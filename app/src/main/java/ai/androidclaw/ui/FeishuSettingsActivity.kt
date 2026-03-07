package ai.androidclaw.ui

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import ai.androidclaw.databinding.ActivityFeishuSettingsBinding
import ai.androidclaw.feishu.FeishuBotService
import ai.androidclaw.feishu.FeishuConfig

class FeishuSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFeishuSettingsBinding
    private lateinit var botService: FeishuBotService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFeishuSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        botService = FeishuBotService.getInstance(this)

        setupToolbar()
        setupViews()
        loadConfig()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "飞书机器人配置"
    }

    private fun setupViews() {
        binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.cardConfig.isEnabled = isChecked
            binding.cardConfig.alpha = if (isChecked) 1.0f else 0.5f
        }

        binding.etAppId.addTextChangedListener {
            binding.tilAppId.error = null
        }

        binding.etAppSecret.addTextChangedListener {
            binding.tilAppSecret.error = null
        }

        binding.btnSave.setOnClickListener {
            if (validateInputs()) {
                saveConfig()
            }
        }

        binding.btnTest.setOnClickListener {
            testWebhook()
        }
    }

    private fun loadConfig() {
        val config = botService.getConfig()

        binding.switchEnabled.isChecked = config.enabled
        binding.etAppId.setText(config.appId)
        binding.etAppSecret.setText(config.appSecret)
        binding.etVerificationToken.setText(config.verificationToken)
        binding.etEncryptKey.setText(config.encryptKey)
        binding.etWebhookUrl.setText(config.webhookUrl)

        binding.cardConfig.isEnabled = config.enabled
        binding.cardConfig.alpha = if (config.enabled) 1.0f else 0.5f
    }

    private fun validateInputs(): Boolean {
        var isValid = true

        if (binding.etAppId.text.isNullOrBlank()) {
            binding.tilAppId.error = "App ID 不能为空"
            isValid = false
        }

        if (binding.etAppSecret.text.isNullOrBlank()) {
            binding.tilAppSecret.error = "App Secret 不能为空"
            isValid = false
        }

        return isValid
    }

    private fun saveConfig() {
        val config = FeishuConfig(
            enabled = binding.switchEnabled.isChecked,
            appId = binding.etAppId.text.toString().trim(),
            appSecret = binding.etAppSecret.text.toString().trim(),
            verificationToken = binding.etVerificationToken.text.toString().trim(),
            encryptKey = binding.etEncryptKey.text.toString().trim(),
            webhookUrl = binding.etWebhookUrl.text.toString().trim()
        )

        botService.saveConfig(config)
        botService.setEnabled(config.enabled)
        
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testWebhook() {
        if (binding.etWebhookUrl.text.isNullOrBlank()) {
            binding.tilWebhookUrl.error = "请输入Webhook地址"
            return
        }

        binding.btnTest.isEnabled = false
        binding.btnTest.text = "测试中..."

        // Simulate webhook test
        Toast.makeText(this, "请使用飞书开发者后台的在线调试功能测试", Toast.LENGTH_LONG).show()
        
        binding.btnTest.isEnabled = true
        binding.btnTest.text = "测试Webhook"
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
