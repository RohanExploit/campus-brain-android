package com.kriet.campusbrain.retrieval

import com.kriet.campusbrain.answer.AnswerComposer
import com.kriet.campusbrain.data.AnswerResult
import com.kriet.campusbrain.data.BrainDb
import com.kriet.campusbrain.data.Route
import com.kriet.campusbrain.data.Source
import com.kriet.campusbrain.data.query

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
) {

    fun answer(rawQuery: String): AnswerResult {
        val trace = mutableListOf<Pair<String, String>>()

        // Blank input must be rejected BEFORE routing. Left to fall through it
        // matches no rule, lands on TABULAR's roster branch, and prints every
        // student's name on screen. api/main.py:288-291 guards the same way.
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            return AnswerResult(Route.FACT, "Ask me something about the campus.", trace = trace)
        }

        val (route, reason) = classify(query)
        trace += "route" to "$route ($reason)"

        return when (route) {
            Route.TABULAR -> answerTabular(query, trace)
            Route.GLOBAL -> answerVector(query, Route.GLOBAL, HybridSearch.GLOBAL_FANOUT_K, trace, dedupeByDoc = true)
            Route.LOCAL -> answerLocal(query, trace)
            Route.FACT -> answerVector(query, Route.FACT, HybridSearch.FACT_TOP_K, trace)
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
        trace += "context" to "${packed.sumOf { it.content.length }} / ${HybridSearch.CONTEXT_BUDGET_CHARS} chars"
        trace += "generation" to "extractive"

        val composed = AnswerComposer.compose(query, packed)
        return AnswerResult(
            route = route,
            answer = composed.lead,
            passages = composed.passages.map { it.heading to it.body },
            sources = packed.map { Source(it.docId, it.section) }.distinct(),
            trace = trace,
            abstained = composed.abstained,
        )
    }

    private fun answerLocal(query: String, trace: MutableList<Pair<String, String>>): AnswerResult {
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
            val composed = AnswerComposer.compose(query, packed)
            return AnswerResult(Route.LOCAL, composed.lead,
                composed.passages.map { it.heading to it.body },
                packed.map { Source(it.docId, it.section) }.distinct(), trace, composed.abstained)
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
        val composed = AnswerComposer.compose(query, packed, prefix = edgeText)
        return AnswerResult(Route.LOCAL, composed.lead,
            composed.passages.map { it.heading to it.body },
            packed.map { Source(it.docId, it.section) }.distinct(), trace, composed.abstained)
    }

    private fun answerTabular(query: String, trace: MutableList<Pair<String, String>>): AnswerResult {
        var answer = ""
        try {
            // A deterministic template first: no generation, exact SQL.
            val matched = SqlTemplates.match(query)
            if (matched != null) {
                val r = matched.run(tabular)
                trace += "template" to r.template
                trace += "debug_sql" to r.debugSql
                answer = r.answer
            } else {
                val intent = TabularIntent.classify(query)
                trace += "intent" to intent.kind
                answer = runIntent(intent, query, trace)
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
            return answerVector(query, Route.FACT, HybridSearch.FACT_TOP_K, trace)
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
