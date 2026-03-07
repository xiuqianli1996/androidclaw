package ai.androidclaw.config

import android.content.Context
import android.content.SharedPreferences
import ai.androidclaw.agent.core.ClawModelCatalog
import ai.androidclaw.agent.core.ModelConfig
import ai.androidclaw.agent.core.ModelProvider

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
            modelName = prefs.getString(KEY_MODEL_NAME, ClawModelCatalog.defaultModelForProvider(provider))
                ?: ClawModelCatalog.defaultModelForProvider(provider),
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
        return ClawModelCatalog.defaultModelForProvider(getStoredProvider())
    }

    fun setProvider(provider: ModelProvider) {
        prefs.edit().putString(KEY_PROVIDER, provider.name).apply()
        prefs.edit().putString(KEY_MODEL_NAME, ClawModelCatalog.defaultModelForProvider(provider)).apply()
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
        return ClawModelCatalog.availableModels(getModelConfig().provider)
    }

    fun saveAgentConfig(systemPrompt: String, maxIterations: Int) {
        prefs.edit().apply {
            putString(KEY_AGENT_SYSTEM_PROMPT, systemPrompt)
            putInt(KEY_AGENT_MAX_ITERATIONS, maxIterations.coerceIn(1, 50))
            apply()
        }
    }

    fun getAgentSystemPrompt(): String {
        return prefs.getString(KEY_AGENT_SYSTEM_PROMPT, "") ?: ""
    }

    fun getAgentMaxIterations(): Int {
        return prefs.getInt(KEY_AGENT_MAX_ITERATIONS, 10).coerceIn(1, 50)
    }

    companion object {
        private const val PREFS_NAME = "android_claw_config"
        private const val KEY_PROVIDER = "model_provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_AGENT_SYSTEM_PROMPT = "agent_system_prompt"
        private const val KEY_AGENT_MAX_ITERATIONS = "agent_max_iterations"

        @Volatile
        private var instance: ConfigManager? = null

        fun getInstance(context: Context): ConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
