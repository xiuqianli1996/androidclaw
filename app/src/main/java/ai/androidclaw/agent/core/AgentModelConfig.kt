package ai.androidclaw.agent.core

import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel
import dev.langchain4j.model.openai.OpenAiChatModel

enum class ModelProvider(val displayName: String) {
    OPENAI("OpenAI"),
    GOOGLE_GEMINI("Google Gemini")
}

data class ModelConfig(
    val provider: ModelProvider = ModelProvider.OPENAI,
    val apiKey: String = "",
    val baseUrl: String = "",
    val modelName: String = ClawModelCatalog.defaultModelForProvider(ModelProvider.OPENAI),
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096
)

object ClawModelCatalog {
    fun defaultModelForProvider(provider: ModelProvider): String {
        return when (provider) {
            ModelProvider.OPENAI -> "gpt-4o-mini"
            ModelProvider.GOOGLE_GEMINI -> "gemini-pro"
        }
    }

    fun availableModels(provider: ModelProvider): List<String> {
        return when (provider) {
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
}

object ClawModelFactory {
    fun createChatModel(config: ModelConfig): ChatLanguageModel {
        return when (config.provider) {
            ModelProvider.OPENAI -> createOpenAIModel(config)
            ModelProvider.GOOGLE_GEMINI -> createGeminiModel(config)
        }
    }

    private fun createOpenAIModel(config: ModelConfig): ChatLanguageModel {
        val builder = OpenAiChatModel.builder()
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .temperature(config.temperature.toDouble())
            .maxTokens(config.maxTokens)

        if (config.baseUrl.isNotBlank()) {
            builder.baseUrl(config.baseUrl)
        }

        return builder.build()
    }

    private fun createGeminiModel(config: ModelConfig): ChatLanguageModel {
        return GoogleAiGeminiChatModel.builder()
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .temperature(config.temperature.toDouble())
            .maxOutputTokens(config.maxTokens)
            .build()
    }
}
