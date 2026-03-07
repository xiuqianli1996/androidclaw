package ai.androidclaw.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ai.androidclaw.databinding.ActivityMcpSettingsBinding
import ai.androidclaw.databinding.DialogMcpEditBinding
import ai.androidclaw.agent.AgentManager
import ai.androidclaw.mcp.McpConfigManager
import ai.androidclaw.mcp.McpServerConfig
import ai.androidclaw.mcp.McpTransport
import ai.androidclaw.ui.adapter.McpServerAdapter

class McpSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMcpSettingsBinding
    private lateinit var mcpConfigManager: McpConfigManager
    private lateinit var adapter: McpServerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMcpSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mcpConfigManager = McpConfigManager.getInstance(this)

        setupToolbar()
        setupList()
        setupAdd()
        loadData()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun setupList() {
        adapter = McpServerAdapter(
            emptyList(),
            onToggle = { server, enabled ->
                mcpConfigManager.setEnabled(server.id, enabled)
                AgentManager.getInstance(this).refreshMcpTools()
                Toast.makeText(this, if (enabled) "已启用" else "已停用", Toast.LENGTH_SHORT).show()
            },
            onEdit = { server -> showEditDialog(server) },
            onDelete = { server ->
                mcpConfigManager.delete(server.id)
                AgentManager.getInstance(this).refreshMcpTools()
                loadData()
            }
        )

        binding.rvMcp.layoutManager = LinearLayoutManager(this)
        binding.rvMcp.adapter = adapter
    }

    private fun setupAdd() {
        binding.fabAdd.setOnClickListener { showEditDialog(null) }
    }

    private fun loadData() {
        val items = mcpConfigManager.getAllServers()
        adapter.submitList(items)
        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showEditDialog(existing: McpServerConfig?) {
        val dialogBinding = DialogMcpEditBinding.inflate(layoutInflater)

        val transports = McpTransport.entries.map { it.displayName }
        val transportAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, transports)
        (dialogBinding.tilTransport.editText as? AutoCompleteTextView)?.setAdapter(transportAdapter)

        val target = existing ?: McpServerConfig(name = "")
        dialogBinding.etName.setText(target.name)
        dialogBinding.etCommand.setText(target.command)
        dialogBinding.etArgs.setText(target.args)
        dialogBinding.etEnvJson.setText(target.envJson)
        dialogBinding.etEndpoint.setText(target.endpoint)
        dialogBinding.etHeadersJson.setText(target.headersJson)
        dialogBinding.etTimeoutMs.setText(target.timeoutMs.toString())
        dialogBinding.etDescription.setText(target.description)
        (dialogBinding.tilTransport.editText as? AutoCompleteTextView)?.setText(target.transport.displayName, false)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新增 MCP" else "编辑 MCP")
            .setView(dialogBinding.root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.etName.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    dialogBinding.tilName.error = "名称不能为空"
                    return@setOnClickListener
                }
                dialogBinding.tilName.error = null

                val selectedTransport = (dialogBinding.tilTransport.editText as? AutoCompleteTextView)
                    ?.text?.toString()?.trim().orEmpty()
                val transport = McpTransport.entries.firstOrNull { it.displayName == selectedTransport }
                    ?: McpTransport.STDIO

                val timeoutMs = dialogBinding.etTimeoutMs.text?.toString()?.toIntOrNull() ?: 30000

                val config = target.copy(
                    name = name,
                    transport = transport,
                    command = dialogBinding.etCommand.text?.toString()?.trim().orEmpty(),
                    args = dialogBinding.etArgs.text?.toString()?.trim().orEmpty(),
                    envJson = dialogBinding.etEnvJson.text?.toString()?.trim().orEmpty(),
                    endpoint = dialogBinding.etEndpoint.text?.toString()?.trim().orEmpty(),
                    headersJson = dialogBinding.etHeadersJson.text?.toString()?.trim().orEmpty(),
                    timeoutMs = timeoutMs,
                    description = dialogBinding.etDescription.text?.toString()?.trim().orEmpty()
                )

                mcpConfigManager.upsert(config)
                AgentManager.getInstance(this).refreshMcpTools()
                loadData()
                dialog.dismiss()
            }
        }

        dialog.show()
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
