package com.campusbrain.app.ui.docs

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialSharedAxis
import com.google.android.material.transition.MaterialFadeThrough
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.campusbrain.app.R
import com.campusbrain.app.data.BrainRepository
import com.campusbrain.app.data.DocCatalog
import com.campusbrain.app.data.DocumentSummary
import com.campusbrain.app.data.IngestResult
import com.campusbrain.app.data.InitState
import com.campusbrain.app.databinding.FragmentDocDetailBinding
import com.campusbrain.app.databinding.FragmentDocsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The corpus, browsable, and the one place a student adds to it.
 *
 * Doubles as the grounding proof for the Ask screen: a citation chip lands on
 * the exact document the answer came from.
 *
 * Filtering is in-memory. At 58 documents plus whatever the student has added
 * that is instant, and it avoids handing user text to FTS syntax a second time.
 */
class DocsFragment : Fragment() {
    // Sibling tabs are peers, so they cross-fade rather than slide: a
    // directional transition would imply a hierarchy the bottom bar does not
    // have. MaterialFadeThrough carries Material's own easing and duration,
    // which is why it is used instead of a hand-rolled alpha animation.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
    }

    private var _binding: FragmentDocsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: DocAdapter
    private var all: List<DocumentSummary> = emptyList()

    private val imports: ImportViewModel by lazy {
        // Explicit provider rather than the activityViewModels() delegate: the
        // delegate lives in fragment-ktx and this keeps the dependency on what
        // the build file actually declares. Activity scope is the point — see
        // ImportViewModel's doc comment.
        ViewModelProvider(requireActivity())[ImportViewModel::class.java]
    }

    /**
     * Registered as a field so it is in place before the fragment reaches
     * STARTED; registering later throws.
     *
     * OpenDocument rather than GetContent: it returns a stable, re-readable
     * document Uri from the system picker, and the picker is a surface the
     * student already knows from every other app on the phone.
     */
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport(uri)
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentDocsBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = DocAdapter(
            onClick = { doc ->
                findNavController().navigate(
                    R.id.docDetailFragment, Bundle().apply { putString("docId", doc.docId) })
            },
            onRemove = ::confirmRemove,
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        binding.addDoc.setOnClickListener { picker.launch(IMPORT_MIME_TYPES) }
        binding.importResult.importDismiss.setOnClickListener { imports.dismiss() }
        binding.importResult.importAsk.setOnClickListener {
            imports.dismiss()
            // Through the bottom bar rather than navigate(), so the tab
            // selection and the back stack behave exactly as a tap would.
            requireActivity().findViewById<BottomNavigationView>(R.id.bottomNav)
                ?.selectedItemId = R.id.askFragment
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                imports.state.collect { render(it) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch { load() }
    }

    private fun startImport(uri: android.net.Uri) {
        if (!imports.start(uri)) {
            Toast.makeText(requireContext(), R.string.import_busy, Toast.LENGTH_LONG).show()
        }
    }

    // --- import rendering --------------------------------------------------

    private fun render(state: ImportState) {
        when (state) {
            is ImportState.Idle -> {
                binding.importProgress.root.visibility = View.GONE
                binding.importResult.root.visibility = View.GONE
            }
            is ImportState.Working -> {
                binding.importResult.root.visibility = View.GONE
                renderWorking(state)
            }
            is ImportState.Done -> {
                binding.importProgress.root.visibility = View.GONE
                renderDone(state)
            }
        }
    }

    private fun renderWorking(state: ImportState.Working) {
        val p = binding.importProgress
        val indeterminate = state.total <= 0
        setProgressMode(indeterminate)

        p.progressLabel.text = getString(
            if (indeterminate) R.string.import_extracting else R.string.import_indexing
        )
        if (indeterminate) {
            // A count that cannot be given is worse than no count.
            p.progressDetail.visibility = View.GONE
        } else {
            p.progressBar.max = state.total
            p.progressBar.setProgressCompat(state.done, true)
            p.progressDetail.text = getString(
                R.string.import_progress_fmt, state.done, passages(state.total)
            )
            p.progressDetail.visibility = View.VISIBLE
        }
        p.root.visibility = View.VISIBLE
    }

    /**
     * Flips the shared progress component between its two modes, and does it
     * only while the card is hidden.
     *
     * Material's BaseProgressIndicator throws IllegalStateException when it is
     * asked to switch *into* indeterminate mode while it is visible to the
     * user, and this screen switches both ways: indeterminate while the file
     * is read, determinate once the chunk count lands, and back to
     * indeterminate the next time the student adds something. Hiding the root
     * first makes the indicator's own visibleToUser() check false — it walks
     * the parent chain synchronously — so neither direction can throw.
     */
    private fun setProgressMode(indeterminate: Boolean) {
        val bar = binding.importProgress.progressBar
        if (bar.isIndeterminate == indeterminate) return
        val root = binding.importProgress.root
        val restore = root.visibility
        root.visibility = View.GONE
        bar.isIndeterminate = indeterminate
        root.visibility = restore
    }

    private fun renderDone(state: ImportState.Done) {
        val c = binding.importResult
        c.importNote.visibility = View.GONE
        c.importAsk.visibility = View.GONE

        when (val r = state.result) {
            is IngestResult.Ok -> {
                c.importIcon.setImageResource(R.drawable.ic_check)
                c.importTitle.text = getString(R.string.import_ok_title_fmt, r.title)
                c.importBody.text =
                    getString(R.string.import_ok_body_fmt, passages(r.chunks))
                // The limitations, stated at the moment the claim is made.
                //
                // Both, when both apply — not one or the other. They are
                // independent facts, and picking one with an if/else meant the
                // more degraded case said LESS about the graph, which is the
                // limitation this feature most has to be honest about. The
                // graph note leads because it is permanent; the keyword-only
                // caveat follows because it describes a missing model file and
                // can stop being true.
                c.importNote.text = buildString {
                    append(getString(R.string.import_graph_note))
                    if (state.keywordOnly) {
                        append("\n\n")
                        append(getString(R.string.import_keyword_only_note))
                    }
                }
                c.importNote.visibility = View.VISIBLE
                c.importAsk.visibility = View.VISIBLE
                // The row it just created has to actually be there when the
                // student looks for it.
                viewLifecycleOwner.lifecycleScope.launch { load() }
            }
            is IngestResult.Unsupported -> {
                c.importIcon.setImageResource(R.drawable.ic_alert)
                c.importTitle.text = getString(R.string.import_unsupported_title)
                // Verbatim, by contract. The backend's message already names
                // the formats that do work and tells the student what to do
                // with a PDF; restating it here would let the two drift.
                c.importBody.text = r.message
            }
            is IngestResult.Failed -> {
                c.importIcon.setImageResource(R.drawable.ic_alert)
                c.importTitle.text = getString(R.string.import_failed_title)
                c.importBody.text = r.reason
            }
            // The branch the fourth IngestResult forced open, which is the
            // point of having added it to a sealed type rather than folding a
            // licence refusal into Failed: this outcome needs a different
            // headline, a different sentence and a different next step, and a
            // compile error is what made sure it got them.
            //
            // It is also the one outcome that is not a fault. Nothing broke
            // and the student did nothing wrong, so the copy says what the
            // allowance is, says plainly that everything already added is
            // untouched and still searchable, and offers the licence screen
            // rather than a retry that would fail the same way.
            is IngestResult.LicenseRequired -> {
                c.importIcon.setImageResource(R.drawable.ic_alert)
                c.importTitle.text = getString(R.string.import_capped_title)
                c.importBody.text = when (r.limit) {
                    IngestResult.LicenseRequired.Limit.DOCUMENTS ->
                        resources.getQuantityString(
                            R.plurals.import_capped_docs_fmt, r.cap, r.cap
                        )
                    IngestResult.LicenseRequired.Limit.KILOBYTES ->
                        getString(R.string.import_capped_kb_fmt, r.used, r.cap)
                }
                c.importNote.setText(R.string.import_capped_note)
                c.importNote.visibility = View.VISIBLE
            }
        }
        c.root.visibility = View.VISIBLE
    }

    // --- the list ----------------------------------------------------------

    private suspend fun load() {
        val repo = (BrainRepository.state.value as? InitState.Ready)?.repo
        // One call, not a union assembled here. DocsRepository.all() merges the
        // bundled corpus with the user's own and sets isUserAdded on the latter
        // itself; adding ingest.added() on top would list every imported
        // document twice and double-count it in the line above the list.
        all = withContext(Dispatchers.IO) { repo?.docs?.all() ?: emptyList() }
        if (_binding != null) applyFilter(binding.search.text?.toString().orEmpty())
    }

    private fun applyFilter(term: String) {
        val t = term.trim().lowercase()
        val shown = if (t.isEmpty()) all else all.filter {
            it.title.lowercase().contains(t) || it.category.lowercase().contains(t) ||
                it.docId.lowercase().contains(t)
        }
        val mine = all.count { it.isUserAdded }
        binding.count.text = when {
            t.isNotEmpty() -> getString(R.string.docs_count_filtered_fmt, shown.size, all.size)
            mine == 0 -> getString(R.string.docs_count_fmt, all.size)
            else -> getString(R.string.docs_count_split_fmt, all.size - mine, mine)
        }
        adapter.submit(shown)
    }

    private fun confirmRemove(doc: DocumentSummary) {
        // Deleting the student's own file is irreversible and there is no undo
        // to offer, so it asks. The bundled corpus never reaches this path:
        // the affordance is GONE on those rows.
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remove_title)
            .setMessage(R.string.remove_body)
            .setNegativeButton(R.string.remove_cancel, null)
            .setPositiveButton(R.string.remove_confirm) { _, _ -> remove(doc) }
            .show()
    }

    private fun remove(doc: DocumentSummary) {
        viewLifecycleOwner.lifecycleScope.launch {
            val repo = (BrainRepository.state.value as? InitState.Ready)?.repo
            val ok = withContext(Dispatchers.IO) { repo?.ingest?.remove(doc.docId) ?: false }
            if (ok) load()
            else Toast.makeText(requireContext(), R.string.remove_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    companion object {
        /**
         * What the picker offers.
         *
         * Wider than the list of formats that actually parse, on purpose.
         * `application/pdf` is here even though this build refuses PDFs: left
         * out, a student's PDF is simply greyed out in the picker with no
         * explanation, and the refusal copy that tells them to save it as
         * .docx becomes unreachable from the primary path. Being told why beats
         * being silently unable to choose.
         *
         * `application/octet-stream` is here because providers routinely report
         * .md that way. DocumentIngest keys on the file extension before the
         * MIME type, so a mislabelled file still ingests correctly — which
         * argues for a permissive filter rather than a precise one.
         */
        val IMPORT_MIME_TYPES = arrayOf(
            "text/*",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/pdf",
            "application/octet-stream",
        )

        fun passages(n: Int): String = if (n == 1) "1 passage" else "$n passages"
    }
}

/** Flat list with sticky-ish category headers, built as two view types. */
class DocAdapter(
    private val onClick: (DocumentSummary) -> Unit,
    private val onRemove: (DocumentSummary) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class Header(val title: String) : Row
        data class Doc(val doc: DocumentSummary) : Row
    }

    private var rows: List<Row> = emptyList()

    fun submit(docs: List<DocumentSummary>) {
        val out = mutableListOf<Row>()
        var current: String? = null
        // Category order comes from DocCatalog, which is where it is stated
        // once for the whole app. Sorting alphabetically instead would both
        // float "Added by you" to the top by accident and stop Research Papers
        // sinking, landing a journal paper in the middle of the circulars.
        // ORDER[0] is UserCorpusDb.ADDED_CATEGORY itself, so the student's own
        // documents lead without needing a special case here.
        //
        // The category is a tiebreaker and not decoration: orderOf returns the
        // same sink value for EVERY category it does not know, so without it
        // two unknown categories interleave by title and the loop below emits
        // their headers repeatedly — Header(A), doc, Header(B), doc, Header(A).
        // The bundle cannot trigger that today, because it has no documents
        // table and every category is derived. A future bundle that stores its
        // own can.
        docs.sortedWith(
            compareBy(
                { DocCatalog.orderOf(it.category) },
                { it.category },
                { it.title.lowercase() },
            )
        ).forEach {
                if (it.category != current) {
                    current = it.category
                    out.add(Row.Header(it.category))
                }
                out.add(Row.Doc(it))
            }
        rows = out
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderVH(inf.inflate(R.layout.item_doc_header, parent, false))
        else DocVH(inf.inflate(R.layout.item_document, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is Row.Header -> (holder as HeaderVH).title.text = r.title.uppercase()
            is Row.Doc -> (holder as DocVH).bind(r.doc)
        }
    }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.header)
    }

    inner class DocVH(v: View) : RecyclerView.ViewHolder(v) {
        private val title: TextView = v.findViewById(R.id.title)
        private val preview: TextView = v.findViewById(R.id.preview)
        private val meta: TextView = v.findViewById(R.id.meta)
        private val remove: ImageView = v.findViewById(R.id.remove)

        fun bind(d: DocumentSummary) {
            title.text = d.title
            preview.text = d.preview?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            preview.visibility = if (preview.text.isNullOrBlank()) View.GONE else View.VISIBLE
            // The raw docId is a source filename ("26_attendance_condonation.md").
            // It is an artefact of how the bundle was built, means nothing to a
            // student, and made every row look like a directory listing.
            meta.text = DocsFragment.passages(d.chunkCount)
            itemView.setOnClickListener { onClick(d) }

            // Never offered on a bundled document. The category header above
            // already says "Added by you", so the row itself needs no badge —
            // the delete button is the only thing that distinguishes it, and
            // that is the distinction the student can act on.
            if (d.isUserAdded) {
                remove.visibility = View.VISIBLE
                remove.setOnClickListener { onRemove(d) }
            } else {
                remove.visibility = View.GONE
                // Cleared, not left over: holders recycle between a bundled row
                // and an added one, and a stale listener on an invisible button
                // is exactly the bug that deletes the wrong document.
                remove.setOnClickListener(null)
            }
        }
    }
}

/** One document's chunks, grouped by section. */
class DocDetailFragment : Fragment() {
    // Opening one document is a step down the hierarchy, so it moves along Z:
    // the list recedes and the detail comes forward. Back reverses it.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    private var _binding: FragmentDocDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentDocDetailBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val docId = requireArguments().getString("docId").orEmpty()
        binding.title.text = com.campusbrain.app.data.DocsRepository.titleFor(docId)
        binding.chunks.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            val repo = (BrainRepository.state.value as? InitState.Ready)?.repo
            // Whether this is the student's own document is resolved here
            // rather than passed in a nav argument, because the other way in
            // is a citation chip under an answer, and that only carries a
            // docId. Both entry points have to behave the same.
            val mine = withContext(Dispatchers.IO) {
                repo?.ingest?.added()?.firstOrNull { it.docId == docId }
            }
            if (mine != null) {
                // DocCatalog.titleFor strips a leading number, which is right
                // for the bundle's catalogue-generated names and wrong here: a
                // student who called a file "2026 fee receipt" meant the year.
                // Use the title ingestion actually stored.
                binding.title.text = mine.title
            }

            val chunks = withContext(Dispatchers.IO) { repo?.docs?.chunksOf(docId) ?: emptyList() }
            // The same count the Documents row showed, worded the same way.
            // Arriving here from a citation should confirm the figure rather
            // than quietly present a different one.
            val count = DocsFragment.passages(chunks.size)
            binding.meta.text =
                if (mine != null) getString(R.string.detail_added_meta_fmt, count) else count
            binding.chunks.adapter = ChunkAdapter(chunks)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class ChunkAdapter(
    private val chunks: List<com.campusbrain.app.data.RetrievedChunk>,
) : RecyclerView.Adapter<ChunkAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val rule: View = v.findViewById(R.id.rule)
        val section: TextView = v.findViewById(R.id.section)
        val content: TextView = v.findViewById(R.id.content)
    }

    override fun getItemCount() = chunks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_chunk, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = chunks[position]
        val heading = c.section
        // Repeating an identical heading on consecutive chunks is noise.
        val prev = if (position > 0) chunks[position - 1].section else null
        // The separator belongs between passages, so the first one has none.
        // It lives in the item rather than in a DividerItemDecoration because
        // it has to be inset by the same gutter as the text.
        holder.rule.visibility = if (position == 0) View.GONE else View.VISIBLE
        holder.section.text = heading.orEmpty()
        holder.section.visibility =
            if (heading.isNullOrBlank() || heading == prev) View.GONE else View.VISIBLE
        holder.content.text = c.content.trim()
    }
}
