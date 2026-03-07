package ai.androidclaw.feishu

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser

class FeishuMessageHandler {
    
    companion object {
        private const val TAG = "FeishuMessageHandler"
    }
    
    private val gson = Gson()
    
    fun parseEvent(jsonString: String): FeishuEvent? {
        return try {
            val json = JsonParser.parseString(jsonString).asJsonObject
            val type = json.get("type")?.asString ?: return null
            val schema = json.get("schema")?.asString ?: "2.0"
            val token = json.get("token")?.asString ?: ""
            val timestamp = json.get("timestamp")?.asString ?: ""
            val signature = json.get("signature")?.asString ?: ""
            
            val challenge = if (json.has("challenge")) {
                json.get("challenge")?.asString
            } else null
            
            val eventJson = json.get("event")
            val event = if (eventJson != null && eventJson.isJsonObject) {
                parseEventContent(eventJson.asJsonObject)
            } else null
            
            FeishuEvent(
                type = type,
                schema = schema,
                token = token,
                timestamp = timestamp,
                signature = signature,
                challenge = challenge,
                event = event
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing event", e)
            null
        }
    }
    
    private fun parseEventContent(eventJson: com.google.gson.JsonObject): FeishuEventContent? {
        val type = eventJson.get("type")?.asString ?: return null
        
        return FeishuEventContent(
            type = type,
            chatId = eventJson.get("chat_id")?.asString,
            tenantKey = eventJson.get("tenant_key")?.asString,
            appId = eventJson.get("app_id")?.asString,
            message = if (eventJson.has("message")) {
                parseMessage(eventJson.get("message").asJsonObject)
            } else null
        )
    }
    
    private fun parseMessage(messageJson: com.google.gson.JsonObject): FeishuMessage? {
        val msgType = messageJson.get("msg_type")?.asString ?: return null
        val content = messageJson.get("content")?.asString ?: return null
        
        return FeishuMessage(
            messageId = messageJson.get("message_id")?.asString,
            rootId = messageJson.get("root_id")?.asString,
            parentId = messageJson.get("parent_id")?.asString,
            createTime = messageJson.get("create_time")?.asString,
            updateTime = messageJson.get("update_time")?.asString,
            deleted = messageJson.get("deleted")?.asBoolean,
            msgType = msgType,
            content = content
        )
    }
    
    fun extractTextContent(message: FeishuMessage): String? {
        if (message.msgType == "text") {
            val content = FeishuMessageParser.parseContent(message.msgType, message.content)
            return content.text
        }
        
        if (message.msgType == "post") {
            val content = FeishuMessageParser.parseContent(message.msgType, message.content)
            return extractTextFromPost(content.post)
        }
        
        return null
    }
    
    private fun extractTextFromPost(post: FeishuPostContent?): String? {
        if (post == null) return null
        
        val body = post.zhCn ?: post.enUs ?: return null
        
        val title = body["title"] as? String
        val contentArray = body["content"] as? List<*>
        
        if (contentArray.isNullOrEmpty()) {
            return title
        }
        
        val textBuilder = StringBuilder()
        title?.let { textBuilder.append(it).append("\n") }
        
        for (item in contentArray) {
            if (item is List<*>) {
                for (element in item) {
                    if (element is Map<*, *>) {
                        val tag = element["tag"] as? String
                        when (tag) {
                            "text" -> textBuilder.append(element["content"]).append("\n")
                            "at" -> textBuilder.append("@").append(element["name"]).append(" ")
                        }
                    }
                }
            }
        }
        
        return textBuilder.toString().trim()
    }
    
    fun buildTextMessageContent(text: String): String {
        return gson.toJson(mapOf("text" to text))
    }
    
    fun buildRichTextMessageContent(title: String, content: String): String {
        return gson.toJson(mapOf(
            "zh_cn" to mapOf(
                "title" to title,
                "content" to listOf(listOf(
                    mapOf("tag" to "text", "content" to content)
                ))
            )
        ))
    }
    
    fun buildImageMessageContent(imageKey: String): String {
        return gson.toJson(mapOf("image_key" to imageKey))
    }
    
    fun getSenderId(message: FeishuMessage): String? {
        return message.messageId
    }
    
    fun getChatId(event: FeishuEventContent): String? {
        return event.chatId
    }
}
