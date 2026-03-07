package ai.androidclaw.config

import android.content.Context
import android.content.SharedPreferences

enum class ModelProvider(val displayName: String) {
    OPENAI("OpenAI"),
    GOOGLE_GEMINI("Google Gemini")
}

data class ModelConfig(
    val provider: ModelProvider = ModelProvider.OPENAI,
    val apiKey: String = "",
    val baseUrl: String = "",
    val modelName: String = "gpt-4o-mini",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096
)

class ConfigManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveModelConfig(config: ModelConfig) {
        prefs.edit().apply {
            putString(KEY_PROVIDER, config.provider.name)
            putString(KEY_API_KEY, config.apiKey)
            putString(KEY_BASE_URL, config.baseUrl)
            putString(KEY_MODEL_NAME, config.modelName)
            putFloat(KEY_TEMPERATURE, config.temperature)
            putInt(KEY_MAX_TOKENS, config.maxTokens)
            apply()
        }
    }

    fun getModelConfig(): ModelConfig {
        val provider = getStoredProvider()
        return ModelConfig(
            provider = provider,
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
            modelName = prefs.getString(KEY_MODEL_NAME, getDefaultModelForProvider(provider))
                ?: getDefaultModelForProvider(provider),
            temperature = prefs.getFloat(KEY_TEMPERATURE, 0.7f),
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, 4096)
        )
    }

    fun isConfigured(): Boolean {
        val config = getModelConfig()
        return config.apiKey.isNotBlank()
    }

    fun clearConfig() {
        prefs.edit().clear().apply()
    }

    fun getDefaultModelName(): String {
        return getDefaultModelForProvider(getStoredProvider())
    }

    fun setProvider(provider: ModelProvider) {
        prefs.edit().putString(KEY_PROVIDER, provider.name).apply()
        prefs.edit().putString(KEY_MODEL_NAME, getDefaultModelForProvider(provider)).apply()
    }

    private fun getDefaultModelForProvider(provider: ModelProvider): String {
        return when (provider) {
            ModelProvider.OPENAI -> "gpt-4o-mini"
            ModelProvider.GOOGLE_GEMINI -> "gemini-pro"
        }
    }

    private fun getStoredProvider(): ModelProvider {
        return try {
            ModelProvider.valueOf(
                prefs.getString(KEY_PROVIDER, ModelProvider.OPENAI.name)
                    ?: ModelProvider.OPENAI.name
            )
        } catch (e: Exception) {
            ModelProvider.OPENAI
        }
    }

    fun getAvailableModels(): List<String> {
        return when (getModelConfig().provider) {
            ModelProvider.OPENAI -> listOf(
                "gpt-4o",
                "gpt-4o-mini",
                "gpt-4-turbo",
                "gpt-3.5-turbo"
            )
            ModelProvider.GOOGLE_GEMINI -> listOf(
                "gemini-pro",
                "gemini-pro-vision",
                "gemini-1.5-pro",
                "gemini-1.5-flash"
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "android_claw_config"
        private const val KEY_PROVIDER = "model_provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_MAX_TOKENS = "max_tokens"

        @Volatile
        private var instance: ConfigManager? = null

        fun getInstance(context: Context): ConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
