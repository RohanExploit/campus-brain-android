package com.kriet.campusbrain.ui.docs

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kriet.campusbrain.R
import com.kriet.campusbrain.data.BrainRepository
import com.kriet.campusbrain.data.DocumentSummary
import com.kriet.campusbrain.data.InitState
import com.kriet.campusbrain.databinding.FragmentDocDetailBinding
import com.kriet.campusbrain.databinding.FragmentDocsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The corpus, browsable. Doubles as the grounding proof for the Ask screen: a
 * citation chip lands on the exact document the answer came from.
 *
 * Filtering is in-memory. At 58 documents that is instant and avoids handing
 * user text to FTS syntax a second time.
 */
class DocsFragment : Fragment() {

    private var _binding: FragmentDocsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: DocAdapter
    private var all: List<DocumentSummary> = emptyList()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentDocsBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = DocAdapter { doc ->
            findNavController().navigate(
                R.id.docDetailFragment, Bundle().apply { putString("docId", doc.docId) })
        }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        viewLifecycleOwner.lifecycleScope.launch {
            all = withContext(Dispatchers.IO) {
                (BrainRepository.state.value as? InitState.Ready)?.repo?.docs?.all() ?: emptyList()
            }
            applyFilter("")
        }
    }

    private fun applyFilter(term: String) {
        val t = term.trim().lowercase()
        val shown = if (t.isEmpty()) all else all.filter {
            it.title.lowercase().contains(t) || it.category.lowercase().contains(t) ||
                it.docId.lowercase().contains(t)
        }
        binding.count.text =
            if (t.isEmpty()) "${all.size} documents in the on-device corpus"
            else "${shown.size} of ${all.size} documents match"
        adapter.submit(shown)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

/** Flat list with sticky-ish category headers, built as two view types. */
class DocAdapter(
    private val onClick: (DocumentSummary) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class Header(val title: String) : Row
        data class Doc(val doc: DocumentSummary) : Row
    }

    private var rows: List<Row> = emptyList()

    fun submit(docs: List<DocumentSummary>) {
        val out = mutableListOf<Row>()
        var current: String? = null
        docs.sortedWith(compareBy({ it.category }, { it.title })).forEach {
            if (it.category != current) { current = it.category; out.add(Row.Header(it.category)) }
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
        fun bind(d: DocumentSummary) {
            title.text = d.title
            preview.text = d.preview?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            preview.visibility = if (preview.text.isNullOrBlank()) View.GONE else View.VISIBLE
            meta.text = "${d.chunkCount} chunks · ${d.docId}"
            itemView.setOnClickListener { onClick(d) }
        }
    }
}

/** One document's chunks, grouped by section. */
class DocDetailFragment : Fragment() {

    private var _binding: FragmentDocDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentDocDetailBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val docId = requireArguments().getString("docId").orEmpty()
        binding.title.text = com.kriet.campusbrain.data.DocsRepository.titleFor(docId)
        binding.chunks.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            val chunks = withContext(Dispatchers.IO) {
                (BrainRepository.state.value as? InitState.Ready)?.repo?.docs?.chunksOf(docId)
                    ?: emptyList()
            }
            binding.chunks.adapter = ChunkAdapter(chunks)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class ChunkAdapter(
    private val chunks: List<com.kriet.campusbrain.data.RetrievedChunk>,
) : RecyclerView.Adapter<ChunkAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
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
        holder.section.text = heading.orEmpty()
        holder.section.visibility =
            if (heading.isNullOrBlank() || heading == prev) View.GONE else View.VISIBLE
        holder.content.text = c.content.trim()
    }
}
