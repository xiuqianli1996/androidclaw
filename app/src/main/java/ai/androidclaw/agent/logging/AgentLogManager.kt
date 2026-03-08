package ai.androidclaw.agent.logging

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class AgentLogManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val logDir = File(appContext.filesDir, "agent_logs").apply { mkdirs() }
    private val sessionStartMap = ConcurrentHashMap<Long, Long>()
    private val channelSessionStartMap = ConcurrentHashMap<String, Long>()
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val lineTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun resolveLogFileForConversation(conversationId: Long): File {
        val now = System.currentTimeMillis()
        val existing = sessionStartMap[conversationId]
        val start = if (existing == null || now - existing > ONE_HOUR_MS) {
            now.also { sessionStartMap[conversationId] = it }
        } else {
            existing
        }
        return File(logDir, "conversation_${conversationId}_${fileNameFormat.format(Date(start))}.log")
    }

    @Synchronized
    fun append(conversationId: Long, section: String, content: String) {
        val file = resolveLogFileForConversation(conversationId)
        val line = buildString {
            append("[")
            append(lineTimeFormat.format(Date()))
            append("] ")
            append(section)
            append('\n')
            append(content)
            append("\n\n")
        }
        file.appendText(line)
    }

    fun listLogFiles(): List<File> {
        return logDir.listFiles()?.filter { it.isFile && it.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun resolveLogFileForChannel(channelType: String, channelId: String): File {
        val key = "$channelType:$channelId"
        val now = System.currentTimeMillis()
        val existing = channelSessionStartMap[key]
        val start = if (existing == null || now - existing > ONE_HOUR_MS) {
            now.also { channelSessionStartMap[key] = it }
        } else {
            existing
        }
        val safeChannelId = channelId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(logDir, "channel_${channelType}_${safeChannelId}_${fileNameFormat.format(Date(start))}.log")
    }

    @Synchronized
    fun appendForChannel(channelType: String, channelId: String, section: String, content: String) {
        val file = resolveLogFileForChannel(channelType, channelId)
        val line = buildString {
            append("[")
            append(lineTimeFormat.format(Date()))
            append("] ")
            append(section)
            append('\n')
            append(content)
            append("\n\n")
        }
        file.appendText(line)
    }

    fun deleteLogFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return file.delete()
    }

    companion object {
        private const val ONE_HOUR_MS = 60 * 60 * 1000L

        @Volatile
        private var instance: AgentLogManager? = null

        fun getInstance(context: Context): AgentLogManager {
            return instance ?: synchronized(this) {
                instance ?: AgentLogManager(context).also { instance = it }
            }
        }
    }
}
