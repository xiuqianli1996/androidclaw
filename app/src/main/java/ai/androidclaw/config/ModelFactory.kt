package ai.androidclaw.config

import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel
import dev.langchain4j.model.openai.OpenAiChatModel

object ModelFactory {

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
