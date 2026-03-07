package ai.androidclaw.agent.subagent

import ai.androidclaw.agent.tools.ClawToolExecutor
import ai.androidclaw.agent.tools.ToolCategory
import ai.androidclaw.agent.tools.ToolRegistry
import ai.androidclaw.agent.tools.ToolResult
import dev.langchain4j.agent.tool.JsonSchemaProperty
import dev.langchain4j.agent.tool.ToolSpecification
import kotlinx.coroutines.runBlocking

object SubAgentToolFactory {
    fun register(toolRegistry: ToolRegistry, subAgentManager: SubAgentManager) {
        toolRegistry.register(SubAgentCreateTool(subAgentManager))
        toolRegistry.register(SubAgentRunTool(subAgentManager))
        toolRegistry.register(SubAgentListTool(subAgentManager))
    }
}

private class SubAgentCreateTool(
    private val manager: SubAgentManager
) : ClawToolExecutor {
    override val name: String = "subagent_create"
    override val description: String = "创建一个可复用的子代理，便于后续分工执行"
    override val category: ToolCategory = ToolCategory.GENERAL

    override fun execute(parameters: Map<String, Any>): ToolResult {
        val name = parameters["name"]?.toString()?.trim().orEmpty()
        val description = parameters["description"]?.toString()?.trim().orEmpty()
        val systemPrompt = parameters["system_prompt"]?.toString()?.trim().orEmpty()
        val maxIterations = parameters["max_iterations"]?.toString()?.toIntOrNull() ?: 5

        if (name.isBlank() || systemPrompt.isBlank()) {
            return ToolResult.error("name 和 system_prompt 不能为空")
        }

        manager.registerSubAgent(
            SubAgentConfig(
                name = name,
                description = description.ifBlank { "动态创建子代理" },
                systemPrompt = systemPrompt,
                maxIterations = maxIterations.coerceIn(1, 20)
            )
        )
        return ToolResult.success("已创建子代理: $name")
    }

    override fun getToolSpecification(): ToolSpecification {
        return ToolSpecification.builder()
            .name(name)
            .description(description)
            .addParameter("name", JsonSchemaProperty.type("string"), JsonSchemaProperty.description("子代理名称"))
            .addOptionalParameter("description", JsonSchemaProperty.type("string"), JsonSchemaProperty.description("子代理描述"))
            .addParameter("system_prompt", JsonSchemaProperty.type("string"), JsonSchemaProperty.description("子代理系统提示词"))
            .addOptionalParameter("max_iterations", JsonSchemaProperty.type("integer"), JsonSchemaProperty.description("最大迭代次数，默认5"))
            .build()
    }
}

private class SubAgentRunTool(
    private val manager: SubAgentManager
) : ClawToolExecutor {
    override val name: String = "subagent_run"
    override val description: String = "调用子代理执行拆解后的子任务"
    override val category: ToolCategory = ToolCategory.GENERAL

    override fun execute(parameters: Map<String, Any>): ToolResult {
        val name = parameters["name"]?.toString()?.trim().orEmpty()
        val task = parameters["task"]?.toString()?.trim().orEmpty()
        if (name.isBlank() || task.isBlank()) {
            return ToolResult.error("name 和 task 不能为空")
        }

        val result = runBlocking { manager.executeSubAgent(name, task) }
        return if (result.success) {
            ToolResult.success("子代理执行完成(${result.subAgentName}): ${result.result}")
        } else {
            ToolResult.error("子代理执行失败(${result.subAgentName}): ${result.error}")
        }
    }

    override fun getToolSpecification(): ToolSpecification {
        return ToolSpecification.builder()
            .name(name)
            .description(description)
            .addParameter("name", JsonSchemaProperty.type("string"), JsonSchemaProperty.description("子代理名称"))
            .addParameter("task", JsonSchemaProperty.type("string"), JsonSchemaProperty.description("子任务描述"))
            .build()
    }
}

private class SubAgentListTool(
    private val manager: SubAgentManager
) : ClawToolExecutor {
    override val name: String = "subagent_list"
    override val description: String = "查看可用子代理列表"
    override val category: ToolCategory = ToolCategory.GENERAL

    override fun execute(parameters: Map<String, Any>): ToolResult {
        val list = manager.getAllSubAgents()
        if (list.isEmpty()) return ToolResult.success("无可用子代理")
        val text = list.joinToString("\n") { "- ${it.name}: ${it.description}" }
        return ToolResult.success(text)
    }

    override fun getToolSpecification(): ToolSpecification {
        return ToolSpecification.builder()
            .name(name)
            .description(description)
            .build()
    }
}
