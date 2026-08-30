"""Deterministic question-side entity linking for the LOCAL (graph) route.

The old LOCAL path fed the top vector hit's *source filename* to the graph,
which is keyed by extracted *entity names* — so it never matched and context
was always empty. This links entities mentioned in the QUESTION text to graph
nodes with no LLM (reproducible), tuned for PRECISION because a wrong entity
yields a wrong neighborhood.

Matching rules (high precision):
  * Multi-word node  -> its normalized form must occur in the normalized
    question on word boundaries (e.g. node "YSPM's Yashoda Technical Campus"
    matches "... Yashoda Technical Campus ..." via its "yashoda technical
    campus" core; we test node-core-in-question).
  * Single-word node -> the word must appear in the question AND be capitalized
    there (proper-noun heuristic), so common-noun graph junk like "trust",
    "college", "technical" can't spuriously match lowercase question words.
  * Prefer-longer dedup: drop a node whose normalized name is contained in a
    higher-ranked kept node (keeps "RAG-MicroSim", drops bare "RAG").
  * Fuzzy (rapidfuzz token_set_ratio >= threshold) only as a fallback when no
    exact/boundary match is found, and only for multi-word candidates.

link_entities(question, node_names) -> (ranked_node_ids, {node: score}).
"""
import logging
import re

from rapidfuzz import fuzz, process

_STOP = {
    "the", "a", "an", "of", "in", "on", "for", "and", "or", "to", "from", "with",
    "is", "are", "who", "what", "which", "where", "when", "how", "many", "much",
    "list", "all", "student", "students", "paper", "their", "its", "by", "at",
    "this", "that", "did", "do", "does", "have", "has", "was", "were", "about",
    "associated", "affiliated", "located", "authors", "author", "college",
    "university", "institution", "city", "conference", "trust", "runs", "chief",
    "patrons", "name", "give", "me", "tell", "please", "co", "get",
}


def _norm(s: str) -> str:
    s = s.lower().replace("'s", " ").replace("’s", " ")
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


def _tokens(question: str):
    """Return (all_tokens, capitalized_token_set) from the raw question."""
    raw = re.findall(r"[A-Za-z0-9&./'\-]+", question)
    caps = {_norm(t) for t in raw if t[:1].isupper()}
    caps.discard("")
    return raw, caps


def _multiword_candidates(question: str, max_n: int = 4):
    toks = re.findall(r"[A-Za-z0-9&./'\-]+", question)
    out = set()
    for i in range(len(toks)):
        for length in range(2, max_n + 1):
            if i + length > len(toks):
                break
            words = toks[i:i + length]
            if any(w.lower() not in _STOP for w in words):
                out.add(" ".join(words))
    return out


def link_entities(question: str, node_names, threshold: int = 90, limit: int = 3,
                  min_confidence: int = 92):
    node_names = list(node_names)
    qn = " " + _norm(question) + " "
    _, caps = _tokens(question)
    scored: dict[str, float] = {}
    ntoks: dict[str, int] = {}

    for node in node_names:
        nn = _norm(node)
        if not nn:
            continue
        toks = nn.split()
        ntoks[node] = len(toks)
        if len(toks) >= 2:
            # multi-word node: its full normalized form present on word boundaries
            if (" " + nn + " ") in qn:
                scored[node] = 100.0
        else:
            # single-word node: must be a capitalized proper noun in the question
            if len(nn) >= 3 and nn in caps and nn not in _STOP:
                scored[node] = 100.0

    # fuzzy fallback only when nothing matched exactly; restricted to MULTI-word
    # nodes so generic single-word graph junk ("trust", "college") can't sneak in
    # for a question whose real entity simply isn't in the graph.
    if not scored:
        for c in _multiword_candidates(question):
            for node, score, _ in process.extract(
                c, node_names, scorer=fuzz.token_set_ratio, limit=3, score_cutoff=threshold
            ):
                if len(_norm(node).split()) >= 2 and score > scored.get(node, 0):
                    scored[node] = score
                    ntoks[node] = len(_norm(node).split())

    # Confidence gate: a confident WRONG match is worse than no match (a bad
    # link fetches a wrong neighborhood, silently). Drop every candidate below
    # min_confidence; if that empties the set, return EMPTY and log the
    # rejection rather than handing back the nearest node.
    if scored:
        best = max(scored.values())
        if best < min_confidence:
            best_node = max(scored, key=scored.get)
            logging.info(
                "entity_link: best match %r score %.0f < min_confidence=%d for %r; returning empty",
                best_node, best, min_confidence, question,
            )
            return [], {}
        scored = {k: v for k, v in scored.items() if v >= min_confidence}

    # rank by (score, specificity=token count, length) then prefer-longer dedup
    ranked_all = sorted(scored, key=lambda k: (scored[k], ntoks[k], len(k)), reverse=True)
    kept: list[str] = []
    for node in ranked_all:
        nn = _norm(node)
        if any(nn != _norm(k) and nn in _norm(k) for k in kept):
            continue  # substring of an already-kept, more-specific node
        kept.append(node)
        if len(kept) >= limit:
            break
    return kept, {n: scored[n] for n in kept}
