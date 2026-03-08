package ai.androidclaw.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ai.androidclaw.R
import ai.androidclaw.databinding.ItemMessageAiBinding
import ai.androidclaw.databinding.ItemMessageTraceBinding
import ai.androidclaw.databinding.ItemMessageUserBinding
import ai.androidclaw.db.entity.Message
import ai.androidclaw.db.entity.MessageRole
import com.google.gson.JsonParser
import coil.load
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap

class ChatAdapter(
    private val onMessageLongClick: ((Message) -> Unit)? = null,
    private val onImageClick: ((Message) -> Unit)? = null
) : ListAdapter<Message, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_AI = 2
        private const val VIEW_TYPE_IMAGE = 3
        private const val VIEW_TYPE_TRACE = 4
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val metaExpanded = mutableSetOf<Long>()

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        return when {
            message.role == MessageRole.SYSTEM.value && message.content.startsWith("[") -> VIEW_TYPE_TRACE
            message.message_type == "image" -> VIEW_TYPE_IMAGE
            message.role == MessageRole.USER.value -> VIEW_TYPE_USER
            else -> VIEW_TYPE_AI
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_USER -> {
                val binding = ItemMessageUserBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                UserMessageViewHolder(binding)
            }
            VIEW_TYPE_IMAGE -> {
                val binding = ItemMessageUserBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                UserMessageViewHolder(binding)
            }
            VIEW_TYPE_TRACE -> {
                val binding = ItemMessageTraceBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                TraceMessageViewHolder(binding)
            }
            else -> {
                val binding = ItemMessageAiBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                AiMessageViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is UserMessageViewHolder -> holder.bind(message)
            is AiMessageViewHolder -> holder.bind(message)
            is TraceMessageViewHolder -> holder.bind(message)
        }
    }

    private val traceExpanded = mutableSetOf<Long>()

    inner class UserMessageViewHolder(
        private val binding: ItemMessageUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onMessageLongClick?.invoke(getItem(position))
                }
                true
            }
        }

        fun bind(message: Message) {
            binding.tvMessage.text = message.content
            binding.tvTime.text = timeFormat.format(Date(message.timestamp))

            if (message.message_type == "image" && !message.media_url.isNullOrEmpty()) {
                binding.ivImage.visibility = View.VISIBLE
                binding.ivImage.load(message.media_url) {
                    crossfade(true)
                    placeholder(R.drawable.ic_image_placeholder)
                }
                binding.ivImage.setOnClickListener { onImageClick?.invoke(message) }
                binding.tvMessage.visibility = View.GONE
            } else {
                binding.ivImage.visibility = View.GONE
                binding.tvMessage.visibility = View.VISIBLE
                binding.ivImage.setOnClickListener(null)
            }

            // Show status indicator
            when (message.status) {
                "sending" -> binding.tvStatus.visibility = View.VISIBLE
                "failed" -> {
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvStatus.text = "!"
                }
                else -> binding.tvStatus.visibility = View.GONE
            }
        }
    }

    inner class AiMessageViewHolder(
        private val binding: ItemMessageAiBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onMessageLongClick?.invoke(getItem(position))
                }
                true
            }
        }

        fun bind(message: Message) {
            binding.tvMessage.text = message.content
            binding.tvTime.text = timeFormat.format(Date(message.timestamp))

            // Show tool calls info if present
            if (!message.tool_calls.isNullOrEmpty()) {
                binding.tvToolCalls.visibility = View.VISIBLE
                binding.tvToolCalls.text = "[使用了工具]"
            } else {
                binding.tvToolCalls.visibility = View.GONE
            }

            // Show image if present
            if (message.message_type == "image" && !message.media_url.isNullOrEmpty()) {
                binding.ivImage.visibility = View.VISIBLE
                binding.ivImage.load(message.media_url) {
                    crossfade(true)
                    placeholder(R.drawable.ic_image_placeholder)
                }
                binding.ivImage.setOnClickListener { onImageClick?.invoke(message) }
            } else {
                binding.ivImage.visibility = View.GONE
                binding.ivImage.setOnClickListener(null)
            }

            bindMeta(message)
        }

        private fun bindMeta(message: Message) {
            if (message.metadata.isNullOrBlank()) {
                binding.tvMetaSummary.visibility = View.GONE
                binding.tvMetaDetail.visibility = View.GONE
                return
            }

            val expanded = metaExpanded.contains(message.id)
            val summary = "执行统计（点击${if (expanded) "收起" else "展开"}）"
            binding.tvMetaSummary.text = summary
            binding.tvMetaSummary.visibility = View.VISIBLE
            binding.tvMetaDetail.text = prettyMeta(message.metadata)
            binding.tvMetaDetail.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.tvMetaSummary.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                if (metaExpanded.contains(message.id)) {
                    metaExpanded.remove(message.id)
                } else {
                    metaExpanded.add(message.id)
                }
                notifyItemChanged(position)
            }
        }

        private fun prettyMeta(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return runCatching {
                val obj = JsonParser.parseString(raw).asJsonObject
                val elapsed = obj.get("elapsedMs")?.asLong ?: 0L
                val iterations = obj.get("iterations")?.asInt ?: 0
                val inTok = obj.get("inputTokens")?.asInt
                val outTok = obj.get("outputTokens")?.asInt
                val total = obj.get("totalTokens")?.asInt
                val reason = obj.get("finishReason")?.asString

                buildString {
                    appendLine("耗时: ${elapsed}ms")
                    appendLine("迭代: $iterations")
                    if (inTok != null || outTok != null || total != null) {
                        appendLine("Token: in=${inTok ?: "-"}, out=${outTok ?: "-"}, total=${total ?: "-"}")
                    }
                    if (!reason.isNullOrBlank()) appendLine("结束原因: $reason")
                    appendLine("原始: $raw")
                }.trim()
            }.getOrDefault(raw)
        }
    }

    inner class TraceMessageViewHolder(
        private val binding: ItemMessageTraceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                val item = getItem(position)
                val id = item.id
                if (traceExpanded.contains(id)) traceExpanded.remove(id) else traceExpanded.add(id)
                notifyItemChanged(position)
            }
        }

        fun bind(message: Message) {
            val content = message.content
            val formatted = formatTraceContent(content)
            val expanded = traceExpanded.contains(message.id)

            binding.tvTraceTitle.text = "执行过程${if (formatted.status.isNotBlank()) "（${formatted.status}）" else ""}"
            binding.tvTracePreview.text = formatted.preview
            binding.tvTraceFull.text = formatted.full
            binding.tvTraceFull.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.tvExpandHint.text = if (expanded) "收起" else "展开"
            binding.tvTime.text = timeFormat.format(Date(message.timestamp))
        }

        private fun formatTraceContent(raw: String): TraceFormatted {
            val lines = raw.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && it != "[执行过程]" }

            val status = lines.firstOrNull { it.startsWith("状态:") }
                ?.substringAfter("状态:")
                ?.trim()
                .orEmpty()

            val eventLines = lines.filterNot { it.startsWith("状态:") }

            val stepMap = TreeMap<Int, MutableList<Pair<String, String>>>()
            val regex = """^\[(决策|工具|结果)\s+(\d+)]\s*(.*)$""".toRegex()

            for (line in eventLines) {
                val match = regex.find(line) ?: continue
                val type = match.groupValues[1]
                val step = match.groupValues[2].toIntOrNull() ?: continue
                val content = match.groupValues[3]
                stepMap.getOrPut(step) { mutableListOf() }.add(type to content)
            }

            if (stepMap.isEmpty()) {
                val fallback = eventLines.joinToString("\n").ifBlank { raw }
                return TraceFormatted(
                    status = status,
                    preview = fallback,
                    full = fallback
                )
            }

            val preview = stepMap.entries.take(3).joinToString(" | ") { (step, events) ->
                val decision = events.count { it.first == "决策" }
                val tool = events.count { it.first == "工具" }
                val result = events.count { it.first == "结果" }
                "步骤$step: 决策$decision 工具$tool 结果$result"
            }

            val full = buildString {
                stepMap.forEach { (step, events) ->
                    appendLine("步骤 $step")
                    events.forEach { (type, text) ->
                        appendLine("- $type: $text")
                    }
                    appendLine()
                }
            }.trim()

            return TraceFormatted(status = status, preview = preview, full = full)
        }

    }

    class MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem == newItem
        }
    }
}

private data class TraceFormatted(
    val status: String = "",
    val preview: String,
    val full: String
)
