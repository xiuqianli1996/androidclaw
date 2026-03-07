package ai.androidclaw.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ai.androidclaw.databinding.ItemMcpServerBinding
import ai.androidclaw.mcp.McpServerConfig

class McpServerAdapter(
    private var items: List<McpServerConfig>,
    private val onToggle: (McpServerConfig, Boolean) -> Unit,
    private val onEdit: (McpServerConfig) -> Unit,
    private val onDelete: (McpServerConfig) -> Unit
) : RecyclerView.Adapter<McpServerAdapter.McpViewHolder>() {

    fun submitList(newItems: List<McpServerConfig>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): McpViewHolder {
        val binding = ItemMcpServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return McpViewHolder(binding)
    }

    override fun onBindViewHolder(holder: McpViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class McpViewHolder(private val binding: ItemMcpServerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: McpServerConfig) {
            binding.tvName.text = item.name
            binding.tvTransport.text = "Transport: ${item.transport.displayName}"
            binding.tvTarget.text = when {
                item.transport.name == "STDIO" -> item.command
                else -> item.endpoint
            }

            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = item.enabled
            binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
                onToggle(item, checked)
            }

            binding.btnEdit.setOnClickListener { onEdit(item) }
            binding.btnDelete.setOnClickListener { onDelete(item) }
        }
    }
}
