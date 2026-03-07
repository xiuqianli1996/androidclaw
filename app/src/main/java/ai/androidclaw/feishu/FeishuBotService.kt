package ai.androidclaw.feishu

import ai.androidclaw.agent.AgentManager
import ai.androidclaw.db.AppDatabase
import ai.androidclaw.db.entity.Conversation
import ai.androidclaw.db.entity.Message
import ai.androidclaw.db.entity.MessageRole
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.lark.oapi.service.im.v1.model.EventMessage
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FeishuBotService(context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val feishuConfigManager = FeishuConfigManager.getInstance(context)
    private val agentManager = AgentManager.getInstance(context)
    private val database = AppDatabase.getInstance(context)

    private var wsClient: FeishuWebSocketClient? = null
    private val messageHandler = FeishuMessageHandler()
    private val gson = Gson()
    @Volatile
    private var wsConnected = false

    companion object {
        private const val TAG = "FeishuBotService"

        @Volatile
        private var instance: FeishuBotService? = null

        fun getInstance(context: Context): FeishuBotService {
            return instance ?: synchronized(this) {
                instance ?: FeishuBotService(context.applicationContext).also {
                    instance = it
                    it.initialize()
                }
            }
        }
    }

    fun initialize() {
        if (feishuConfigManager.isConfigured()) {
            val config = feishuConfigManager.getConfig()
            if (config.enabled) {
                connectWebSocket(config)
            }
            Log.d(TAG, "Feishu bot initialized, enabled: ${config.enabled}")
        } else {
            Log.d(TAG, "Feishu bot not configured")
        }
    }

    fun isEnabled(): Boolean = feishuConfigManager.isEnabled()

    fun getConfig(): FeishuConfig = feishuConfigManager.getConfig()

    fun setEnabled(enabled: Boolean) {
        feishuConfigManager.setEnabled(enabled)
        if (enabled && feishuConfigManager.isConfigured()) {
            connectWebSocket(feishuConfigManager.getConfig())
        } else {
            disconnectWebSocket()
        }
    }

    fun saveConfig(config: FeishuConfig) {
        val wasEnabled = isEnabled()
        feishuConfigManager.saveConfig(config)

        if (config.enabled) {
            if (wasEnabled) {
                disconnectWebSocket()
            }
            connectWebSocket(config)
        } else {
            disconnectWebSocket()
        }
    }

    private fun connectWebSocket(config: FeishuConfig) {
        disconnectWebSocket()

        wsClient = FeishuWebSocketClient(config, object : FeishuWebSocketClient.FeishuMessageListener {
            override fun onConnected() {
                wsConnected = true
                Log.d(TAG, "WebSocket connected")
            }

            override fun onDisconnected() {
                wsConnected = false
                Log.d(TAG, "WebSocket disconnected")
            }

            override fun onMessage(event: P2MessageReceiveV1) {
                handleWsMessage(event)
            }

            override fun onError(error: String) {
                wsConnected = false
                Log.e(TAG, "WebSocket error: $error")
            }
        })

        wsClient?.connect()
    }

    private fun disconnectWebSocket() {
        wsClient?.release()
        wsClient = null
        wsConnected = false
    }

    fun ensureRunning() {
        if (!feishuConfigManager.isConfigured() || !feishuConfigManager.isEnabled()) {
            disconnectWebSocket()
            return
        }

        if (wsClient == null || !wsConnected) {
            connectWebSocket(feishuConfigManager.getConfig())
        }
    }

    fun isWsConnected(): Boolean = wsConnected

    private fun handleWsMessage(wsMessage: P2MessageReceiveV1) {
        val message = wsMessage.event?.message ?: return
        val chatId = message.chatId ?: return
        val messageId = message.messageId ?: return

        scope.launch {
            val textContent = extractTextContent(message)
            if (textContent.isNullOrBlank()) {
                Log.d(TAG, "Unsupported message type or empty content: ${message.messageType}")
                sendMessage(chatId, "暂不支持此类消息类型", messageId)
                return@launch
            }

            Log.d(TAG, "Received message: $textContent")
            val conversationId = getOrCreateConversation(chatId)

            val userMessage = Message(
                conversation_id = conversationId,
                role = MessageRole.USER.value,
                content = textContent,
                message_type = "text",
                metadata = """{"message_id":"$messageId","chat_id":"$chatId"}"""
            )
            database.messageDao().insert(userMessage)

            agentManager.execute(textContent) { response ->
                scope.launch {
                    val replyContent = if (response.error != null) {
                        "处理失败: ${response.error}"
                    } else {
                        response.message
                    }

                    val assistantMessage = Message(
                        conversation_id = conversationId,
                        role = MessageRole.ASSISTANT.value,
                        content = response.message,
                        message_type = "text",
                        tool_calls = if (response.toolCalls.isNotEmpty()) gson.toJson(response.toolCalls) else null
                    )
                    database.messageDao().insert(assistantMessage)

                    sendMessage(chatId, replyContent, messageId)
                }
            }
        }
    }

    private fun extractTextContent(message: EventMessage): String? {
        return try {
            when (message.messageType) {
                "text" -> {
                    val json = message.content?.let { JsonParser.parseString(it).asJsonObject }
                    json?.get("text")?.asString
                }

                "post" -> {
                    val json = message.content?.let { JsonParser.parseString(it).asJsonObject }
                    json?.get("content")?.asString
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text content", e)
            null
        }
    }

    private suspend fun sendMessage(chatId: String, content: String, replyToMessageId: String? = null) {
        val config = feishuConfigManager.getConfig()
        val client = FeishuClient(config)
        val messageContent = messageHandler.buildTextMessageContent(content)

        val result = if (replyToMessageId != null) {
            client.replyMessage(replyToMessageId, "text", messageContent)
        } else {
            client.sendMessage("chat_id", chatId, "text", messageContent)
        }

        result.fold(
            onSuccess = { Log.d(TAG, "Message sent successfully") },
            onFailure = { Log.e(TAG, "Failed to send message: ${it.message}") }
        )
    }

    private suspend fun getOrCreateConversation(chatId: String): Long {
        val existing = database.conversationDao().getConversationByChannel("feishu", chatId)
        if (existing != null) return existing.id

        val newConversation = Conversation(
            title = "Feishu Chat $chatId",
            channel_type = "feishu",
            channel_id = chatId
        )
        return database.conversationDao().insert(newConversation)
    }

    fun handleEvent(eventJson: String) {
        if (!isEnabled()) {
            Log.d(TAG, "Feishu bot is disabled")
            return
        }

        val event = messageHandler.parseEvent(eventJson) ?: run {
            Log.e(TAG, "Failed to parse event")
            return
        }

        event.challenge?.let { challenge ->
            Log.d(TAG, "URL verification challenge: $challenge")
            return
        }

        val eventContent = event.event ?: return
        if (eventContent.type != "message") return

        val message = eventContent.message ?: return
        val chatId = eventContent.chatId ?: return

        scope.launch {
            handleMessage(chatId, message)
        }
    }

    private suspend fun handleMessage(chatId: String, message: FeishuMessage) {
        val textContent = messageHandler.extractTextContent(message) ?: run {
            Log.d(TAG, "Unsupported message type: ${message.msgType}")
            sendMessage(chatId, "暂不支持此类消息类型", message.messageId)
            return
        }

        val messageId = message.messageId ?: return
        Log.d(TAG, "Received message: $textContent")

        val conversationId = getOrCreateConversation(chatId)
        val userMessage = Message(
            conversation_id = conversationId,
            role = MessageRole.USER.value,
            content = textContent,
            message_type = "text",
            metadata = """{"chat_id":"$chatId","message_id":"$messageId"}"""
        )
        database.messageDao().insert(userMessage)

        agentManager.execute(textContent) { response ->
            scope.launch {
                val replyContent = if (response.error != null) {
                    "处理失败: ${response.error}"
                } else {
                    response.message
                }

                val assistantMessage = Message(
                    conversation_id = conversationId,
                    role = MessageRole.ASSISTANT.value,
                    content = response.message,
                    message_type = "text",
                    tool_calls = if (response.toolCalls.isNotEmpty()) gson.toJson(response.toolCalls) else null
                )
                database.messageDao().insert(assistantMessage)

                sendMessage(chatId, replyContent, messageId)
            }
        }
    }

    fun release() {
        disconnectWebSocket()
    }
}
