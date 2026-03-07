package ai.androidclaw.feishu

import android.content.Context
import ai.androidclaw.config.ConfigManager

data class FeishuConfig(
    val enabled: Boolean = false,
    val appId: String = "",
    val appSecret: String = "",
    val verificationToken: String = "",
    val encryptKey: String = "",
    val webhookUrl: String = "" 
)

class FeishuConfigManager(context: Context) {
    
    private val prefs = context.getSharedPreferences("feishu_config", Context.MODE_PRIVATE)

    fun saveConfig(config: FeishuConfig) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, config.enabled)
            putString(KEY_APP_ID, config.appId)
            putString(KEY_APP_SECRET, config.appSecret)
            putString(KEY_VERIFICATION_TOKEN, config.verificationToken)
            putString(KEY_ENCRYPT_KEY, config.encryptKey)
            putString(KEY_WEBHOOK_URL, config.webhookUrl)
            apply()
        }
    }

    fun getConfig(): FeishuConfig {
        return FeishuConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            appId = prefs.getString(KEY_APP_ID, "") ?: "",
            appSecret = prefs.getString(KEY_APP_SECRET, "") ?: "",
            verificationToken = prefs.getString(KEY_VERIFICATION_TOKEN, "") ?: "",
            encryptKey = prefs.getString(KEY_ENCRYPT_KEY, "") ?: "",
            webhookUrl = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        )
    }

    fun isConfigured(): Boolean {
        val config = getConfig()
        return config.enabled && config.appId.isNotBlank() && config.appSecret.isNotBlank()
    }

    fun isEnabled(): Boolean = getConfig().enabled

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_APP_ID = "app_id"
        private const val KEY_APP_SECRET = "app_secret"
        private const val KEY_VERIFICATION_TOKEN = "verification_token"
        private const val KEY_ENCRYPT_KEY = "encrypt_key"
        private const val KEY_WEBHOOK_URL = "webhook_url"

        @Volatile
        private var instance: FeishuConfigManager? = null

        fun getInstance(context: Context): FeishuConfigManager {
            return instance ?: synchronized(this) {
                instance ?: FeishuConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
