package ai.androidclaw.agent

import android.content.Context
import android.util.Log
import ai.androidclaw.agent.core.AgentRequest
import ai.androidclaw.agent.core.AgentResponse
import ai.androidclaw.agent.core.ClawAgent
import ai.androidclaw.agent.core.ClawAgentBuilder
import ai.androidclaw.agent.core.ModelConfig
import ai.androidclaw.agent.memory.AgentMemory
import ai.androidclaw.agent.skills.SkillManager
import ai.androidclaw.agent.skills.Skill
import ai.androidclaw.agent.subagent.SubAgentManager
import ai.androidclaw.agent.subagent.SubAgentConfig
import ai.androidclaw.agent.subagent.SubAgentResult
import ai.androidclaw.agent.subagent.SubAgentToolFactory
import ai.androidclaw.agent.tools.ToolFactory
import ai.androidclaw.agent.tools.ToolRegistry
import ai.androidclaw.mcp.McpConfigManager
import ai.androidclaw.mcp.McpStdioClient
import ai.androidclaw.mcp.McpToolExecutor
import ai.androidclaw.ui.overlay.ExecutionOverlayManager
import dev.langchain4j.agent.tool.ToolSpecification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var modelConfig: ModelConfig = ModelConfig()
    private val mcpToolNames = mutableSetOf<String>()
    private val overlayManager = ExecutionOverlayManager.getInstance(context)

    fun initialize(apiKey: String, modelName: String = "gpt-4o-mini", baseUrl: String? = null) {
        initialize(
            ModelConfig(
                apiKey = apiKey,
                modelName = modelName,
                baseUrl = baseUrl ?: ""
            )
        )
    }

    fun initialize(modelConfig: ModelConfig) {
        this.modelConfig = modelConfig
        this.apiKey = modelConfig.apiKey
        this.modelName = modelConfig.modelName
        this.baseUrl = modelConfig.baseUrl.takeIf { it.isNotBlank() }

        toolRegistry = ToolRegistry()
        memory = AgentMemory()
        skillManager = SkillManager()
        
        ToolFactory(toolRegistry).registerAllTools()
        registerMcpTools()
        
        agent = ClawAgentBuilder()
            .modelConfig(modelConfig)
            .toolRegistry(toolRegistry)
            .memory(memory)
            .skillManager(skillManager)
            .progressReporter { msg ->
                runCatching { overlayManager.update(msg) }
            }
            .build()

        subAgentManager = SubAgentManager(agent)
        subAgentManager.createDefaultSubAgents()
        SubAgentToolFactory.register(toolRegistry, subAgentManager)

        isInitialized = true
        Log.d(TAG, "AgentManager initialized with ${toolRegistry.size()} tools")
    }

    fun refreshMcpTools() {
        if (!isInitialized) return
        mcpToolNames.forEach { toolRegistry.unregister(it) }
        mcpToolNames.clear()
        registerMcpTools()
        Log.d(TAG, "MCP tools refreshed: ${mcpToolNames.size}")
    }

    private fun registerMcpTools() {
        val mcpConfigs = McpConfigManager.getInstance(context).getEnabledServers()
        mcpConfigs.forEach { server ->
            if (server.transport.name != "STDIO") {
                return@forEach
            }
            runCatching {
                val tools = McpStdioClient.listTools(server)
                tools.forEach { discovered ->
                    val executor = McpToolExecutor(
                        serverConfig = server,
                        mcpToolName = discovered.name,
                        mcpToolDescription = discovered.description,
                        inputSchema = discovered.inputSchema
                    )
                    toolRegistry.register(executor)
                    mcpToolNames.add(executor.name)
                }
            }.onFailure { e ->
                Log.w(TAG, "Failed to load MCP tools from ${server.name}: ${e.message}")
            }
        }
    }

    fun execute(
        message: String,
        imageDataUrl: String? = null,
        systemPrompt: String = "",
        maxIterations: Int = 10,
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
        currentJob = scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    overlayManager.show("任务已开始")
                }
                if (memory.shouldCompact()) {
                    val compacted = agent.compactMemory()
                    Log.d(TAG, "Memory compacted: $compacted items")
                }

                val request = AgentRequest(
                    message = message,
                    imageDataUrl = imageDataUrl,
                    systemPrompt = systemPrompt,
                    maxIterations = maxIterations
                )

                val response = agent.execute(request)
                withContext(Dispatchers.Main) {
                    callback(response)
                    overlayManager.hide()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Agent execution failed", e)
                withContext(Dispatchers.Main) {
                    callback(AgentResponse(
                        message = "执行失败",
                        toolCalls = emptyList(),
                        done = false,
                        error = e.message
                    ))
                    overlayManager.hide()
                }
            }
        }
    }

    suspend fun executeAsync(
        message: String,
        imageDataUrl: String? = null,
        systemPrompt: String = "",
        maxIterations: Int = 10
    ): AgentResponse {
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
                val compacted = agent.compactMemory()
                Log.d(TAG, "Memory compacted: $compacted items")
            }

            val request = AgentRequest(
                message = message,
                imageDataUrl = imageDataUrl,
                systemPrompt = systemPrompt,
                maxIterations = maxIterations
            )

            withContext(Dispatchers.IO) {
                agent.execute(request)
            }
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

    fun getToolSpecifications(): List<ToolSpecification> {
        if (!isInitialized) return emptyList()
        return toolRegistry.getToolSpecifications()
    }

    fun getMemorySize(): Int = memory.size()

    suspend fun compactMemory(): Int = agent.compactMemory()

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
