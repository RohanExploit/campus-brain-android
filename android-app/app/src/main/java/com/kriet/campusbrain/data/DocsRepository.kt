package com.kriet.campusbrain.data

/**
 * The corpus, browsable: backs the Documents tab and the citation chips on
 * every answer.
 *
 * A singleton rather than an instance handed around because [titleFor] is
 * called from navigation destinations (DocDetailFragment) that only have a
 * docId argument, not a reference to [BrainRepository]. [init] is called once,
 * from [BrainRepository]'s own init, so the title cache is warm before any
 * screen can navigate to a document.
 */
object DocsRepository {

    private var db: BrainDb? = null
    private var titleCache: Map<String, String> = emptyMap()

    fun init(database: BrainDb) {
        db = database
        titleCache = if (database.hasDocumentsTable) {
            database.conn.query("SELECT doc_id, title FROM documents") {
                it.getText(0) to it.getText(1)
            }.toMap()
        } else {
            emptyMap()
        }
    }

    /** Falls back to the raw doc_id on an older bundle with no `documents` table. */
    fun titleFor(docId: String): String = titleCache[docId] ?: docId

    fun all(): List<DocumentSummary> {
        val database = db ?: return emptyList()
        return if (database.hasDocumentsTable) {
            database.conn.query(
                "SELECT doc_id, title, category, chunk_count, preview FROM documents"
            ) {
                DocumentSummary(
                    docId = it.getText(0),
                    title = it.getText(1),
                    category = if (it.isNull(2)) "Other" else it.getText(2),
                    chunkCount = it.getLong(3).toInt(),
                    preview = if (it.isNull(4)) null else it.getText(4),
                )
            }
        } else {
            // user_version 1: no `documents` table. One row per distinct
            // doc_id, synthesised from chunks so the screen still works, just
            // without a title, category or preview -- see SelfTest's
            // "documents table matches chunks" check for the same fallback.
            database.conn.query("SELECT doc_id, COUNT(*) FROM chunks GROUP BY doc_id") {
                DocumentSummary(
                    docId = it.getText(0),
                    title = it.getText(0),
                    category = "Other",
                    chunkCount = it.getLong(1).toInt(),
                    preview = null,
                )
            }
        }
    }

    fun chunksOf(docId: String): List<RetrievedChunk> {
        val database = db ?: return emptyList()
        return database.conn.query(
            "SELECT id, doc_id, section, content FROM chunks WHERE doc_id = ? ORDER BY id",
            bind = { it.bindText(1, docId) },
        ) {
            RetrievedChunk(
                id = it.getLong(0),
                docId = it.getText(1),
                section = if (it.isNull(2)) null else it.getText(2),
                content = it.getText(3),
                score = 0.0,
            )
        }
    }
}
