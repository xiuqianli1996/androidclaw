package ai.androidclaw.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ai.androidclaw.databinding.ItemSkillFileBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SkillFileItem(
    val file: File,
    val isParent: Boolean = false
)

class SkillFileAdapter(
    private val onClick: (SkillFileItem) -> Unit,
    private val onLongClick: (SkillFileItem) -> Unit
) : RecyclerView.Adapter<SkillFileAdapter.ViewHolder>() {

    private val items = mutableListOf<SkillFileItem>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun submitList(list: List<SkillFileItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSkillFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemSkillFileBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SkillFileItem) {
            if (item.isParent) {
                binding.ivType.setImageResource(android.R.drawable.ic_menu_revert)
                binding.tvName.text = ".."
                binding.tvMeta.text = "返回上一级"
            } else {
                val file = item.file
                val isDir = file.isDirectory
                binding.ivType.setImageResource(
                    if (isDir) android.R.drawable.ic_menu_gallery else android.R.drawable.ic_menu_edit
                )
                binding.tvName.text = file.name
                binding.tvMeta.text = buildMeta(file, isDir)
            }

            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                if (!item.isParent) {
                    onLongClick(item)
                }
                true
            }
        }

        private fun buildMeta(file: File, isDir: Boolean): String {
            val timeText = dateFormat.format(Date(file.lastModified()))
            return if (isDir) {
                "文件夹 · $timeText"
            } else {
                "${formatSize(file.length())} · $timeText"
            }
        }

        private fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "${bytes}B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(Locale.getDefault(), "%.1fKB", kb)
            val mb = kb / 1024.0
            return String.format(Locale.getDefault(), "%.1fMB", mb)
        }
    }
}
