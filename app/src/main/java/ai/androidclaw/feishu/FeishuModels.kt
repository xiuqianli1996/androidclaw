package ai.androidclaw.feishu

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class FeishuEvent(
    val type: String,           // event type: message, callback
    val schema: String,        // 2.0
    val token: String,         // verification token
    val timestamp: String,
    val signature: String,
    val challenge: String? = null, // URL verification
    val event: FeishuEventContent? = null
)

data class FeishuEventContent(
    val type: String,          // message
    val chatId: String? = null,
    val tenantKey: String? = null,
    val appId: String? = null,
    val message: FeishuMessage? = null
)

data class FeishuMessage(
    val messageId: String? = null,
    val rootId: String? = null,
    val parentId: String? = null,
    val createTime: String? = null,
    val updateTime: String? = null,
    val deleted: Boolean? = null,
    val msgType: String,       // text, image, file, audio, video, sticker, interactive, share_chat, share_user
    val content: String,       // JSON string
    val mentions: List<FeishuMention>? = null
)

data class FeishuMention(
    val id: FeishuMentionId? = null,
    val name: String? = null,
    val key: String? = null
)

data class FeishuMentionId(
    val unionId: String? = null,
    val userId: String? = null,
    val openId: String? = null
)

data class FeishuUser(
    val unionId: String? = null,
    val userId: String? = null,
    val openId: String? = null,
    val name: String? = null,
    val avatar: String? = null,
    val avatarThumb: String? = null,
    val avatarMedium: String? = null,
    val avatarBig: String? = null,
    val tenantKey: String? = null
)

data class FeishuMessageContent(
    val text: String? = null,
    val imageKey: String? = null,
    val fileId: String? = null,
    val post: FeishuPostContent? = null
)

data class FeishuPostContent(
    val zhCn: Map<String, Any>? = null,
    val enUs: Map<String, Any>? = null
)

object FeishuMessageParser {
    
    fun parseContent(msgType: String, content: String): FeishuMessageContent {
        return try {
            val json = JsonParser.parseString(content).asJsonObject
            when (msgType) {
                "text" -> FeishuMessageContent(
                    text = json.get("text")?.asString
                )
                "image" -> FeishuMessageContent(
                    imageKey = json.get("image_key")?.asString
                )
                "file" -> FeishuMessageContent(
                    fileId = json.get("file_id")?.asString
                )
                "post" -> FeishuMessageContent(
                    post = parsePostContent(json.get("post")?.asJsonObject)
                )
                else -> FeishuMessageContent()
            }
        } catch (e: Exception) {
            FeishuMessageContent()
        }
    }
    
    private fun parsePostContent(postJson: JsonObject?): FeishuPostContent? {
        if (postJson == null) return null
        return FeishuPostContent(
            zhCn = postJson.get("zh_cn")?.let { parsePostBody(it.asJsonObject) },
            enUs = postJson.get("en_us")?.let { parsePostBody(it.asJsonObject) }
        )
    }
    
    private fun parsePostBody(body: JsonObject): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        body.entrySet().forEach { (key, value) ->
            result[key] = value.toString()
        }
        return result
    }
}
