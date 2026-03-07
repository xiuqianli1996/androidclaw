package ai.androidclaw.agent.subagent

import ai.androidclaw.agent.core.ClawAgent
import ai.androidclaw.agent.core.AgentRequest
import ai.androidclaw.agent.core.AgentResponse

data class SubAgentConfig(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val tools: List<String> = emptyList(),
    val maxIterations: Int = 5,
    val timeout: Long = 60000
)

data class SubAgentResult(
    val subAgentName: String,
    val success: Boolean,
    val result: String,
    val error: String? = null,
    val toolCalls: Int = 0,
    val executionTime: Long = 0
)

class SubAgentManager(private val agent: ClawAgent) {
    
    private val subAgents = mutableMapOf<String, SubAgentConfig>()
    private val runningAgents = mutableMapOf<String, Long>()

    fun registerSubAgent(config: SubAgentConfig) {
        subAgents[config.name] = config
    }

    fun unregisterSubAgent(name: String) {
        subAgents.remove(name)
    }

    fun getSubAgent(name: String): SubAgentConfig? = subAgents[name]

    fun getAllSubAgents(): List<SubAgentConfig> = subAgents.values.toList()

    fun getSubAgentNames(): Set<String> = subAgents.keys

    suspend fun executeSubAgent(
        name: String,
        task: String,
        context: Map<String, String> = emptyMap()
    ): SubAgentResult {
        val config = subAgents[name] ?: return SubAgentResult(
            subAgentName = name,
            success = false,
            result = "",
            error = "Sub-agent not found: $name"
        )

        val startTime = System.currentTimeMillis()
        runningAgents[name] = startTime

        return try {
            val systemPrompt = buildString {
                appendLine(config.systemPrompt)
                appendLine()
                appendLine("你是一个专门的子代理，负责完成特定任务。")
                appendLine("任务: $task")
                if (context.isNotEmpty()) {
                    appendLine("\n上下文信息:")
                    context.forEach { (key, value) ->
                        appendLine("- $key: $value")
                    }
                }
            }

            val request = AgentRequest(
                message = task,
                systemPrompt = systemPrompt,
                maxIterations = config.maxIterations,
                timeout = config.timeout
            )

            val response = agent.execute(request)
            
            runningAgents.remove(name)
            
            SubAgentResult(
                subAgentName = name,
                success = response.done,
                result = response.message,
                toolCalls = response.toolCalls.size,
                executionTime = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            runningAgents.remove(name)
            SubAgentResult(
                subAgentName = name,
                success = false,
                result = "",
                error = e.message,
                executionTime = System.currentTimeMillis() - startTime
            )
        }
    }

    fun isRunning(name: String): Boolean = runningAgents.containsKey(name)

    fun getRunningAgents(): Map<String, Long> = runningAgents.toMap()

    fun cancelSubAgent(name: String): Boolean {
        return runningAgents.remove(name) != null
    }

    fun getSubAgentForPrompt(): String {
        if (subAgents.isEmpty()) return "无可用子代理"
        
        return subAgents.values.joinToString("\n\n") { agent ->
            """
            |### SubAgent: ${agent.name}
            |Description: ${agent.description}
            |Available tools: ${agent.tools.joinToString(", ").ifEmpty { "默认工具" }}
            |Max iterations: ${agent.maxIterations}
            """.trimMargin()
        }
    }

    fun createDefaultSubAgents() {
        if (!subAgents.containsKey("explorer")) {
            registerSubAgent(SubAgentConfig(
                name = "explorer",
                description = "屏幕内容探索代理",
                systemPrompt = "你是一个屏幕内容探索代理，专门用于分析当前屏幕并找到用户需要的元素。",
                maxIterations = 3
            ))
        }

        if (!subAgents.containsKey("automation")) {
            registerSubAgent(SubAgentConfig(
                name = "automation",
                description = "自动化任务执行代理",
                systemPrompt = "你是一个自动化任务执行代理，负责按照用户描述的步骤执行操作。",
                maxIterations = 10
            ))
        }

        if (!subAgents.containsKey("debugger")) {
            registerSubAgent(SubAgentConfig(
                name = "debugger",
                description = "问题排查代理",
                systemPrompt = "你是一个问题排查代理，负责分析操作失败的原因并提供解决方案。",
                maxIterations = 5
            ))
        }
    }
}
