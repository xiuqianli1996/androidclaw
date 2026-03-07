package ai.androidclaw.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversation_id"]),
        Index(value = ["timestamp"]),
        Index(value = ["message_type"])
    ]
)
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversation_id: Long,
    val role: String,                    // user, assistant, system
    val content: String,                // text content
    val message_type: String = "text",  // text, image, file, voice, etc.
    val media_url: String? = null,     // for image/file/voice messages
    val media_mime_type: String? = null, // MIME type for media
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "sent",        // sending, sent, failed, read
    val metadata: String? = null,       // JSON metadata (extensible)
    val tool_calls: String? = null,    // JSON array of tool calls
    val tool_results: String? = null   // JSON array of tool results
)

enum class MessageType(val value: String) {
    TEXT("text"),
    IMAGE("image"),
    FILE("file"),
    VOICE("voice"),
    VIDEO("video"),
    SYSTEM("system")
}

enum class MessageRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    TOOL("tool")
}

enum class MessageStatus(val value: String) {
    SENDING("sending"),
    SENT("sent"),
    FAILED("failed"),
    READ("read")
}
