package com.kriet.campusbrain.ui.ask

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.kriet.campusbrain.R
import com.kriet.campusbrain.data.AnswerResult
import com.kriet.campusbrain.data.Route

sealed interface Message {
    data class User(val text: String) : Message
    data class Answer(
        val result: AnswerResult,
        var traceVisible: Boolean = false,
        var passagesVisible: Boolean = false,
    ) : Message
    data class Error(val text: String) : Message
}

class MessageAdapter(
    private val onSourceClick: (String) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Message>()

    fun addUser(text: String) { items.add(Message.User(text)); notifyItemInserted(items.size - 1) }
    fun addAnswer(r: AnswerResult) { items.add(Message.Answer(r)); notifyItemInserted(items.size - 1) }
    fun addError(t: String) { items.add(Message.Error(t)); notifyItemInserted(items.size - 1) }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is Message.User -> TYPE_USER
        else -> TYPE_ANSWER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            UserVH(inflater.inflate(R.layout.item_message_user, parent, false))
        } else {
            AnswerVH(inflater.inflate(R.layout.item_message_answer, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val m = items[position]) {
            is Message.User -> (holder as UserVH).bind(m)
            is Message.Answer -> (holder as AnswerVH).bind(m, position)
            is Message.Error -> (holder as AnswerVH).bindError(m)
        }
    }

    inner class UserVH(v: View) : RecyclerView.ViewHolder(v) {
        private val text: TextView = v.findViewById(R.id.text)
        fun bind(m: Message.User) { text.text = m.text }
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

        fun bindError(m: Message.Error) {
            badge.text = "ERROR"
            badge.setBackgroundColor(Color.parseColor("#C62828"))
            badge.setTextColor(Color.WHITE)
            answer.text = m.text
            sourcesScroll.visibility = View.GONE
            trace.visibility = View.GONE
            traceToggle.visibility = View.GONE
            passages.visibility = View.GONE
            passagesToggle.visibility = View.GONE
        }

        fun bind(m: Message.Answer, position: Int) {
            val r = m.result
            badge.text = r.route.name
            val colour = when (r.route) {
                Route.FACT -> R.color.route_fact
                Route.LOCAL -> R.color.route_local
                Route.GLOBAL -> R.color.route_global
                Route.TABULAR -> R.color.route_tabular
            }
            badge.setBackgroundColor(ContextCompat.getColor(badge.context, colour))
            badge.setTextColor(Color.WHITE)

            answer.text = stripMarkdown(r.answer)
            answer.alpha = if (r.abstained) 0.75f else 1f

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
                        .inflate(R.layout.item_source_chip, sources, false) as TextView
                    chip.text = s.section?.substringAfterLast(" > ") ?: s.docId
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

        /**
         * The composer emits a little markdown for structure. Rendering it
         * properly would mean pulling in a markdown library for two constructs,
         * so the markers are stripped instead.
         */
        fun stripMarkdown(s: String): String =
            s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("(?m)^---$"), "———")
    }
}
