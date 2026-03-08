package ai.androidclaw.channel

import ai.androidclaw.agent.AgentManager
import ai.androidclaw.agent.core.AgentResponse
import ai.androidclaw.config.ConfigManager
import ai.androidclaw.db.AppDatabase
import ai.androidclaw.db.entity.Conversation
import ai.androidclaw.db.entity.Message
import ai.androidclaw.db.entity.MessageRole
import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class ChannelIncomingMessage(
    val channelType: String,
    val channelId: String,
    val senderId: String,
    val senderName: String? = null,
    val messageId: String,
    val text: String,
    val metadata: String? = null
)

data class ChannelOutgoingMessage(
    val channelType: String,
    val channelId: String,
    val replyToMessageId: String? = null,
    val text: String,
    val metadata: String? = null
)

interface ChannelTransport {
    val channelType: String
    suspend fun send(message: ChannelOutgoingMessage): Result<String>
}

class ChannelEngine private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val database = AppDatabase.getInstance(appContext)
    private val configManager = ConfigManager.getInstance(appContext)
    private val agentManager = AgentManager.getInstance(appContext)
    private val gson = Gson()

    fun handleIncoming(
        incoming: ChannelIncomingMessage,
        transport: ChannelTransport,
        onAfterResponse: ((AgentResponse) -> Unit)? = null
    ) {
        scope.launch {
            val conversationId = getOrCreateConversation(incoming.channelType, incoming.channelId)

            val userMessage = Message(
                conversation_id = conversationId,
                role = MessageRole.USER.value,
                content = incoming.text,
                message_type = "text",
                metadata = incoming.metadata
                    ?: """{"sender_id":"${incoming.senderId}","message_id":"${incoming.messageId}"}"""
            )
            database.messageDao().insert(userMessage)
            database.conversationDao().updateTimestamp(conversationId)

            agentManager.execute(
                incoming.text,
                systemPrompt = configManager.getAgentSystemPrompt(),
                maxIterations = configManager.getAgentMaxIterations()
            ) { response ->
                scope.launch {
                    persistAssistantMessage(conversationId, response)
                    val reply = if (response.error != null) {
                        "处理失败: ${response.error}"
                    } else {
                        response.message
                    }

                    transport.send(
                        ChannelOutgoingMessage(
                            channelType = incoming.channelType,
                            channelId = incoming.channelId,
                            replyToMessageId = incoming.messageId,
                            text = reply
                        )
                    )
                    onAfterResponse?.invoke(response)
                }
            }
        }
    }

    private suspend fun persistAssistantMessage(conversationId: Long, response: AgentResponse) {
        val assistantMessage = Message(
            conversation_id = conversationId,
            role = MessageRole.ASSISTANT.value,
            content = response.message,
            message_type = "text",
            metadata = gson.toJson(
                mapOf(
                    "elapsedMs" to response.elapsedMs,
                    "iterations" to response.iterations,
                    "inputTokens" to response.inputTokens,
                    "outputTokens" to response.outputTokens,
                    "totalTokens" to response.totalTokens,
                    "finishReason" to response.finishReason
                )
            ),
            tool_calls = if (response.toolCalls.isNotEmpty()) gson.toJson(response.toolCalls) else null
        )
        database.messageDao().insert(assistantMessage)
        database.conversationDao().updateTimestamp(conversationId)
    }

    private suspend fun getOrCreateConversation(channelType: String, channelId: String): Long {
        val existing = database.conversationDao().getConversationByChannel(channelType, channelId)
        if (existing != null) return existing.id

        val newConversation = Conversation(
            title = "$channelType:$channelId",
            channel_type = channelType,
            channel_id = channelId
        )
        return database.conversationDao().insert(newConversation)
    }

    companion object {
        @Volatile
        private var instance: ChannelEngine? = null

        fun getInstance(context: Context): ChannelEngine {
            return instance ?: synchronized(this) {
                instance ?: ChannelEngine(context).also { instance = it }
            }
        }
    }
}
