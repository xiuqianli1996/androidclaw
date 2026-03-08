package ai.androidclaw.agent.core

import ai.androidclaw.agent.memory.AgentMemory
import ai.androidclaw.agent.memory.MemoryItem
import ai.androidclaw.agent.skills.SkillManager
import ai.androidclaw.agent.subagent.SubAgentManager
import ai.androidclaw.agent.subagent.SubAgentResult
import ai.androidclaw.agent.tools.ClawToolExecutor
import ai.androidclaw.agent.tools.ToolRegistry
import ai.androidclaw.agent.tools.ToolResult
import com.google.gson.JsonElement
import com.google.gson.JsonParser
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
import dev.langchain4j.model.output.TokenUsage
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

data class AgentRequest(
    val message: String,
    val imageDataUrl: String? = null,
    val systemPrompt: String = "",
    val maxIterations: Int = 10,
    val maxToolCallsPerIteration: Int = 2,
    val timeout: Long = 120000,
    val enableTools: Boolean = true,
    val enableSkills: Boolean = true,
    val enableSubAgents: Boolean = true,
    val traceLogger: ((AgentTraceEvent) -> Unit)? = null,
    val shouldPause: (() -> Boolean)? = null
)

enum class AgentTraceType {
    USER_MESSAGE,
    LLM_REQUEST,
    LLM_RESPONSE,
    TOOL_CALL,
    TOOL_RESULT,
    ERROR,
    FINAL
}

data class AgentTraceEvent(
    val type: AgentTraceType,
    val iteration: Int,
    val content: String
)

