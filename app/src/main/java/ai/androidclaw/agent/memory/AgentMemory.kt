package ai.androidclaw.agent.memory

import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class MemoryItem(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: MemoryType = MemoryType.CONVERSATION,
    val metadata: Map<String, String> = emptyMap()
)

enum class MemoryType {
    CONVERSATION,
    TOOL_RESULT,
    SKILL_RESULT,
    SUBAGENT_RESULT,
    COMPACTED,
    SYSTEM
}

class AgentMemory(
    private val maxSize: Int = 100,
    private val storagePath: String = "/data/data/ai.androidclaw/files/memory"
) {
    private val memory = ConcurrentLinkedQueue<MemoryItem>()
    private val lock = ReentrantReadWriteLock()
    private val gson = Gson()

    private val compactedSummary = StringBuilder()
    private var totalCompacted = 0

    init {
        File(storagePath).mkdirs()
        loadFromDisk()
    }

    fun add(item: MemoryItem) {
        lock.write {
            memory.add(item)
            while (memory.size > maxSize) {
                memory.poll()
            }
        }
    }

    fun addUserMessage(content: String) {
        add(MemoryItem("user", content, type = MemoryType.CONVERSATION))
    }

    fun addAssistantMessage(content: String) {
        add(MemoryItem("assistant", content, type = MemoryType.CONVERSATION))
    }

    fun addToolResult(toolName: String, result: String) {
        add(MemoryItem(
            role = "tool",
            content = "[$toolName] $result",
            type = MemoryType.TOOL_RESULT,
            metadata = mapOf("tool" to toolName)
        ))
    }

    fun addSystemMessage(content: String) {
        add(MemoryItem("system", content, type = MemoryType.SYSTEM))
    }

    fun getRecentMessages(count: Int = 20): List<MemoryItem> {
        return lock.read {
            memory.toList().takeLast(count)
        }
    }

    fun getAll(): List<MemoryItem> {
        return lock.read {
            memory.toList()
        }
    }

    fun getForPrompt(): String {
        return lock.read {
            val items = memory.toList()
            if (items.isEmpty()) return@read ""
            
            buildString {
                appendLine("## 对话历史")
                items.forEach { item ->
                    when (item.role) {
                        "user" -> appendLine("User: ${item.content}")
                        "assistant" -> appendLine("Assistant: ${item.content}")
                        "tool" -> appendLine("Tool: ${item.content}")
                        "system" -> appendLine("System: ${item.content}")
                    }
                }
            }
        }
    }

    fun compact(): Int {
        return lock.write {
            if (memory.size < 10) return@write 0
            
            val items = memory.toList()
            val toCompact = items.take(items.size / 2)
            
            val summary = generateSummary(toCompact)
            compactedSummary.appendLine(summary)
            totalCompacted += toCompact.size
            
            memory.clear()
            memory.add(MemoryItem(
                role = "system",
                content = "[压缩记忆] 已压缩${totalCompacted}条记录。摘要: $summary",
                type = MemoryType.COMPACTED
            ))
            
            saveToDisk()
            toCompact.size
        }
    }

    fun compactWithSummary(summary: String): Int {
        return lock.write {
            if (memory.size < 10) return@write 0

            val items = memory.toList()
            val toCompact = items.take(items.size / 2)
            val normalizedSummary = summary.ifBlank { generateSummary(toCompact) }

            compactedSummary.appendLine(normalizedSummary)
            totalCompacted += toCompact.size

            memory.clear()
            memory.add(
                MemoryItem(
                    role = "system",
                    content = "[压缩记忆] 已压缩${totalCompacted}条记录。摘要: $normalizedSummary",
                    type = MemoryType.COMPACTED
                )
            )

            saveToDisk()
            toCompact.size
        }
    }

    private fun generateSummary(items: List<MemoryItem>): String {
        val toolCalls = items.count { it.type == MemoryType.TOOL_RESULT }
        val userMessages = items.count { it.role == "user" }
        val assistantMessages = items.count { it.role == "assistant" }
        
        return buildString {
            append("[${userMessages}条用户消息, ${assistantMessages}条助手回复, ${toolCalls}次工具调用]")
        }
    }

    fun getCompactedSummary(): String = lock.read { compactedSummary.toString() }

    fun clear() {
        lock.write {
            memory.clear()
            compactedSummary.clear()
            totalCompacted = 0
        }
    }

    fun size(): Int = memory.size

    fun saveToDisk() {
        try {
            val file = File(storagePath, "memory.json")
            val items = memory.toList()
            file.writeText(gson.toJson(items))
        } catch (e: Exception) {
            Log.e("AgentMemory", "Failed to save memory", e)
        }
    }

    private fun loadFromDisk() {
        try {
            val file = File(storagePath, "memory.json")
            if (file.exists()) {
                val json = file.readText()
                val items: List<MemoryItem> = gson.fromJson(
                    json,
                    object : com.google.gson.reflect.TypeToken<List<MemoryItem>>() {}.type
                )
                memory.addAll(items)
            }
        } catch (e: Exception) {
            Log.e("AgentMemory", "Failed to load memory", e)
        }
    }

    fun getMemoryStats(): Map<String, Any> = lock.read {
        mapOf(
            "total_items" to memory.size,
            "compacted_items" to totalCompacted,
            "user_messages" to memory.count { it.role == "user" },
            "assistant_messages" to memory.count { it.role == "assistant" },
            "tool_results" to memory.count { it.type == MemoryType.TOOL_RESULT }
        )
    }

    fun shouldCompact(): Boolean {
        return lock.read { memory.size >= maxSize * 0.9 }
    }

    fun getLastUserMessage(): String? {
        return lock.read {
            memory.toList().filter { it.role == "user" }.lastOrNull()?.content
        }
    }

    fun getLastAssistantMessage(): String? {
        return lock.read {
            memory.toList().filter { it.role == "assistant" }.lastOrNull()?.content
        }
    }

    fun search(query: String): List<MemoryItem> {
        return lock.read {
            memory.filter { it.content.contains(query, ignoreCase = true) }
        }
    }
}
