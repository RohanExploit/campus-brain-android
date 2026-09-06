package com.campusbrain.app.retrieval

import com.campusbrain.app.answer.AnswerComposer
import com.campusbrain.app.answer.CloudAnswer
import com.campusbrain.app.answer.PremiseCheck
import com.campusbrain.app.answer.TopicGate
import com.campusbrain.app.data.AnswerResult
import com.campusbrain.app.data.BrainDb
import com.campusbrain.app.data.Route
import com.campusbrain.app.data.Source
import com.campusbrain.app.data.query
import kotlinx.coroutines.runBlocking

/**
 * On-device port of retrieval/router.py.
 *
 * The backend classifies in three stages: deterministic rules, then an Ollama
 * call, then FACT on any exception. There is no Ollama on a phone, so stage two
 * is replaced rather than dropped -- dropping it would make LOCAL and GLOBAL
 * unreachable. What survives unchanged is the shape: rules first, a fallible
 * middle, and FACT as the answer to "I am not sure".
 */
class QueryRouter(
    private val db: BrainDb,
    private val hybrid: HybridSearch,
    private val tabular: TabularQueries,
    private val graph: GraphTraverse,
    private val prototypes: RoutePrototypes?,
    // Null in any environment that has not wired a Context in yet (e.g. a
    // plain unit test constructing QueryRouter directly). A null cloud
    // client behaves exactly like a cloud call that always fails: the
    // existing abstention text, unchanged.
    private val cloud: CloudAnswer? = null,
) {

    /**
     * The corpus's own vocabulary, for the off-topic check in
     * [com.campusbrain.app.answer.AnswerCheck.unsupportedSubject].
     *
     * Lazy because it is a scan per distinct word and a router that only ever
     * answers TABULAR questions never needs one, and held here rather than
     * inside AnswerComposer because that object is deliberately stateless and
     * database-free.
     */
    private val vocabulary by lazy { CorpusWords(db) }

    fun answer(rawQuery: String): AnswerResult {
        // Blank input must be rejected BEFORE routing. Left to fall through it
        // matches no rule, lands on TABULAR's roster branch, and prints every
        // student's name on screen. api/main.py:288-291 guards the same way.
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            return AnswerResult(Route.FACT, "Ask me something about the campus.")
        }

        // A question that is really two questions is split before anything
        // else looks at it. Doing this AFTER template matching would be worse
        // than useless: "how many students failed and what is the pass
        // percentage" matches pass_percentage, the constraint guard would then
        // see an unevaluated fail filter and caveat a figure that a second
        // template answers exactly.
        val parts = CompoundQuestion.split(query)
        if (parts.size > 1) {
            answerCompound(parts)?.let { return it }
        }
        return answerSingle(query, mutableListOf(), allowCloud = true)
    }

    /**
     * Answers each half and joins them, or returns null to let the caller fall
     * back to the whole query.
     *
     * Null on anything less than two answered halves is the safety property:
     * one answered half plus one abstention is precisely what the app already
     * produced for these questions, so the unsplit path reproduces today's
     * behaviour exactly and the split can only add.
     *
     * The cloud fallback is off for every half after the first. A fragment
     * such as "what happens if I miss it", handed to a general-knowledge model
     * without the sentence that gave "it" a referent, is the confident-and-
     * wrong shape this codebase keeps paying to remove.
     */
    private fun answerCompound(parts: List<String>): AnswerResult? {
        val answered = ArrayList<AnswerResult>()
        val trace = mutableListOf<Pair<String, String>>()
        trace += "compound" to "${parts.size} questions: " + parts.joinToString(" | ")
        parts.forEachIndexed { i, part ->
            val effective = if (i == 0) part else CompoundQuestion.carryOver(part, parts[0])
            val sub = mutableListOf<Pair<String, String>>()
            val r = runCatching {
                answerSingle(effective, sub, allowCloud = i == 0)
            }.getOrNull()
            // Prefixed so two halves cannot both write a "route" row into one
            // flat trace list, which the trace renderer is not ours to change.
            sub.forEach { (k, v) -> trace += "q${i + 1}.$k" to v }
            if (r != null && !r.abstained) answered += r
        }
        if (answered.size < 2) return null

        val leads = answered.map { it.answer.trim() }.distinct()
        if (leads.size < 2) return null
        // A bare "route" row at the top, because the trace panel is not ours
        // to change and every other answer in the app writes one.
        trace.add(0, "route" to
            "${answered.first().route} (compound: ${answered.size} of ${parts.size} halves answered)")
        return AnswerResult(
            answered.first().route,
            leads.joinToString("\n\n"),
            answered.flatMap { it.passages }.distinct(),
            answered.flatMap { it.sources }.distinct(),
            trace,
            abstained = false,
        )
    }

    private fun answerSingle(
        query: String,
        trace: MutableList<Pair<String, String>>,
        allowCloud: Boolean,
    ): AnswerResult {
        val (route, reason) = classify(query)
        trace += "route" to "$route ($reason)"

        // A question can be well-formed, correctly routed, and still have no
        // true answer, because its premise is false. "How many students got an
        // A+ grade" is one: there is no A+ on this scale. Checked here rather
        // than inside a route so it covers all four, and after classification
        // so the route label stays honest about where the question was headed.
        //
        // Reported as an answer, not an abstention, on purpose. Abstaining
        // would hand it to the cloud fallback, which would cheerfully describe
        // some other institution's A+ scale as though it were this one's.
        PremiseCheck.gradeScale(query)?.let { correction ->
            trace += "premise_check" to "grade named in the question is not on this scale"
            trace += "generation" to "deterministic (grade scale from Grades.kt)"
            return AnswerResult(route, correction, emptyList(), emptyList(), trace, abstained = false)
        }

        return when (route) {
            Route.TABULAR -> answerTabular(query, trace, allowCloud)
            Route.GLOBAL -> answerVector(
                query, Route.GLOBAL, HybridSearch.GLOBAL_FANOUT_K, trace, allowCloud, dedupeByDoc = true
            )
            Route.LOCAL -> answerLocal(query, trace, allowCloud)
            Route.FACT -> answerVector(query, Route.FACT, HybridSearch.FACT_TOP_K, trace, allowCloud)
        }
    }

    // --- stage 1 + 2: classification -------------------------------------

    fun classify(query: String): Pair<Route, String> {
        // Stage 1: deterministic rules, extracted to RouteRules so they are
        // unit-testable without a database.
        RouteRules.classify(query)?.let { return it }

        // Stage 2 replaces the Ollama classify call with cosine against three
        // pre-computed route prototypes. Requires the query embedder; without
        // it everything unmatched is FACT, which is what the backend does when
        // the LLM call raises.
        prototypes?.classify(query)?.let { (r, detail) -> return r to "prototype: $detail" }
        return Route.FACT to "default"
    }

    // --- stage 3: per-route retrieval ------------------------------------

    private fun answerVector(
        query: String,
        route: Route,
        topK: Int,
        trace: MutableList<Pair<String, String>>,
        allowCloud: Boolean,
        dedupeByDoc: Boolean = false,
    ): AnswerResult {
        val res = hybrid.search(query, topK)
        if (res.usedLikeFallback) trace += "fts" to "UNAVAILABLE - LIKE fallback in use"
        else trace += "fts" to "${res.ftsHits} hits"
        trace += "vector" to if (res.vecHits > 0) "${res.vecHits} hits" else "unavailable (fts-only)"

        var chunks = res.chunks
        if (dedupeByDoc) {
            // A 30-chunk fan-out over one long document is not a corpus-wide
            // answer, which is the whole point of the GLOBAL route.
            chunks = chunks.distinctBy { it.docId }
            trace += "global_mode" to "chunks (deduped to ${chunks.size} documents)"
        }
        val packed = hybrid.pack(chunks)
        trace += "fused" to "${packed.size} chunks, ${res.bothCount} found by both arms"
        // Named explicitly rather than left to be inferred from the citations:
        // whether an answer leaned on the user's own imports is the first thing
        // to check when an answer looks wrong after an import.
        if (res.userHits > 0) trace += "user_corpus" to "${res.userHits} of your own documents"
        trace += "context" to "${packed.sumOf { it.content.length }} / ${HybridSearch.CONTEXT_BUDGET_CHARS} chars"
        trace += "generation" to "extractive"

        val composed = AnswerComposer.compose(query, packed, vocabulary = vocabulary)
        trace += "answer_check" to composed.reason
        return withCloudFallback(
            query, route, composed,
            passages = composed.passages.map { it.heading to it.body },
            sources = packed.map { Source(it.docId, it.section, it.isUserAdded) }.distinct(),
            trace = trace,
            allowCloud = allowCloud,
        )
    }

    // --- cloud fallback ----------------------------------------------------

    /**
     * The one place AnswerComposer's abstention is allowed to be overridden.
     * AnswerComposer itself stays pure and offline -- this is deliberately
     * NOT logic added to it. See its doc comment: abstention there exists
     * because a wrong confident answer about the corpus is the one failure
     * that discredits every correct answer beside it. That reasoning still
     * holds for the corpus. It does not extend to a general-knowledge
     * question the corpus was never going to have an opinion on -- refusing
     * those outright is the failure this whole path exists to fix.
     *
     * - Not an education question at all (TopicGate says no): keep
     *   abstaining, but say plainly that it's out of scope rather than
     *   printing the generic "I don't have enough information" sentence.
     * - An education question the corpus missed: try Groq. Success is
     *   labelled as general guidance, never as corpus fact, and is not
     *   flagged as abstained -- it IS an answer, just not from the
     *   student's own records.
     * - Cloud call fails for any reason (no config, no network, rate
     *   limited, timeout, bad response): fall back to the existing
     *   abstention text, unchanged.
     */
    private fun withCloudFallback(
        query: String,
        route: Route,
        composed: AnswerComposer.Composed,
        passages: List<Pair<String, String>>,
        sources: List<Source>,
        trace: MutableList<Pair<String, String>>,
        allowCloud: Boolean,
    ): AnswerResult {
        if (!composed.abstained) {
            return AnswerResult(route, composed.lead, passages, sources, trace, abstained = false)
        }

        // Suppressed for the second half of a compound question. See
        // answerCompound: a fragment whose subject was in the other half is
        // the worst possible thing to hand a general-knowledge model.
        if (!allowCloud) {
            trace += "cloud_fallback" to "suppressed (secondary half of a compound question)"
            return AnswerResult(route, composed.lead, passages, sources, trace, abstained = true)
        }

        if (!TopicGate.isEducational(query)) {
            trace += "cloud_fallback" to "skipped (not educational)"
            val lead = "This app answers questions about college and campus life, " +
                "and this looks like it falls outside that."
            return AnswerResult(route, lead, passages, sources, trace, abstained = true)
        }

        // Suppressed for the third reason, and the narrowest one: the question
        // names a campus subject, and the college's records hold nothing on it
        // -- see AnswerComposer.Composed.offTopic. There is no grounding to
        // send, and what would come back is a general model's idea of which
        // students at THIS college were caught cheating, which is a claim about
        // the records dressed as an answer.
        //
        // [TopicGate.namesCampusSubject], not [TopicGate.isEducational], and
        // the difference is the whole scope of this suppression. isEducational
        // answers true for anything ambiguous, by design, so using it here
        // would close the cloud path on every general question the corpus does
        // not cover -- which is the path's entire purpose. What is being
        // refused is narrower: "students caught cheating" would be read as a
        // statement about this cohort, and "what is a good laptop" would not.
        //
        // Sources are dropped too. The citation list is built from whatever
        // retrieval ranked highest, and under a reply that says nothing here is
        // relevant, naming three documents contradicts the sentence above them.
        if (composed.offTopic && TopicGate.namesCampusSubject(query)) {
            trace += "cloud_fallback" to "suppressed (corpus holds nothing on this subject)"
            return AnswerResult(route, composed.lead, passages, emptyList(), trace, abstained = true)
        }

        // RAG, not a chatbot: whatever the retrieval pass DID find (even the
        // weak match that made AnswerComposer abstain) goes to the model as
        // grounding context before it is allowed to reach for general
        // knowledge. Empty passages fall back to context = null inside
        // CloudAnswer, which is the pure-general-knowledge path.
        val contextText = passages.joinToString("\n\n") { (heading, body) -> "$heading: $body" }
            .takeIf { it.isNotBlank() }
        val cloudAnswer = cloud?.let { c ->
            runCatching { runBlocking { c.answerWithProvenance(query, contextText) } }.getOrNull()
        }
        val cloudText = cloudAnswer?.text
        if (cloudText != null) {
            trace += "cloud_fallback" to
                (if (cloudAnswer.grounded) "cloud (grounded in corpus, draft + verify pass)"
                 else "cloud (general knowledge, draft + verify pass)")
            // No model name in the user-facing label -- what matters to the
            // student is provenance, not which vendor answered.
            //
            // The two labels are not interchangeable. Retrieval returns its
            // top chunks whether or not they are relevant, so supplying
            // context is not evidence the answer used it: a revaluation
            // question retrieved an unrelated research paper and was answered
            // from general knowledge, while a bonafide question retrieved the
            // right notice and returned that notice's own office, timeline and
            // phone number. Labelling both "not from your college's records"
            // understated the second as badly as the reverse would overstate
            // the first, so the label follows what the model reported doing.
            val lead = cloudText + "\n\n" + if (cloudAnswer.grounded)
                "[From your college's documents, with general guidance where they were silent]"
            else
                "[General guidance, not from your college's records]"
            return AnswerResult(route, lead, passages, sources, trace, abstained = false)
        }

        trace += "cloud_fallback" to "unavailable"
        return AnswerResult(route, composed.lead, passages, sources, trace, abstained = true)
    }

    private fun answerLocal(
        query: String,
        trace: MutableList<Pair<String, String>>,
        allowCloud: Boolean,
    ): AnswerResult {
        val linked = graph.linkEntities(query)
        val edges = graph.neighborhood(linked, hops = 2, maxEdges = 40)
        trace += "linked_entities" to if (linked.isEmpty()) "(none)" else linked.joinToString(", ")

        if (edges.isEmpty()) {
            // Entity absent from the graph, or no graph at all. Degrade to
            // vector context rather than answering from nothing. The route
            // label stays LOCAL: relabelling it FACT would inflate route
            // accuracy against a LOCAL-expected question without answering it
            // any better.
            trace += "local_mode" to "graph_miss_vector"
            val res = hybrid.search(query, 3)
            val packed = hybrid.pack(res.chunks)
            trace += "fused" to "${packed.size} chunks"
            trace += "generation" to "extractive"
            val composed = AnswerComposer.compose(query, packed, vocabulary = vocabulary)
            trace += "answer_check" to composed.reason
            return withCloudFallback(
                query, Route.LOCAL, composed,
                passages = composed.passages.map { it.heading to it.body },
                sources = packed.map { Source(it.docId, it.section, it.isUserAdded) }.distinct(),
                trace = trace,
                allowCloud = allowCloud,
            )
        }

        // Hybrid mode: edges and chunks fail in disjoint places. Edges follow
        // the relation; chunks carry the sentence the relation was stated in.
        val edgeText = edges.joinToString("\n")
        val res = hybrid.search(query, HybridSearch.LOCAL_VECTOR_K)
        val packed = hybrid.pack(
            res.chunks,
            maxOf(1000, HybridSearch.CONTEXT_BUDGET_CHARS - edgeText.length)
        )
        trace += "local_mode" to "hybrid (${edges.size} edges + ${packed.size} chunks)"
        trace += "generation" to "extractive"
        val composed = AnswerComposer.compose(query, packed, prefix = edgeText, vocabulary = vocabulary)
        trace += "answer_check" to composed.reason
        return withCloudFallback(
            query, Route.LOCAL, composed,
            passages = composed.passages.map { it.heading to it.body },
            sources = packed.map { Source(it.docId, it.section, it.isUserAdded) }.distinct(),
            trace = trace,
            allowCloud = allowCloud,
        )
    }

    private fun answerTabular(
        query: String,
        trace: MutableList<Pair<String, String>>,
        allowCloud: Boolean,
    ): AnswerResult {
        // The student tables hold exactly one college. Every template below
        // matches on keywords alone, so "how many students failed at IIT
        // Bombay" matched result_count and answered this college's 35 as if it
        // were IIT Bombay's -- confidently, with a TABULAR badge. Refuse before
        // any template runs. Only TABULAR is gated: a FACT question mentioning
        // another institution is legitimately answerable from the papers in
        // the corpus, but a student-record question about one is not.
        ScopeGate.foreignInstitution(query)?.let { institution ->
            trace += "scope_gate" to "foreign institution: $institution"
            return AnswerResult(
                Route.TABULAR, ScopeGate.refusal(institution),
                emptyList(), emptyList(), trace, abstained = true,
            )
        }

        var answer = ""
        try {
            // A deterministic template first: no generation, exact SQL. Note
            // resolve(), not match(): a template that answers only part of the
            // question has to say so. See SqlTemplates.Resolution.Partial --
            // the caveat below is the obligation that contract creates, and
            // this is the only place that discharges it.
            when (val resolved = SqlTemplates.resolve(query)) {
                is SqlTemplates.Resolution.Answered -> {
                    val r = resolved.match.run(tabular)
                    trace += "template" to r.template
                    trace += "debug_sql" to r.debugSql
                    answer = r.answer
                }
                is SqlTemplates.Resolution.Partial -> {
                    val r = resolved.match.run(tabular)
                    trace += "template" to r.template + " (partial)"
                    trace += "constraint_guard" to
                        "not evaluated: " + resolved.ignored.joinToString(", ") { it.name }
                    trace += "debug_sql" to r.debugSql
                    answer = r.answer + "\n\n" + SqlTemplates.caveat(resolved.ignored)
                }
                SqlTemplates.Resolution.None -> {
                    val intent = TabularIntent.classify(query)
                    trace += "intent" to intent.kind
                    answer = runIntent(intent, query, trace)
                    // The intent cascade runs real SQL too, so it owes the
                    // same declaration. Without this, "how many students below
                    // 6 SGPA are in the hostel" lists all 25 students below 6
                    // and never mentions the hostel -- the template path's
                    // defect, reached through the other door.
                    val ignored = SqlTemplates.unmodelled(
                        SqlTemplates.INTENT_TEMPLATES[intent.kind], query
                    )
                    if (answer.isNotBlank() && ignored.isNotEmpty()) {
                        trace += "constraint_guard" to
                            "not evaluated: " + ignored.joinToString(", ") { it.name }
                        answer = answer + "\n\n" + SqlTemplates.caveat(ignored)
                    }
                }
            }
        } catch (e: Throwable) {
            // Log the exception type only, never a payload -- these rows are
            // student records.
            trace += "tabular_error" to (e.javaClass.simpleName)
            answer = ""
        }

        if (answer.isBlank()) {
            // TABULAR-miss -> FACT. The single most important line in the
            // router: on the backend stress set this moved FACT accuracy from
            // 50% to 94%. The route is honestly reported as FACT afterwards,
            // because the answer now comes from the vector path.
            trace += "tabular_fallback" to "TABULAR->FACT"
            return answerVector(query, Route.FACT, HybridSearch.FACT_TOP_K, trace, allowCloud)
        }
        trace += "generation" to "deterministic SQL (no model involved)"
        return AnswerResult(Route.TABULAR, answer, emptyList(), emptyList(), trace)
    }

    private fun runIntent(
        intent: TabularIntent.Intent,
        query: String,
        trace: MutableList<Pair<String, String>>,
    ): String = when (intent.kind) {
        "record_by_roll" -> {
            val roll = intent.params["roll"] ?: ""
            val s = tabular.studentByRoll(roll)
            trace += "debug_sql" to "studentByRoll(\"$roll\")"
            if (s == null) "No student found with roll number $roll." else
                tabular.renderStudent(s, tabular.subjectsFor(s.rollNo))
        }
        "name_search" -> {
            val hits = tabular.studentsByName(query)
            trace += "debug_sql" to "studentsByName(tokens)"
            when {
                hits.isEmpty() -> ""
                hits.size == 1 -> tabular.renderStudent(hits[0], tabular.subjectsFor(hits[0].rollNo))
                else -> buildString {
                    append("${hits.size} students match:\n")
                    hits.take(25).forEach {
                        append("- ${it.name ?: "?"} (${it.rollNo}): ${it.result}")
                        it.sgpa?.let { g -> append(", SGPA ${"%.2f".format(g)}") }
                        append('\n')
                    }
                }
            }
        }
        "below_sgpa" -> {
            val t = intent.params["threshold"]?.toDoubleOrNull() ?: 6.0
            val r = tabular.listBelowSgpa(t)
            trace += "debug_sql" to r.debugSql
            r.answer
        }
        // Reachable only through the intent cascade, because SqlTemplates
        // requires the word "sgpa" beside "average" and this branch does not.
        // Wired here as well as there so the intent stops being a dead end --
        // it had one for the whole life of the port.
        "average_sgpa" -> {
            val r = tabular.averageSgpa()
            trace += "debug_sql" to r.debugSql
            r.answer
        }
        "count_failures" -> {
            val r = tabular.subjectFailureCounts()
            trace += "debug_sql" to r.debugSql
            r.answer
        }
        // "dynamic_sql" is the backend's LLM text-to-SQL branch. There is no
        // offline equivalent, and a small on-device model writing SQL against
        // student records is a fabrication risk rather than a feature. Return
        // empty so the TABULAR->FACT fallback answers from documents instead.
        else -> ""
    }

}
