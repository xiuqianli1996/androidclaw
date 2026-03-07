package ai.androidclaw.agent.core

import ai.androidclaw.agent.memory.AgentMemory
import ai.androidclaw.agent.memory.MemoryItem
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
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.output.Response
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

data class AgentRequest(
    val message: String,
    val imageDataUrl: String? = null,
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
    private var subAgentManager: SubAgentManager?,
    private val progressReporter: (String) -> Unit = {}
) {

    companion object {
        private val PHONE_OBSERVATION_EXCLUDED_TOOLS = setOf(
            "phone_get_screenshot",
            "phone_get_screen_text",
            "phone_wait"
        )

        private const val MEMORY_COMPACT_SYSTEM_PROMPT =
            """
            你是记忆压缩助手。请将对话和工具执行历史压缩成可复用的短摘要。
            要求：
            1) 保留用户目标、关键约束、已完成动作、失败原因、待办事项。
            2) 删除重复和无关细节，输出简洁中文。
            3) 输出 6-10 条要点，每条一行，以 '- ' 开头。
            4) 不要编造信息；不确定时明确标注。
            """
    }

    private fun buildSystemPrompt(): String = buildString {
        appendLine("你是一个高效、可靠的Android手机自动化助手，目标是高成功率完成真实手机任务（如微信发消息）。")
        appendLine()
        appendLine("## 执行原则:")
        appendLine("- 严格采用 计划->执行->观察->再计划 的循环")
        appendLine("- 先输出简短计划（2-5步），每步执行后根据观测修正，不要盲点")
        appendLine("- 优先理解页面截图和页面文本，再决定下一步")
        appendLine("- 优先使用phone工具执行真实手机操作，避免冗余动作")
        appendLine("- 在页面跳转、输入后，优先使用 phone_wait / phone_wait_text 等待加载")
        appendLine("- 无法完成时明确说明原因并给出下一步")
        appendLine("- 涉及微信发送时，优先流程：打开微信->定位会话->输入文本->点击发送或触发输入法回车")
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

        messages.add(buildUserMessage(request))
        memory.addUserMessage(request.message)

        return try {
            withTimeout(request.timeout) {
                progressReporter("开始分析任务")
                while (currentIteration < maxIterations) {
                    currentIteration++
                    progressReporter("第${currentIteration}轮决策")
                    
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
                        progressReporter("执行工具: $toolName")

                        val result = executeTool(toolName, toolArgs)
                        val observedResult = enrichPhoneToolResult(toolName, result)
                        val toolCallInfo = ToolCallInfo(
                            name = toolName,
                            arguments = toolExecution.arguments(),
                            result = observedResult.result.ifEmpty { observedResult.error.orEmpty() },
                            success = observedResult.success
                        )
                        toolCalls.add(toolCallInfo)
                        memory.addToolResult(
                            toolName,
                            observedResult.result.ifEmpty { observedResult.error.orEmpty() }
                        )

                        val toolResultMessage = if (observedResult.success) {
                            observedResult.result
                        } else {
                            "Error: ${observedResult.error}"
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
                        progressReporter("任务执行完成")
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
            progressReporter("任务超时")
            AgentResponse(
                message = "执行超时",
                toolCalls = toolCalls,
                done = false,
                error = "Timeout after ${request.timeout}ms",
                iterations = currentIteration
            )
        } catch (e: Exception) {
            progressReporter("执行异常: ${e.message ?: "unknown"}")
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
        return toolCalls.isNotEmpty() && lastResponse.isNullOrBlank()
    }

    fun getAvailableTools(): List<String> = toolRegistry.getToolNames().toList()

    fun getMemorySize(): Int = memory.size()

    suspend fun compactMemory(): Int {
        if (!memory.shouldCompact()) return 0

        val items = memory.getAll()
        if (items.size < 10) return 0

        val toCompact = items.take(items.size / 2)
        val compactInput = buildCompactInput(toCompact)
        val summary = runCatching {
            withTimeout(15_000) {
                chatModel.generate(
                    listOf(
                        SystemMessage(MEMORY_COMPACT_SYSTEM_PROMPT),
                        UserMessage(compactInput)
                    )
                ).content().text().orEmpty().trim()
            }
        }.getOrDefault(defaultCompactSummary(toCompact))

        return memory.compactWithSummary(summary)
    }

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

    private fun buildUserMessage(request: AgentRequest): UserMessage {
        val imageDataUrl = request.imageDataUrl
        if (imageDataUrl.isNullOrBlank()) {
            return UserMessage(request.message)
        }

        return UserMessage.from(
            TextContent.from(request.message),
            ImageContent.from(imageDataUrl)
        )
    }

    private fun enrichPhoneToolResult(toolName: String, originalResult: ToolResult): ToolResult {
        if (!toolName.startsWith("phone_")) return originalResult
        if (toolName in PHONE_OBSERVATION_EXCLUDED_TOOLS) return originalResult

        val screenshot = toolRegistry.execute("phone_get_screenshot", emptyMap())
        val observation = if (screenshot.success) {
            "\n[页面截图]\n${screenshot.result}"
        } else {
            val screenText = toolRegistry.execute("phone_get_screen_text", emptyMap())
            if (screenText.success) {
                "\n[页面文本]\n${screenText.result}"
            } else {
                "\n[页面观测失败] screenshot=${screenshot.error}; text=${screenText.error}"
            }
        }

        return if (originalResult.success) {
            ToolResult.success(originalResult.result + observation)
        } else {
            ToolResult(
                success = false,
                result = originalResult.result + observation,
                error = originalResult.error
            )
        }
    }

    private fun buildCompactInput(items: List<MemoryItem>): String {
        return buildString {
            appendLine("请压缩以下历史内容：")
            items.forEachIndexed { index, item ->
                appendLine("${index + 1}. [${item.role}] ${item.content}")
            }
        }
    }

    private fun defaultCompactSummary(items: List<MemoryItem>): String {
        val userMessages = items.count { it.role == "user" }
        val assistantMessages = items.count { it.role == "assistant" }
        val toolMessages = items.count { it.role == "tool" }
        return "- 用户消息: $userMessages 条\n- 助手消息: $assistantMessages 条\n- 工具结果: $toolMessages 条"
    }
}

class ClawAgentBuilder {
    private var modelConfig: ModelConfig = ModelConfig()
    private var toolRegistry: ToolRegistry? = null
    private var memory: AgentMemory? = null
    private var skillManager: SkillManager? = null
    private var subAgentManager: SubAgentManager? = null
    private var progressReporter: (String) -> Unit = {}

    fun modelConfig(config: ModelConfig) = apply { this.modelConfig = config }
    fun provider(provider: ModelProvider) = apply { this.modelConfig = this.modelConfig.copy(provider = provider) }
    fun apiKey(key: String) = apply { this.modelConfig = this.modelConfig.copy(apiKey = key) }
    fun modelName(name: String) = apply { this.modelConfig = this.modelConfig.copy(modelName = name) }
    fun baseUrl(url: String?) = apply { this.modelConfig = this.modelConfig.copy(baseUrl = url ?: "") }
    fun temperature(value: Float) = apply { this.modelConfig = this.modelConfig.copy(temperature = value) }
    fun maxTokens(value: Int) = apply { this.modelConfig = this.modelConfig.copy(maxTokens = value) }
    fun toolRegistry(registry: ToolRegistry) = apply { this.toolRegistry = registry }
    fun memory(memory: AgentMemory) = apply { this.memory = memory }
    fun skillManager(manager: SkillManager) = apply { this.skillManager = manager }
    fun subAgentManager(manager: SubAgentManager) = apply { this.subAgentManager = manager }
    fun progressReporter(reporter: (String) -> Unit) = apply { this.progressReporter = reporter }

    fun build(): ClawAgent {
        val model = ClawModelFactory.createChatModel(modelConfig)

        val memoryInstance = memory ?: AgentMemory()
        val skillManagerInstance = skillManager ?: SkillManager()
        val toolRegistryInstance = requireNotNull(toolRegistry) { "toolRegistry must be set before build()" }

        val agent = ClawAgent(
            chatModel = model,
            toolRegistry = toolRegistryInstance,
            memory = memoryInstance,
            skillManager = skillManagerInstance,
            subAgentManager = null,
            progressReporter = progressReporter
        )

        val subAgentManagerInstance = subAgentManager ?: SubAgentManager(agent)
        subAgentManagerInstance.createDefaultSubAgents()
        agent.setSubAgentManager(subAgentManagerInstance)

        return agent
    }
}
