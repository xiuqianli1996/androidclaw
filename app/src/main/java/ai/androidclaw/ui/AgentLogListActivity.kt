package ai.androidclaw.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ai.androidclaw.agent.logging.AgentLogManager
import ai.androidclaw.databinding.ActivityAgentLogListBinding
import ai.androidclaw.ui.adapter.AgentLogFileAdapter

class AgentLogListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAgentLogListBinding
    private lateinit var logManager: AgentLogManager
    private lateinit var adapter: AgentLogFileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgentLogListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        logManager = AgentLogManager.getInstance(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Agent 日志"

        adapter = AgentLogFileAdapter(
            emptyList(),
            onClick = { file ->
                val intent = Intent(this, AgentLogDetailActivity::class.java)
                intent.putExtra("path", file.absolutePath)
                startActivity(intent)
            },
            onLongClick = { file ->
                confirmDelete(file)
            }
        )

        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = adapter

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val files = logManager.listLogFiles()
        adapter.submit(files)
        binding.tvEmpty.visibility = if (files.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun confirmDelete(file: java.io.File) {
        AlertDialog.Builder(this)
            .setTitle("删除日志")
            .setMessage("确认删除日志文件？\n${file.name}")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val ok = logManager.deleteLogFile(file)
                if (ok) {
                    Toast.makeText(this, "日志已删除", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            finish()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }
}
