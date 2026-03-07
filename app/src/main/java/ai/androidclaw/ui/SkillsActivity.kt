package ai.androidclaw.ui

import ai.androidclaw.databinding.ActivitySkillsBinding
import ai.androidclaw.ui.adapter.SkillFileAdapter
import ai.androidclaw.ui.adapter.SkillFileItem
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SkillsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySkillsBinding
    private lateinit var adapter: SkillFileAdapter

    private lateinit var rootDir: File
    private lateinit var currentDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        rootDir = File(filesDir, "skills")
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
        currentDir = rootDir

        setupToolbar()
        setupList()
        setupActions()
        refreshList()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun setupList() {
        adapter = SkillFileAdapter(
            onClick = { item ->
                if (item.isParent) {
                    currentDir.parentFile?.let { parent -> currentDir = parent }
                    refreshList()
                    return@SkillFileAdapter
                }

                if (item.file.isDirectory) {
                    currentDir = item.file
                    refreshList()
                } else {
                    openFileEditor(item.file)
                }
            },
            onLongClick = { item ->
                confirmDelete(item.file)
            }
        )

        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.adapter = adapter
    }

    private fun setupActions() {
        binding.fabAdd.setOnClickListener { view ->
            showCreateMenu(view)
        }
    }

    private fun showCreateMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(Menu.NONE, 1, 1, "新建文件")
        popup.menu.add(Menu.NONE, 2, 2, "新建文件夹")
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> showCreateFileDialog()
                2 -> showCreateFolderDialog()
            }
            true
        }
        popup.show()
    }

    private fun showCreateFileDialog() {
        showNameInputDialog(
            title = "新建文件",
            hint = "例如 my_skill.json"
        ) { name ->
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    val file = File(currentDir, name)
                    if (file.exists()) return@withContext "文件已存在"

                    if (file.createNewFile()) {
                        if (name.endsWith(".json")) {
                            file.writeText(defaultSkillTemplate(name.removeSuffix(".json")))
                        }
                        null
                    } else {
                        "创建失败"
                    }
                }

                if (result == null) {
                    Toast.makeText(this@SkillsActivity, "文件已创建", Toast.LENGTH_SHORT).show()
                    refreshList()
                } else {
                    Toast.makeText(this@SkillsActivity, result, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCreateFolderDialog() {
        showNameInputDialog(
            title = "新建文件夹",
            hint = "文件夹名称"
        ) { name ->
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    val folder = File(currentDir, name)
                    if (folder.exists()) return@withContext "文件夹已存在"
                    if (folder.mkdirs()) null else "创建失败"
                }

                if (result == null) {
                    Toast.makeText(this@SkillsActivity, "文件夹已创建", Toast.LENGTH_SHORT).show()
                    refreshList()
                } else {
                    Toast.makeText(this@SkillsActivity, result, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showNameInputDialog(title: String, hint: String, onConfirm: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(48, 32, 48, 16)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || name.contains("/") || name.contains("\\") || name == "." || name == "..") {
                    Toast.makeText(this, "名称不合法", Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm(name)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openFileEditor(file: File) {
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching { file.readText() }.getOrElse { "" }
            }

            val input = EditText(this@SkillsActivity).apply {
                setText(content)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 8
                setPadding(36, 24, 36, 8)
            }

            MaterialAlertDialogBuilder(this@SkillsActivity)
                .setTitle(file.name)
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    val newContent = input.text?.toString().orEmpty()
                    lifecycleScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                file.writeText(newContent)
                                true
                            }.getOrDefault(false)
                        }
                        Toast.makeText(
                            this@SkillsActivity,
                            if (ok) "保存成功" else "保存失败",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (ok) refreshList()
                    }
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun confirmDelete(target: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除确认")
            .setMessage("确定删除 ${target.name} 吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        deleteRecursively(target)
                    }
                    Toast.makeText(
                        this@SkillsActivity,
                        if (ok) "已删除" else "删除失败",
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshList()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach {
                if (!deleteRecursively(it)) return false
            }
        }
        return file.delete()
    }

    private fun refreshList() {
        val items = mutableListOf<SkillFileItem>()
        if (currentDir != rootDir) {
            items.add(SkillFileItem(file = currentDir.parentFile ?: rootDir, isParent = true))
        }

        val children = currentDir.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
        items.addAll(children.map { SkillFileItem(it) })

        adapter.submitList(items)
        binding.tvPath.text = currentDir.absolutePath
        val isEmpty = children.isEmpty()
        binding.tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun defaultSkillTemplate(name: String): String {
        return """
            {
              "name": "$name",
              "description": "",
              "instruction": "",
              "parameters": [],
              "examples": []
            }
        """.trimIndent()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }
}
