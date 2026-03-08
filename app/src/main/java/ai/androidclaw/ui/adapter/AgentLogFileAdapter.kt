package ai.androidclaw.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ai.androidclaw.databinding.ItemAgentLogFileBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentLogFileAdapter(
    private var items: List<File>,
    private val onClick: (File) -> Unit,
    private val onLongClick: (File) -> Unit
) : RecyclerView.Adapter<AgentLogFileAdapter.Holder>() {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun submit(newItems: List<File>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAgentLogFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemAgentLogFileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: File) {
            binding.tvName.text = file.name
            binding.tvMeta.text = "${fmt.format(Date(file.lastModified()))} · ${file.length()} bytes"
            binding.root.setOnClickListener { onClick(file) }
            binding.root.setOnLongClickListener {
                onLongClick(file)
                true
            }
        }
    }
}