data class AgentResponse(
    val message: String,
    val toolCalls: List<ToolCallInfo>,
    val done: Boolean,
    val error: String? = null,
    val iterations: Int = 0,
    val elapsedMs: Long = 0,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val finishReason: String? = null
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
            "phone_get_ui_snapshot_compact",
            "phone_wait",
            "phone_wait_text"
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
        appendLine("你是 Android 手机执行代理。目标是在保证正确性的前提下，用最少步骤完成用户诉求。")
        appendLine()
        appendLine("## 核心原则")
        appendLine("1) 始终规划最短操作路径，避免无意义导航和重复操作。")
        appendLine("2) 每轮只执行当前必要动作，动作后基于观测继续决策。")
        appendLine("3) 未获得可验证结果前，不要提前结束任务。")
        appendLine()
        appendLine("## 观测与交互优先级（严格遵循）")
        appendLine("- 首选 `phone_get_ui_snapshot_compact` 做页面理解（低token）。")
        appendLine("- 优先使用 `phone_click_node` 和 `phone_input_node` 执行基于结构化节点的交互。")
        appendLine("- 仅在结构化快照无法覆盖/理解时，再使用截图。")
        appendLine("- 使用截图点击时优先 `phone_click_coordinates_from_screenshot`，避免压缩后坐标偏移。")
        appendLine("- `phone_get_screen_text` 与 `phone_click_text` 只作为最后兜底。")
        appendLine("- 页面切换、输入、点击后应使用 `phone_wait` 或 `phone_wait_text` 等待界面稳定。")
        appendLine()
        appendLine("## 执行流程")
        appendLine("- 先给出简要计划（2-5步，强调最短路径）。")
        appendLine("- 当前轮执行后，结合最新观测修正下一步。")
        appendLine("- 工具失败先进行一次等价重试；仍失败则给出明确阻塞点和下一步建议。")
        appendLine()
        appendLine("## 结束条件")
        appendLine("- 仅在用户目标已完成且有观测证据时结束。")
        appendLine("- 输出简洁：当前结果 + 是否完成 + 必要的后续建议。")
        appendLine()
        appendLine("## 可用工具")
        toolRegistry.getToolSpecifications().forEach { spec ->
            appendLine("- ${spec.name()}: ${spec.description()}")
        }
        appendLine()
        appendLine(skillManager.getSkillSummaryForPrompt())
        appendLine()
        appendLine(subAgentManager?.getSubAgentForPrompt() ?: "无可用子代理")
        appendLine()
        appendLine("## 记忆说明:")
        appendLine("- 可以参考历史摘要，但以当前观测为准")
        appendLine("- 可以使用技能或子代理完成复杂子任务")
        appendLine("- 记忆过多时系统会自动压缩")
    }

    suspend fun execute(request: AgentRequest): AgentResponse {
        val startedAt = System.currentTimeMillis()
        var currentIteration = 0
        val maxIterations = request.maxIterations
        val toolCalls = mutableListOf<ToolCallInfo>()
        var tokenUsage: TokenUsage? = null

        val messages = mutableListOf<ChatMessage>()
        
        val systemPrompt = buildSystemPrompt()
        if (request.systemPrompt.isNotEmpty()) {
            messages.add(SystemMessage(systemPrompt + "\n\n" + request.systemPrompt))
        } else {
            messages.add(SystemMessage(systemPrompt))
        }

        appendHistoricalContext(messages)
        messages.add(buildUserMessage(request))
        memory.addUserMessage(request.message)
        request.traceLogger?.invoke(AgentTraceEvent(AgentTraceType.USER_MESSAGE, 0, request.message))

        return try {
            withTimeout(request.timeout) {
                progressReporter("THINKING|开始分析任务")
                while (currentIteration < maxIterations) {
                    waitIfPaused(request, currentIteration)
                    currentIteration++
                    progressReporter("THINKING|第${currentIteration}轮决策")
                    
                    val chatMessages = messages.toMutableList()

                    val toolSpecs = if (request.enableTools) {
                        toolRegistry.getToolSpecifications()
                    } else emptyList()

                    val response: Response<AiMessage> = if (toolSpecs.isNotEmpty()) {
                        request.traceLogger?.invoke(
                            AgentTraceEvent(
                                AgentTraceType.LLM_REQUEST,
                                currentIteration,
                                renderLlmRequest(chatMessages, toolSpecs)
                            )
                        )
                        chatModel.generate(chatMessages, toolSpecs)
                    } else {
                        request.traceLogger?.invoke(
                            AgentTraceEvent(
                                AgentTraceType.LLM_REQUEST,
                                currentIteration,
                                renderLlmRequest(chatMessages, emptyList())
                            )
                        )
                        chatModel.generate(chatMessages)
                    }
                    tokenUsage = tokenUsage?.add(response.tokenUsage()) ?: response.tokenUsage()

                    val aiMessage = response.content()
                    val toolExecutions = aiMessage.toolExecutionRequests()
                    request.traceLogger?.invoke(
                        AgentTraceEvent(
                            AgentTraceType.LLM_RESPONSE,
                            currentIteration,
                            renderLlmResponse(aiMessage)
                        )
                    )

                    if (toolExecutions.isNullOrEmpty()) {
                        val answer = aiMessage.text().orEmpty()
                        val finalAnswer = answer.ifBlank { "任务已结束，但模型未返回文本结果。" }
                        memory.addAssistantMessage(finalAnswer)
                        progressReporter("DONE|任务执行完成")
                        request.traceLogger?.invoke(
                            AgentTraceEvent(AgentTraceType.FINAL, currentIteration, finalAnswer)
                        )
                        return@withTimeout AgentResponse(
                            message = finalAnswer,
                            toolCalls = toolCalls,
                            done = true,
                            iterations = currentIteration,
                            elapsedMs = System.currentTimeMillis() - startedAt,
                            inputTokens = tokenUsage?.inputTokenCount(),
                            outputTokens = tokenUsage?.outputTokenCount(),
                            totalTokens = tokenUsage?.totalTokenCount(),
                            finishReason = response.finishReason()?.name
                        )
                    }

                    val limitedExecutions = toolExecutions.take(request.maxToolCallsPerIteration.coerceIn(1, 5))
                    for (toolExecution in limitedExecutions) {
                        waitIfPaused(request, currentIteration)
                        val toolName = toolExecution.name()
                        val toolArgs = parseArguments(toolExecution.arguments())
                        progressReporter("TOOL|$toolName|${toolExecution.arguments()}")
                        request.traceLogger?.invoke(
                            AgentTraceEvent(
                                AgentTraceType.TOOL_CALL,
                                currentIteration,
                                "$toolName ${toolExecution.arguments()}"
                            )
                        )

                        val result = executeTool(toolName, toolArgs)
                        val observedResult = enrichPhoneToolResult(toolName, result)
                        request.traceLogger?.invoke(
                            AgentTraceEvent(
                                AgentTraceType.TOOL_RESULT,
                                currentIteration,
                                "$toolName => ${if (observedResult.success) "success" else "error"}: ${truncateText(observedResult.result.ifBlank { observedResult.error.orEmpty() }, 4000)}"
                            )
                        )
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

                    if (toolExecutions.size > limitedExecutions.size) {
                        messages.add(
                            SystemMessage(
                                "本轮模型请求了过多工具调用，系统仅执行了前${limitedExecutions.size}个。" +
                                    "请基于结果继续下一轮规划。"
                            )
                        )
                    }
                    continue
                }

                AgentResponse(
                    message = "达到最大迭代次数",
                    toolCalls = toolCalls,
                    done = false,
                    iterations = currentIteration,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    inputTokens = tokenUsage?.inputTokenCount(),
                    outputTokens = tokenUsage?.outputTokenCount(),
                    totalTokens = tokenUsage?.totalTokenCount()
                )
            }
        } catch (e: TimeoutCancellationException) {
            progressReporter("ERROR|任务超时")
            request.traceLogger?.invoke(
                AgentTraceEvent(AgentTraceType.ERROR, currentIteration, "任务超时: ${e.message}")
            )
            AgentResponse(
                message = "执行超时",
                toolCalls = toolCalls,
                done = false,
                error = "Timeout after ${request.timeout}ms",
                iterations = currentIteration,
                elapsedMs = System.currentTimeMillis() - startedAt,
                inputTokens = tokenUsage?.inputTokenCount(),
                outputTokens = tokenUsage?.outputTokenCount(),
                totalTokens = tokenUsage?.totalTokenCount()
            )
        } catch (e: Exception) {
            progressReporter("ERROR|执行异常: ${e.message ?: "unknown"}")
            request.traceLogger?.invoke(
                AgentTraceEvent(AgentTraceType.ERROR, currentIteration, "执行异常: ${e.message ?: "unknown"}")
            )
            AgentResponse(
                message = "执行错误",
                toolCalls = toolCalls,
                done = false,
                error = e.message,
                iterations = currentIteration,
                elapsedMs = System.currentTimeMillis() - startedAt,
                inputTokens = tokenUsage?.inputTokenCount(),
                outputTokens = tokenUsage?.outputTokenCount(),
                totalTokens = tokenUsage?.totalTokenCount()
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
            val obj = JsonParser.parseString(arguments).asJsonObject
            obj.entrySet().associate { (key, value) -> key to jsonToAny(value) }
        } catch (e: Exception) {
            emptyMap()
        }
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

        if (originalResult.success) {
            toolRegistry.execute("phone_wait", mapOf("milliseconds" to 700L))
        }

        val snapshot = toolRegistry.execute("phone_get_ui_snapshot_compact", mapOf("maxNodes" to 80))
        val observation = if (snapshot.success) {
            "\n[页面结构快照]\n${snapshot.result}"
        } else {
            val screenshot = toolRegistry.execute("phone_get_screenshot", emptyMap())
            if (screenshot.success) {
                "\n[页面截图]\n${screenshot.result}"
            } else {
                val screenText = toolRegistry.execute("phone_get_screen_text", emptyMap())
                if (screenText.success) {
                    "\n[页面文本]\n${screenText.result}"
                } else {
                    "\n[页面观测失败] snapshot=${snapshot.error}; screenshot=${screenshot.error}; text=${screenText.error}"
                }
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

    private fun appendHistoricalContext(messages: MutableList<ChatMessage>) {
        val history = memory.getRecentMessages(8)
        history.forEach {
            val message = when (it.role) {
                "user" -> UserMessage(it.content)
                "assistant" -> AiMessage(it.content)
                "system" -> SystemMessage(it.content)
                else -> UserMessage(it.content)
            }
            messages.add(message)
        }
    }

    private fun jsonToAny(element: JsonElement): Any {
        if (element.isJsonNull) return ""
        if (element.isJsonPrimitive) {
            val p = element.asJsonPrimitive
            return when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> {
                    val num = p.asString
                    if (num.contains('.')) num.toDoubleOrNull() ?: num else num.toLongOrNull() ?: num
                }
                else -> p.asString
            }
        }
        if (element.isJsonArray) {
            return element.asJsonArray.map { jsonToAny(it) }
        }
        return element.asJsonObject.entrySet().associate { (k, v) -> k to jsonToAny(v) }
    }

    private suspend fun waitIfPaused(request: AgentRequest, iteration: Int) {
        var reported = false
        while (request.shouldPause?.invoke() == true) {
            if (!reported) {
                progressReporter("THINKING|执行已暂停")
                request.traceLogger?.invoke(
                    AgentTraceEvent(AgentTraceType.FINAL, iteration, "Execution paused")
                )
                reported = true
            }
            delay(250)
        }
        if (reported) {
            progressReporter("THINKING|继续执行")
        }
    }

    private fun truncateText(text: String, max: Int): String {
        return if (text.length <= max) text else text.take(max) + "..."
    }

    private fun renderLlmRequest(messages: List<ChatMessage>, specs: List<ToolSpecification>): String {
        val tools = if (specs.isEmpty()) "(none)" else specs.joinToString(",") { it.name() }
        val msg = messages.joinToString("\n") { m ->
            val role = m.javaClass.simpleName
            "[$role] ${truncateText(m.toString(), 1200)}"
        }
        return "tools=$tools\n$msg"
    }

    private fun renderLlmResponse(aiMessage: AiMessage): String {
        val text = truncateText(aiMessage.text().orEmpty(), 2000)
        val tools = aiMessage.toolExecutionRequests()?.joinToString("\n") {
            "${it.name()} ${it.arguments()}"
        }.orEmpty()
        return if (tools.isBlank()) {
            "text=$text"
        } else {
            "text=$text\ntool_requests:\n$tools"
        }
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
