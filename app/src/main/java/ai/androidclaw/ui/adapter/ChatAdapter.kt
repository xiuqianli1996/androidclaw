package ai.androidclaw.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ai.androidclaw.R
import ai.androidclaw.databinding.ItemMessageAiBinding
import ai.androidclaw.databinding.ItemMessageUserBinding
import ai.androidclaw.db.entity.Message
import ai.androidclaw.db.entity.MessageRole
import coil.load
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private val onMessageLongClick: ((Message) -> Unit)? = null
) : ListAdapter<Message, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_AI = 2
        private const val VIEW_TYPE_IMAGE = 3
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        return when {
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
        }
    }

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
                binding.tvMessage.visibility = View.GONE
            } else {
                binding.ivImage.visibility = View.GONE
                binding.tvMessage.visibility = View.VISIBLE
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
            } else {
                binding.ivImage.visibility = View.GONE
            }
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
