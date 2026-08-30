package com.kriet.campusbrain.retrieval

import com.kriet.campusbrain.data.BrainDb
import com.kriet.campusbrain.data.query

/**
 * LOCAL route's graph arm: entity linking plus a 2-hop neighbourhood walk over
 * graph_edges.
 *
 * The whole edge set is loaded into an adjacency map at warm-up. When the
 * bundle carries no graph -- the graph build stages need Ollama and are not
 * part of the offline export -- every method returns empty and the router
 * degrades to vector context, which is the same thing the backend does on an
 * entity-link miss.
 */
class GraphTraverse(private val db: BrainDb) {

    private val adjacency = HashMap<String, MutableList<Triple<String, String, String>>>()
    private var nodes: List<String> = emptyList()
    private var warmed = false

    fun warm() {
        if (warmed) return
        warmed = true
        if (!db.hasTable("graph_edges")) return
        db.conn.query("SELECT src, rel, dst FROM graph_edges") {
            Triple(it.getText(0), it.getText(1), it.getText(2))
        }.forEach { e ->
            adjacency.getOrPut(e.first.lowercase()) { mutableListOf() }.add(e)
            adjacency.getOrPut(e.third.lowercase()) { mutableListOf() }.add(e)
        }
        nodes = adjacency.keys.toList()
    }

    val edgeCount: Int get() = adjacency.values.sumOf { it.size } / 2

    /**
     * Match entity names appearing in the question against graph nodes.
     *
     * Deliberately conservative: a node must appear as a whole phrase in the
     * query. The backend's own comments record that loose matching latched onto
     * junk nodes like "the campus" and "committee", which then made `edges`
     * non-empty and suppressed the vector fallback that would have answered.
     */
    fun linkEntities(query: String, max: Int = 5): List<String> {
        warm()
        if (nodes.isEmpty()) return emptyList()
        val q = query.lowercase()
        return nodes
            .filter { it.length >= 4 && q.contains(it) }
            .sortedByDescending { it.length }
            .take(max)
    }

    fun neighborhood(entities: List<String>, hops: Int = 2, maxEdges: Int = 40): List<String> {
        warm()
        if (entities.isEmpty() || adjacency.isEmpty()) return emptyList()
        val seen = LinkedHashSet<String>()
        var frontier = entities.map { it.lowercase() }.toSet()
        val visited = HashSet<String>()
        repeat(hops) {
            val next = HashSet<String>()
            for (node in frontier) {
                if (!visited.add(node)) continue
                adjacency[node]?.forEach { (s, r, d) ->
                    if (seen.size < maxEdges) seen.add("$s -> $r -> $d")
                    next.add(s.lowercase()); next.add(d.lowercase())
                }
            }
            frontier = next
            if (seen.size >= maxEdges) return seen.toList()
        }
        return seen.toList()
    }
}
