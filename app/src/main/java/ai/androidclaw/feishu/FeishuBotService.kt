package ai.androidclaw.feishu

import ai.androidclaw.channel.ChannelEngine
import ai.androidclaw.channel.ChannelIncomingMessage
import ai.androidclaw.channel.ChannelOutgoingMessage
import ai.androidclaw.channel.ChannelTransport
import ai.androidclaw.agent.logging.AgentLogManager
import android.content.Context
import android.util.Log
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
    private val channelEngine = ChannelEngine.getInstance(context)
    private val logManager = AgentLogManager.getInstance(context)

    private var wsClient: FeishuWebSocketClient? = null
    private val messageHandler = FeishuMessageHandler()
    @Volatile
    private var wsConnected = false
    @Volatile
    private var lastEventAt: Long = 0L
    @Volatile
    private var lastError: String = ""
    @Volatile
    private var lastMessageId: String = ""
    @Volatile
    private var reconnectCount: Int = 0
    private val processedMessageIds = LinkedHashMap<String, Long>()

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
        reconnectCount += 1

        wsClient = FeishuWebSocketClient(config, object : FeishuWebSocketClient.FeishuMessageListener {
            override fun onConnected() {
                wsConnected = true
                lastError = ""
                logChannel("feishu_ws", "WS", "connected")
                Log.d(TAG, "WebSocket connected")
            }

            override fun onDisconnected() {
                wsConnected = false
                logChannel("feishu_ws", "WS", "disconnected")
                Log.d(TAG, "WebSocket disconnected")
            }

            override fun onMessage(event: P2MessageReceiveV1) {
                handleWsMessage(event)
            }

            override fun onError(error: String) {
                wsConnected = false
                lastError = error
                logChannel("feishu_ws", "WS_ERROR", error)
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
        if (isDuplicateMessage(messageId)) {
            Log.d(TAG, "Duplicate message ignored: $messageId")
            return
        }
        lastEventAt = System.currentTimeMillis()
        lastMessageId = messageId

        scope.launch {
            val textContent = extractTextContent(message)
            if (textContent.isNullOrBlank()) {
                Log.d(TAG, "Unsupported message type or empty content: ${message.messageType}")
                sendMessage(chatId, "暂不支持此类消息类型", messageId)
                return@launch
            }

            routeIncomingToChannel(chatId, messageId, textContent)
        }
    }

    private fun routeIncomingToChannel(chatId: String, messageId: String, text: String) {
        logChannel(chatId, "INCOMING", "chat=$chatId messageId=$messageId text=$text")
        channelEngine.handleIncoming(
            incoming = ChannelIncomingMessage(
                channelType = "feishu",
                channelId = chatId,
                senderId = messageId,
                messageId = messageId,
                text = text,
                metadata = """{"chat_id":"$chatId","message_id":"$messageId"}"""
            ),
            transport = object : ChannelTransport {
                override val channelType: String = "feishu"

                override suspend fun send(message: ChannelOutgoingMessage): Result<String> {
                    logChannel(chatId, "OUTGOING", "chat=$chatId replyTo=${message.replyToMessageId} text=${message.text}")
                    sendMessage(chatId, message.text, message.replyToMessageId)
                    return Result.success(message.replyToMessageId ?: "")
                }
            }
        )
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
            onFailure = {
                lastError = it.message ?: "send failed"
                logChannel(chatId, "ERROR", "send failed: ${lastError}")
                Log.e(TAG, "Failed to send message: ${it.message}")
            }
        )
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
        if (isDuplicateMessage(messageId)) {
            Log.d(TAG, "Duplicate legacy message ignored: $messageId")
            return
        }
        lastEventAt = System.currentTimeMillis()
        lastMessageId = messageId
        Log.d(TAG, "Received message: $textContent")
        routeIncomingToChannel(chatId, messageId, textContent)
    }

    fun getHealthStatus(): String {
        val configured = feishuConfigManager.isConfigured()
        val enabled = feishuConfigManager.isEnabled()
        val lastEvent = if (lastEventAt > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(lastEventAt)) else "-"

        return buildString {
            appendLine("configured: $configured")
            appendLine("enabled: $enabled")
            appendLine("wsConnected: $wsConnected")
            appendLine("reconnectCount: $reconnectCount")
            appendLine("lastEventAt: $lastEvent")
            appendLine("lastMessageId: ${if (lastMessageId.isBlank()) "-" else lastMessageId}")
            appendLine("lastError: ${if (lastError.isBlank()) "-" else lastError}")
        }.trim()
    }

    private fun isDuplicateMessage(messageId: String): Boolean {
        synchronized(processedMessageIds) {
            val now = System.currentTimeMillis()
            val cutoff = now - 10 * 60 * 1000L
            val iterator = processedMessageIds.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value < cutoff) iterator.remove()
            }

            if (processedMessageIds.containsKey(messageId)) {
                logChannel("feishu_dedup", "DEDUP", "drop duplicate messageId=$messageId")
                return true
            }
            processedMessageIds[messageId] = now
            if (processedMessageIds.size > 500) {
                val first = processedMessageIds.entries.firstOrNull()?.key
                if (first != null) processedMessageIds.remove(first)
            }
            return false
        }
    }

    fun release() {
        disconnectWebSocket()
    }

    private fun logChannel(channelId: String, section: String, content: String) {
        logManager.appendForChannel("feishu", channelId.ifBlank { "unknown" }, section, content)
    }
}
