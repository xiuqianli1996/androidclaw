package ai.androidclaw.db.dao

import androidx.room.*
import ai.androidclaw.db.entity.Conversation
import ai.androidclaw.db.entity.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: Message): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<Message>)

    @Update
    suspend fun update(message: Message)

    @Delete
    suspend fun delete(message: Message)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: Long)

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    fun getMessagesByConversation(conversationId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(conversationId: Long, limit: Int): List<Message>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: Long): Message?

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND role = :role ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageByRole(conversationId: Long, role: String): Message?

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun getMessageCount(conversationId: Long): Int

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteAllByConversation(conversationId: Long)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: Long, status: String)

    @Query("UPDATE messages SET tool_results = :toolResults WHERE id = :messageId")
    suspend fun updateToolResults(messageId: Long, toolResults: String)

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND content LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchMessages(conversationId: Long, query: String): List<Message>
}
