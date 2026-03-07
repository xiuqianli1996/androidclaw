package ai.androidclaw.agent.core

import ai.androidclaw.agent.memory.AgentMemory
import ai.androidclaw.agent.skills.SkillManager
import ai.androidclaw.agent.subagent.SubAgentManager
import ai.androidclaw.agent.subagent.SubAgentResult
import ai.androidclaw.agent.tools.ClawToolExecutor
import ai.androidclaw.agent.tools.ToolRegistry
import ai.androidclaw.agent.tools.ToolResult
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.output.Response
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

data class AgentRequest(
    val message: String,
    val systemPrompt: String = "",
    val maxIterations: Int = 10,
    val timeout: Long = 120000,
    val enableTools: Boolean = true,
    val enableSkills: Boolean = true,
    val enableSubAgents: Boolean = true
)

data class AgentResponse(
    val message: String,
    val toolCalls: List<ToolCallInfo>,
    val done: Boolean,
    val error: String? = null,
    val iterations: Int = 0
)

data class ToolCallInfo(
    val name: String,
    val arguments: String,
    val result: String,
    val success: Boolean
)

class ClawAgent(
    private val chatModel: ChatLanguageModel,
    private val toolRegistry: ToolRegistry,
    private val memory: AgentMemory,
    private val skillManager: SkillManager,
    private var subAgentManager: SubAgentManager?
) {

    private fun buildSystemPrompt(): String = buildString {
        appendLine("你是一个Android手机自动化助手，可以控制手机屏幕、执行操作。")
        appendLine()
        appendLine("## 可用工具:")
        toolRegistry.getToolSpecifications().forEach { spec ->
            appendLine("- ${spec.name()}: ${spec.description()}")
        }
        appendLine()
        appendLine(skillManager.getSkillForPrompt())
        appendLine()
        appendLine(subAgentManager?.getSubAgentForPrompt() ?: "无可用子代理")
        appendLine()
        appendLine("## 记忆说明:")
        appendLine("- 你可以访问之前的对话历史")
        appendLine("- 必要时可以使用技能或子代理完成任务")
        appendLine("- 如果记忆过多，系统会自动压缩")
    }

    suspend fun execute(request: AgentRequest): AgentResponse {
        var currentIteration = 0
        val maxIterations = request.maxIterations
        val toolCalls = mutableListOf<ToolCallInfo>()

        val messages = mutableListOf<ChatMessage>()
        
        val systemPrompt = buildSystemPrompt()
        if (request.systemPrompt.isNotEmpty()) {
            messages.add(SystemMessage(systemPrompt + "\n\n" + request.systemPrompt))
        } else {
            messages.add(SystemMessage(systemPrompt))
        }

        messages.add(UserMessage(request.message))
        memory.addUserMessage(request.message)

        return try {
            withTimeout(request.timeout) {
                while (currentIteration < maxIterations) {
                    currentIteration++
                    
                    val chatMessages = messages.toMutableList()
                    chatMessages.addAll(memory.getRecentMessages(10).map { 
                        when (it.role) {
                            "user" -> UserMessage(it.content)
                            "assistant" -> AiMessage(it.content)
                            "system" -> SystemMessage(it.content)
                            else -> UserMessage(it.content)
                        }
                    })

                    val toolSpecs = if (request.enableTools) {
                        toolRegistry.getToolSpecifications()
                    } else emptyList()

                    val response: Response<AiMessage> = if (toolSpecs.isNotEmpty()) {
                        chatModel.generate(chatMessages, toolSpecs)
                    } else {
                        chatModel.generate(chatMessages)
                    }

                    val aiMessage = response.content()
                    val toolExecutions = aiMessage.toolExecutionRequests()

                    if (toolExecutions.isNullOrEmpty()) {
                        val answer = aiMessage.text().orEmpty()
                        memory.addAssistantMessage(answer)
                        return@withTimeout AgentResponse(
                            message = answer,
                            toolCalls = toolCalls,
                            done = true,
                            iterations = currentIteration
                        )
                    }

                    for (toolExecution in toolExecutions) {
                        val toolName = toolExecution.name()
                        val toolArgs = parseArguments(toolExecution.arguments())

                        val result = executeTool(toolName, toolArgs)
                        val toolCallInfo = ToolCallInfo(
                            name = toolName,
                            arguments = toolExecution.arguments(),
                            result = result.result,
                            success = result.success
                        )
                        toolCalls.add(toolCallInfo)
                        memory.addToolResult(toolName, result.result)

                        val toolResultMessage = if (result.success) {
                            result.result
                        } else {
                            "Error: ${result.error}"
                        }

                        messages.add(AiMessage.from(toolExecution))
                        messages.add(
                            ToolExecutionResultMessage(
                                toolExecution.id(),
                                toolName,
                                toolResultMessage
                            )
                        )
                    }

                    if (shouldContinue(aiMessage.text(), toolCalls)) {
                        continue
                    } else {
                        val answer = aiMessage.text().orEmpty()
                        memory.addAssistantMessage(answer)
                        return@withTimeout AgentResponse(
                            message = answer,
                            toolCalls = toolCalls,
                            done = true,
                            iterations = currentIteration
                        )
                    }
                }

                AgentResponse(
                    message = "达到最大迭代次数",
                    toolCalls = toolCalls,
                    done = false,
                    iterations = currentIteration
                )
            }
        } catch (e: TimeoutCancellationException) {
            AgentResponse(
                message = "执行超时",
                toolCalls = toolCalls,
                done = false,
                error = "Timeout after ${request.timeout}ms",
                iterations = currentIteration
            )
        } catch (e: Exception) {
            AgentResponse(
                message = "执行错误",
                toolCalls = toolCalls,
                done = false,
                error = e.message,
                iterations = currentIteration
            )
        }
    }

    private fun executeTool(name: String, arguments: Map<String, Any>): ToolResult {
        return try {
            toolRegistry.execute(name, arguments)
        } catch (e: Exception) {
            ToolResult.error("Tool execution failed: ${e.message}")
        }
    }

    private fun parseArguments(arguments: String?): Map<String, Any> {
        if (arguments.isNullOrBlank()) return emptyMap()
        
        return try {
            val cleanArgs = arguments
                .trim()
                .removePrefix("{")
                .removeSuffix("}")
            
            if (cleanArgs.isBlank()) return emptyMap()
            
            val result = mutableMapOf<String, Any>()
            val regex = """"([^"]+)"\s*:\s*"?([^",}]*)"?""".toRegex()
            
            regex.findAll(cleanArgs).forEach { match ->
                val key = match.groupValues[1].trim()
                val value = match.groupValues[2].trim()
                result[key] = value
            }
            
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun shouldContinue(lastResponse: String?, toolCalls: List<ToolCallInfo>): Boolean {
        if (lastResponse.isNullOrBlank()) return true
        val lowerResponse = lastResponse.lowercase()
        
        val completionIndicators = listOf(
            "完成了", "已经", "结束", "完成", "done", "completed",
            "已经完成", "操作完成", "任务完成"
        )
        
        return !completionIndicators.any { lowerResponse.contains(it) }
    }

    fun getAvailableTools(): List<String> = toolRegistry.getToolNames().toList()

    fun getMemorySize(): Int = memory.size()

    fun compactMemory(): Int = memory.compact()

    fun clearMemory() {
        memory.clear()
    }

    suspend fun executeSubAgent(name: String, task: String): SubAgentResult {
        val manager = subAgentManager
            ?: return SubAgentResult(
                subAgentName = name,
                success = false,
                result = "",
                error = "Sub-agent manager not initialized"
            )
        return manager.executeSubAgent(name, task)
    }

    fun setSubAgentManager(manager: SubAgentManager) {
        subAgentManager = manager
    }

    fun getSubAgentManager(): SubAgentManager? = subAgentManager
}

class ClawAgentBuilder {
    private var apiKey: String = ""
    private var modelName: String = "gpt-4o-mini"
    private var baseUrl: String? = null
    private var toolRegistry: ToolRegistry? = null
    private var memory: AgentMemory? = null
    private var skillManager: SkillManager? = null
    private var subAgentManager: SubAgentManager? = null

    fun apiKey(key: String) = apply { this.apiKey = key }
    fun modelName(name: String) = apply { this.modelName = name }
    fun baseUrl(url: String?) = apply { this.baseUrl = url }
    fun toolRegistry(registry: ToolRegistry) = apply { this.toolRegistry = registry }
    fun memory(memory: AgentMemory) = apply { this.memory = memory }
    fun skillManager(manager: SkillManager) = apply { this.skillManager = manager }
    fun subAgentManager(manager: SubAgentManager) = apply { this.subAgentManager = manager }

    fun build(): ClawAgent {
        val resolvedBaseUrl = baseUrl
        val model = if (resolvedBaseUrl != null) {
            OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(resolvedBaseUrl)
                .build()
        } else {
            OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .build()
        }

        val memoryInstance = memory ?: AgentMemory()
        val skillManagerInstance = skillManager ?: SkillManager()
        val toolRegistryInstance = requireNotNull(toolRegistry) { "toolRegistry must be set before build()" }

        val agent = ClawAgent(
            chatModel = model,
            toolRegistry = toolRegistryInstance,
            memory = memoryInstance,
            skillManager = skillManagerInstance,
            subAgentManager = null
        )

        val subAgentManagerInstance = subAgentManager ?: SubAgentManager(agent)
        subAgentManagerInstance.createDefaultSubAgents()
        agent.setSubAgentManager(subAgentManagerInstance)

        return agent
    }
}
