package ai.androidclaw.ui

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import ai.androidclaw.R
import ai.androidclaw.agent.AgentManager
import ai.androidclaw.agent.core.ClawModelFactory
import ai.androidclaw.agent.core.ModelConfig
import ai.androidclaw.agent.core.ModelProvider
import ai.androidclaw.config.ConfigManager
import ai.androidclaw.databinding.ActivitySettingsBinding
import ai.androidclaw.feishu.FeishuBotService
import dev.langchain4j.data.message.UserMessage

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var configManager: ConfigManager
    private lateinit var feishuBotService: FeishuBotService

    private var currentProvider: ModelProvider = ModelProvider.OPENAI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = ConfigManager.getInstance(this)
        feishuBotService = FeishuBotService.getInstance(this)

        setupToolbar()
        setupProviderDropdown()
        setupInputs()
        loadConfig()
        setupSaveButton()
        setupFeishuCard()
    }

    private fun setupFeishuCard() {
        updateFeishuStatus()
        
        binding.cardFeishu.setOnClickListener {
            startActivity(Intent(this, FeishuSettingsActivity::class.java))
        }
    }

    private fun updateFeishuStatus() {
        val config = feishuBotService.getConfig()
        binding.tvFeishuStatus.text = if (config.enabled) {
            if (config.appId.isNotBlank()) "已启用" else "未配置"
        } else {
            "未启用"
        }
    }

    override fun onResume() {
        super.onResume()
        updateFeishuStatus()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "模型配置"
    }

    private fun setupProviderDropdown() {
        val providers = ModelProvider.entries.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, providers)
        (binding.tilProvider.editText as? AutoCompleteTextView)?.setAdapter(adapter)

        (binding.tilProvider.editText as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            currentProvider = ModelProvider.entries[position]
            configManager.setProvider(currentProvider)
            ensureModelDefaultIfEmpty()
            updateBaseUrlHint()
        }
    }

    private fun updateBaseUrlHint() {
        binding.tilBaseUrl.hint = when (currentProvider) {
            ModelProvider.OPENAI -> "自定义API地址 (可选)"
            ModelProvider.GOOGLE_GEMINI -> "Google API 不支持自定义地址"
        }
        binding.tilBaseUrl.isEnabled = currentProvider == ModelProvider.OPENAI
    }

    private fun setupInputs() {
        binding.etApiKey.addTextChangedListener {
            binding.tilApiKey.error = null
        }

        binding.etModel.addTextChangedListener {
            binding.tilModel.error = null
        }

        binding.etBaseUrl.addTextChangedListener {
            binding.tilBaseUrl.error = null
        }

        binding.etTemperature.addTextChangedListener {
            binding.tilTemperature.error = null
        }

        binding.etMaxTokens.addTextChangedListener {
            binding.tilMaxTokens.error = null
        }
    }

    private fun loadConfig() {
        val config = configManager.getModelConfig()
        currentProvider = config.provider

        (binding.tilProvider.editText as? AutoCompleteTextView)?.setText(
            config.provider.displayName, false
        )

        binding.etModel.setText(config.modelName)

        binding.etApiKey.setText(config.apiKey)
        binding.etBaseUrl.setText(config.baseUrl)
        binding.etTemperature.setText(config.temperature.toString())
        binding.etMaxTokens.setText(config.maxTokens.toString())

        updateBaseUrlHint()
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            if (validateInputs()) {
                saveConfig()
            }
        }

        binding.btnTest.setOnClickListener {
            testConnection()
        }
    }

    private fun validateInputs(): Boolean {
        var isValid = true

        if (binding.etApiKey.text.isNullOrBlank()) {
            binding.tilApiKey.error = "API Key 不能为空"
            isValid = false
        }

        val modelName = binding.etModel.text?.toString()?.trim().orEmpty()
        if (modelName.isBlank()) {
            binding.tilModel.error = "模型名称不能为空"
            isValid = false
        }

        val baseUrl = binding.etBaseUrl.text?.toString() ?: ""
        if (currentProvider == ModelProvider.OPENAI && baseUrl.isNotBlank()) {
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                binding.tilBaseUrl.error = "URL 必须以 http:// 或 https:// 开头"
                isValid = false
            }
        }

        val temperature = binding.etTemperature.text?.toString()?.toFloatOrNull()
        if (temperature == null || temperature < 0 || temperature > 2) {
            binding.tilTemperature.error = "Temperature 必须在 0-2 之间"
            isValid = false
        }

        val maxTokens = binding.etMaxTokens.text?.toString()?.toIntOrNull()
        if (maxTokens == null || maxTokens < 1) {
            binding.tilMaxTokens.error = "Max Tokens 必须大于 0"
            isValid = false
        }

        return isValid
    }

    private fun saveConfig() {
        val config = ModelConfig(
            provider = currentProvider,
            apiKey = binding.etApiKey.text.toString().trim(),
            baseUrl = binding.etBaseUrl.text?.toString()?.trim() ?: "",
            modelName = binding.etModel.text?.toString()?.trim().orEmpty(),
            temperature = binding.etTemperature.text.toString().toFloat(),
            maxTokens = binding.etMaxTokens.text.toString().toInt()
        )

        configManager.saveModelConfig(config)
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        
        AgentManager.getInstance(this).initialize(config)
        
        finish()
    }

    private fun testConnection() {
        if (binding.etApiKey.text.isNullOrBlank()) {
            binding.tilApiKey.error = "请先输入 API Key"
            return
        }

        val modelName = binding.etModel.text?.toString()?.trim().orEmpty()
        if (modelName.isBlank()) {
            binding.tilModel.error = "请先输入模型名称"
            return
        }

        setTestingState(true)

        try {
            val config = ModelConfig(
                provider = currentProvider,
                apiKey = binding.etApiKey.text.toString().trim(),
                baseUrl = binding.etBaseUrl.text?.toString()?.trim() ?: "",
                modelName = modelName,
                temperature = 0.7f,
                maxTokens = 100
            )

            val model = ClawModelFactory.createChatModel(config)
            
            Thread {
                try {
                    val response = model.generate(listOf(UserMessage("Hello"))).content().text()
                    runOnUiThread {
                        Toast.makeText(this, "连接成功! 响应: $response", Toast.LENGTH_LONG).show()
                        setTestingState(false)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        showErrorDialog(
                            title = "连接失败",
                            message = e.message ?: "未知错误",
                            detail = e.stackTraceToString()
                        )
                        setTestingState(false)
                    }
                }
            }.start()

        } catch (e: Exception) {
            showErrorDialog(
                title = "配置错误",
                message = e.message ?: "未知错误",
                detail = e.stackTraceToString()
            )
            setTestingState(false)
        }
    }

    private fun ensureModelDefaultIfEmpty() {
        if (binding.etModel.text.isNullOrBlank()) {
            binding.etModel.setText(configManager.getDefaultModelName())
        }
    }

    private fun setTestingState(testing: Boolean) {
        binding.btnTest.isEnabled = !testing
        binding.btnTest.text = if (testing) "测试中..." else "测试连接"
    }

    private fun showErrorDialog(title: String, message: String, detail: String) {
        val fullMessage = "${message.trim()}\n\n详细信息:\n$detail"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(fullMessage)
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制错误") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("error", fullMessage))
                Toast.makeText(this, "错误信息已复制", Toast.LENGTH_SHORT).show()
            }
            .show()
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
