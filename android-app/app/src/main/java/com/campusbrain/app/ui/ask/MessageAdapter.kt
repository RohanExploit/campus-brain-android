package com.campusbrain.app.ui.ask

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.campusbrain.app.R
import com.campusbrain.app.data.AnswerResult
import com.campusbrain.app.data.Route

sealed interface Message {
    data class User(val text: String) : Message
    data class Answer(
        val result: AnswerResult,
        var traceVisible: Boolean = false,
        var passagesVisible: Boolean = false,
    ) : Message
    /** A failure names what broke and what to do about it, never just "error". */
    data class Error(val title: String, val body: String) : Message
    /** Work in flight. Exactly one of these is ever in the list at a time. */
    data class Pending(val label: String) : Message
}

class MessageAdapter(
    private val onSourceClick: (String) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Message>()

    fun addUser(text: String) { items.add(Message.User(text)); notifyItemInserted(items.size - 1) }
    fun addAnswer(r: AnswerResult) { items.add(Message.Answer(r)); notifyItemInserted(items.size - 1) }
    fun addError(title: String, body: String) {
        items.add(Message.Error(title, body)); notifyItemInserted(items.size - 1)
    }

    /**
     * Answering takes one to three seconds of on-device retrieval, during
     * which the screen used to show nothing between the question and the
     * answer. The placeholder is a list row rather than an overlay so it
     * occupies the space the answer is about to fill and the list does not
     * jump when it is swapped out.
     */
    fun showPending(label: String) {
        if (items.lastOrNull() is Message.Pending) return
        items.add(Message.Pending(label)); notifyItemInserted(items.size - 1)
    }

    fun clearPending() {
        val i = items.indexOfLast { it is Message.Pending }
        if (i >= 0) { items.removeAt(i); notifyItemRemoved(i) }
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is Message.User -> TYPE_USER
        is Message.Error -> TYPE_ERROR
        is Message.Pending -> TYPE_PENDING
        is Message.Answer -> TYPE_ANSWER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserVH(inflater.inflate(R.layout.item_message_user, parent, false))
            TYPE_ERROR -> ErrorVH(inflater.inflate(R.layout.item_message_error, parent, false))
            // The same component the document import uses. One progress
            // treatment in the app, not one per screen that happens to wait.
            TYPE_PENDING -> PendingVH(inflater.inflate(R.layout.view_progress, parent, false))
            else -> AnswerVH(inflater.inflate(R.layout.item_message_answer, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val m = items[position]) {
            is Message.User -> (holder as UserVH).bind(m)
            is Message.Answer -> (holder as AnswerVH).bind(m, position)
            is Message.Error -> (holder as ErrorVH).bind(m)
            is Message.Pending -> (holder as PendingVH).bind(m)
        }
    }

    inner class UserVH(v: View) : RecyclerView.ViewHolder(v) {
        private val text: TextView = v.findViewById(R.id.text)
        fun bind(m: Message.User) { text.text = m.text }
    }

    inner class ErrorVH(v: View) : RecyclerView.ViewHolder(v) {
        private val title: TextView = v.findViewById(R.id.errorTitle)
        private val body: TextView = v.findViewById(R.id.errorBody)
        fun bind(m: Message.Error) { title.text = m.title; body.text = m.body }
    }

    inner class PendingVH(v: View) : RecyclerView.ViewHolder(v) {
        private val label: TextView = v.findViewById(R.id.progressLabel)
        private val bar: LinearProgressIndicator = v.findViewById(R.id.progressBar)
        fun bind(m: Message.Pending) {
            label.text = m.label
            // Answering cannot report how far through it is, so the bar says
            // "working" rather than inventing a fraction. The import path sets
            // this false and drives max/progress from onProgress.
            bar.isIndeterminate = true
        }
    }

    inner class AnswerVH(v: View) : RecyclerView.ViewHolder(v) {
        private val badge: TextView = v.findViewById(R.id.routeBadge)
        private val answer: TextView = v.findViewById(R.id.answer)
        private val sources: LinearLayout = v.findViewById(R.id.sources)
        private val sourcesScroll: View = v.findViewById(R.id.sourcesScroll)
        private val trace: TextView = v.findViewById(R.id.trace)
        private val traceToggle: TextView = v.findViewById(R.id.traceToggle)
        private val passages: TextView = v.findViewById(R.id.passages)
        private val passagesToggle: TextView = v.findViewById(R.id.passagesToggle)

        fun bind(m: Message.Answer, position: Int) {
            val r = m.result
            badge.text = r.route.name
            val colour = when (r.route) {
                Route.FACT -> R.color.route_fact
                Route.LOCAL -> R.color.route_local
                Route.GLOBAL -> R.color.route_global
                Route.TABULAR -> R.color.route_tabular
            }
            // Tint rather than setBackgroundColor: the latter swaps the shape
            // drawable for a flat ColorDrawable and squares off the corners.
            badge.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(badge.context, colour)
            )
            badge.setTextColor(ContextCompat.getColor(badge.context, R.color.on_accent))

            answer.text = stripMarkdown(r.answer)
            // An abstention steps down the text ramp rather than dimming the
            // view. alpha fades everything the view draws and lands on a grey
            // the palette never defines; it also has to be reset on every
            // rebind or a recycled holder keeps the last answer's opacity.
            answer.setTextColor(
                ContextCompat.getColor(
                    answer.context,
                    if (r.abstained) R.color.text_secondary else R.color.text_primary
                )
            )

            // The passage is where the lead sentence came from. Collapsed by
            // default: printing both inline showed the same sentence twice.
            if (r.passages.isEmpty()) {
                passagesToggle.visibility = View.GONE
                passages.visibility = View.GONE
            } else {
                passagesToggle.visibility = View.VISIBLE
                passagesToggle.text = if (m.passagesVisible) "Hide source text"
                    else "Show source text (${r.passages.size})"
                passages.visibility = if (m.passagesVisible) View.VISIBLE else View.GONE
                val nl = "\n"
                passages.text = r.passages.joinToString(nl + nl) { (h, b) -> h + nl + b }
                passagesToggle.setOnClickListener {
                    m.passagesVisible = !m.passagesVisible
                    notifyItemChanged(position)
                }
            }

            sources.removeAllViews()
            if (r.sources.isEmpty()) {
                sourcesScroll.visibility = View.GONE
            } else {
                sourcesScroll.visibility = View.VISIBLE
                r.sources.take(6).forEach { s ->
                    val chip = LayoutInflater.from(sources.context)
                        .inflate(R.layout.item_source_chip, sources, false)
                    chip.findViewById<TextView>(R.id.sourceTitle).text =
                        s.section?.substringAfterLast(" > ") ?: s.docId
                    // The whole provenance treatment, in one line. Bundled
                    // documents keep the caption GONE, so the college's corpus
                    // reads exactly as it did before import existed and the
                    // only citation that announces itself is the one the
                    // student is responsible for.
                    chip.findViewById<TextView>(R.id.sourceOrigin).visibility =
                        if (s.isUserAdded) View.VISIBLE else View.GONE
                    chip.setOnClickListener { onSourceClick(s.docId) }
                    sources.addView(chip)
                }
            }

            trace.text = r.trace.joinToString("\n") { (k, v) -> "$k = $v" }
            trace.visibility = if (m.traceVisible) View.VISIBLE else View.GONE
            traceToggle.visibility = if (r.trace.isEmpty()) View.GONE else View.VISIBLE
            traceToggle.setOnClickListener {
                m.traceVisible = !m.traceVisible
                notifyItemChanged(position)
            }
        }
    }

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ANSWER = 1
        private const val TYPE_ERROR = 2
        private const val TYPE_PENDING = 3

        /**
         * The composer emits a little markdown for structure. Rendering it
         * properly would mean pulling in a markdown library for two constructs,
         * so the markers are stripped instead.
         */
        fun stripMarkdown(s: String): String =
            s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("(?m)^---$"), "———")
    }
}
