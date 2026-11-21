package com.arkueid.livetranslateclient

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** Flat message list mirroring the PC overlay: header line + translation line. */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.Holder>() {

    data class Message(
        val timestamp: String,
        val language: String,
        val original: String,
        val asrMs: Double,
        var translation: String = "",
        var tlMs: Double = 0.0,
        var streaming: Boolean = true,
        val isError: Boolean = false,
    )

    private val items = mutableListOf<Message>()

    fun add(message: Message): Message {
        items.add(message)
        if (items.size > MAX_ITEMS) {
            items.removeAt(0)
            notifyDataSetChanged()
        } else {
            notifyItemInserted(items.size - 1)
        }
        return message
    }

    fun indexOf(message: Message): Int =
        items.indexOfFirst { it === message }

    fun clear() {
        val size = items.size
        items.clear()
        if (size > 0) notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val originalView: TextView = itemView.findViewById(R.id.msg_original)
        private val translationView: TextView = itemView.findViewById(R.id.msg_translation)

        fun bind(message: Message) {
            val context = itemView.context
            val header = SpannableStringBuilder()
            if (message.timestamp.isNotEmpty()) {
                val start = header.length
                header.append("[${message.timestamp}] ")
                header.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(context, R.color.text_timestamp)),
                    start, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (message.language.isNotEmpty()) {
                val start = header.length
                header.append("[${message.language}] ")
                header.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(context, R.color.text_lang)),
                    start, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            val originalStart = header.length
            header.append(message.original)
            header.setSpan(
                ForegroundColorSpan(
                    ContextCompat.getColor(
                        context,
                        if (message.isError) R.color.text_error else R.color.text_original
                    )
                ),
                originalStart, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (!message.isError && message.asrMs > 0) {
                val start = header.length
                header.append(" ASR ${message.asrMs.toInt()}ms")
                header.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(context, R.color.text_asr)),
                    start, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                header.setSpan(
                    RelativeSizeSpan(0.82f),
                    start, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            originalView.text = header

            translationView.text = buildTranslation(context, message)
        }

        private fun buildTranslation(context: android.content.Context, message: Message): CharSequence {
            val builder = SpannableStringBuilder()
            val textColor = ContextCompat.getColor(
                context,
                when {
                    message.isError -> R.color.text_error
                    message.translation.isEmpty() && message.streaming -> R.color.text_dim
                    message.translation.isEmpty() -> R.color.text_dim
                    else -> R.color.text_translation
                }
            )
            val display = when {
                message.isError -> message.translation
                message.translation.isEmpty() && message.streaming ->
                    context.getString(R.string.overlay_translating)
                message.translation.isEmpty() ->
                    context.getString(R.string.overlay_same_language)
                else -> message.translation
            }
            builder.append("> $display")
            builder.setSpan(
                ForegroundColorSpan(textColor),
                0, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (message.streaming || message.isError) {
                builder.setSpan(
                    StyleSpan(Typeface.ITALIC),
                    0, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (!message.isError && !message.streaming && message.tlMs > 0) {
                val start = builder.length
                builder.append(" TL ${message.tlMs.toInt()}ms")
                builder.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(context, R.color.text_tl)),
                    start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.setSpan(
                    RelativeSizeSpan(0.82f),
                    start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            return builder
        }
    }

    private companion object {
        const val MAX_ITEMS = 50
    }
}
