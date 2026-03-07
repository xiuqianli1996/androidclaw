package ai.androidclaw.db.dao

import androidx.room.*
import ai.androidclaw.db.entity.Conversation
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: Conversation): Long

    @Update
    suspend fun update(conversation: Conversation)

    @Delete
    suspend fun delete(conversation: Conversation)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteById(conversationId: Long)

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    fun getAllConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE channel_type = :channelType ORDER BY updated_at DESC")
    fun getConversationsByChannel(channelType: String): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversationById(conversationId: Long): Conversation?

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    fun getConversationByIdFlow(conversationId: Long): Flow<Conversation?>

    @Query("SELECT * FROM conversations WHERE channel_type = :channelType AND channel_id = :channelId")
    suspend fun getConversationByChannel(channelType: String, channelId: String): Conversation?

    @Query("UPDATE conversations SET updated_at = :timestamp WHERE id = :conversationId")
    suspend fun updateTimestamp(conversationId: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET title = :title, updated_at = :timestamp WHERE id = :conversationId")
    suspend fun updateTitle(conversationId: Long, title: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM conversations WHERE channel_type = :channelType")
    suspend fun getConversationCount(channelType: String): Int
}
