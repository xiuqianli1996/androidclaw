package ai.androidclaw.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["channel_type"])]
)
data class Conversation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String = "",
    val channel_type: String = "agent",  // agent, wechat, telegram, etc.
    val channel_id: String? = null,      // external channel ID
    val created_at: Long = System.currentTimeMillis(),
    val updated_at: Long = System.currentTimeMillis(),
    val metadata: String? = null          // JSON metadata for extensibility
)

enum class ChannelType(val value: String) {
    AGENT("agent"),
    WECHAT("wechat"),
    TELEGRAM("telegram"),
    DISCORD("discord"),
    CUSTOM("custom")
}
