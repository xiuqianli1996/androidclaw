package ai.androidclaw.ui

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ai.androidclaw.config.ConfigManager
import ai.androidclaw.databinding.ActivityAgentSettingsBinding

class AgentSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAgentSettingsBinding
    private lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgentSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = ConfigManager.getInstance(this)

        setupToolbar()
        loadConfig()
        setupSaveAction()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Agent 配置"
    }

    private fun loadConfig() {
        binding.etSystemPrompt.setText(configManager.getAgentSystemPrompt())
        binding.etMaxIterations.setText(configManager.getAgentMaxIterations().toString())
    }

    private fun setupSaveAction() {
        binding.btnSave.setOnClickListener {
            val maxIterations = binding.etMaxIterations.text?.toString()?.trim()?.toIntOrNull()
            if (maxIterations == null || maxIterations < 1 || maxIterations > 50) {
                binding.tilMaxIterations.error = "maxIterations 必须在 1-50 之间"
                return@setOnClickListener
            }

            binding.tilMaxIterations.error = null
            val systemPrompt = binding.etSystemPrompt.text?.toString()?.trim().orEmpty()
            configManager.saveAgentConfig(systemPrompt, maxIterations)
            Toast.makeText(this, "Agent 配置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
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
