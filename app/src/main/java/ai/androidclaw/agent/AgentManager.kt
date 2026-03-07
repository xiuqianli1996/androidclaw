package ai.androidclaw.agent

import android.content.Context
import android.util.Log
import ai.androidclaw.agent.core.AgentRequest
import ai.androidclaw.agent.core.AgentResponse
import ai.androidclaw.agent.core.ClawAgent
import ai.androidclaw.agent.core.ClawAgentBuilder
import ai.androidclaw.agent.memory.AgentMemory
import ai.androidclaw.agent.skills.SkillManager
import ai.androidclaw.agent.skills.Skill
import ai.androidclaw.agent.subagent.SubAgentManager
import ai.androidclaw.agent.subagent.SubAgentConfig
import ai.androidclaw.agent.subagent.SubAgentResult
import ai.androidclaw.agent.tools.ToolFactory
import ai.androidclaw.agent.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AgentManager private constructor(
    private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var memory: AgentMemory
    private lateinit var skillManager: SkillManager
    private lateinit var subAgentManager: SubAgentManager
    private lateinit var agent: ClawAgent

    private var currentJob: Job? = null
    private var isInitialized = false

    private var apiKey: String = ""
    private var modelName: String = "gpt-4o-mini"
    private var baseUrl: String? = null

    fun initialize(apiKey: String, modelName: String = "gpt-4o-mini", baseUrl: String? = null) {
        this.apiKey = apiKey
        this.modelName = modelName
        this.baseUrl = baseUrl

        toolRegistry = ToolRegistry()
        memory = AgentMemory()
        skillManager = SkillManager()
        
        ToolFactory(toolRegistry).registerAllTools()
        
        agent = ClawAgentBuilder()
            .apiKey(apiKey)
            .modelName(modelName)
            .baseUrl(baseUrl)
            .toolRegistry(toolRegistry)
            .memory(memory)
            .skillManager(skillManager)
            .build()

        subAgentManager = SubAgentManager(agent)
        subAgentManager.createDefaultSubAgents()

        isInitialized = true
        Log.d(TAG, "AgentManager initialized with ${toolRegistry.size()} tools")
    }

    fun execute(
        message: String,
        systemPrompt: String = "",
        callback: (AgentResponse) -> Unit
    ) {
        if (!isInitialized) {
            callback(AgentResponse(
                message = "Agent未初始化",
                toolCalls = emptyList(),
                done = false,
                error = "请先调用initialize()方法"
            ))
            return
        }

        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                if (memory.shouldCompact()) {
                    val compacted = memory.compact()
                    Log.d(TAG, "Memory compacted: $compacted items")
                }

                val request = AgentRequest(
                    message = message,
                    systemPrompt = systemPrompt
                )

                val response = agent.execute(request)
                callback(response)
            } catch (e: Exception) {
                Log.e(TAG, "Agent execution failed", e)
                callback(AgentResponse(
                    message = "执行失败",
                    toolCalls = emptyList(),
                    done = false,
                    error = e.message
                ))
            }
        }
    }

    suspend fun executeAsync(message: String, systemPrompt: String = ""): AgentResponse {
        if (!isInitialized) {
            return AgentResponse(
                message = "Agent未初始化",
                toolCalls = emptyList(),
                done = false,
                error = "请先调用initialize()方法"
            )
        }

        return try {
            if (memory.shouldCompact()) {
                val compacted = memory.compact()
                Log.d(TAG, "Memory compacted: $compacted items")
            }

            val request = AgentRequest(
                message = message,
                systemPrompt = systemPrompt
            )

            agent.execute(request)
        } catch (e: Exception) {
            Log.e(TAG, "Agent execution failed", e)
            AgentResponse(
                message = "执行失败",
                toolCalls = emptyList(),
                done = false,
                error = e.message
            )
        }
    }

    fun registerSkill(skill: Skill) {
        skillManager.registerSkill(skill)
    }

    fun unregisterSkill(name: String) {
        skillManager.unregisterSkill(name)
    }

    fun getSkills(): List<Skill> = skillManager.getAllSkills()

    fun registerSubAgent(config: SubAgentConfig) {
        subAgentManager.registerSubAgent(config)
    }

    fun unregisterSubAgent(name: String) {
        subAgentManager.unregisterSubAgent(name)
    }

    fun getSubAgents(): List<SubAgentConfig> = subAgentManager.getAllSubAgents()

    suspend fun executeSubAgent(name: String, task: String): SubAgentResult {
        return subAgentManager.executeSubAgent(name, task)
    }

    fun getAvailableTools(): List<String> = toolRegistry.getToolNames().toList()

    fun getMemorySize(): Int = memory.size()

    fun compactMemory(): Int = memory.compact()

    fun clearMemory() = memory.clear()

    fun getMemoryStats(): Map<String, Any> = memory.getMemoryStats()

    fun getConversationHistory(): List<Map<String, Any>> {
        return memory.getRecentMessages().map { item ->
            mapOf(
                "role" to item.role,
                "content" to item.content,
                "timestamp" to item.timestamp
            )
        }
    }

    fun cancel() {
        currentJob?.cancel()
    }

    fun release() {
        scope.cancel()
        isInitialized = false
    }

    fun isReady(): Boolean = isInitialized

    companion object {
        private const val TAG = "AgentManager"
        
        @Volatile
        private var instance: AgentManager? = null

        fun getInstance(context: Context): AgentManager {
            return instance ?: synchronized(this) {
                instance ?: AgentManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
