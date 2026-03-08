package ai.androidclaw.agent.skills

import ai.androidclaw.agent.tools.ClawToolExecutor
import ai.androidclaw.agent.tools.ToolCategory
import ai.androidclaw.agent.tools.ToolRegistry
import ai.androidclaw.agent.tools.ToolResult
import dev.langchain4j.agent.tool.JsonSchemaProperty
import dev.langchain4j.agent.tool.ToolSpecification

object SkillToolFactory {
    fun register(toolRegistry: ToolRegistry, skillManager: SkillManager) {
        toolRegistry.register(SkillListTool(skillManager))
        toolRegistry.register(SkillRunTool(skillManager, toolRegistry))
    }
}

private class SkillListTool(
    private val skillManager: SkillManager
) : ClawToolExecutor {
    override val name: String = "skill_list"
    override val description: String = "列出可用技能及用途"
    override val category: ToolCategory = ToolCategory.GENERAL

    override fun execute(parameters: Map<String, Any>): ToolResult {
        val skills = skillManager.getAllSkills()
        if (skills.isEmpty()) return ToolResult.success("无可用技能")
        return ToolResult.success(skills.joinToString("\n") { "- ${it.name}: ${it.description}" })
    }

    override fun getToolSpecification(): ToolSpecification {
        return ToolSpecification.builder()
            .name(name)
            .description(description)
            .build()
    }
}

private class SkillRunTool(
    private val skillManager: SkillManager,
    private val toolRegistry: ToolRegistry
) : ClawToolExecutor {
    override val name: String = "skill_run"
    override val description: String = "执行指定技能。对于 wechat_send_message 可高可靠完成微信发消息"
    override val category: ToolCategory = ToolCategory.GENERAL

    override fun execute(parameters: Map<String, Any>): ToolResult {
        val skillName = parameters["name"]?.toString()?.trim().orEmpty()
        if (skillName.isBlank()) return ToolResult.error("name 不能为空")

        if (skillName == "wechat_send_message") {
            val contact = parameters["contact"]?.toString()?.trim().orEmpty()
            val message = parameters["message"]?.toString()?.trim().orEmpty()
            if (contact.isBlank() || message.isBlank()) {
                return ToolResult.error("wechat_send_message 需要 contact 和 message")
            }
            return runWechatSendMessage(contact, message)
        }

        val skill = skillManager.getSkill(skillName)
            ?: return ToolResult.error("技能不存在: $skillName")

        val task = parameters["task"]?.toString()?.trim().orEmpty()
        val result = buildString {
            appendLine("技能: ${skill.name}")
            appendLine("说明: ${skill.instruction}")
            if (task.isNotBlank()) {
                appendLine("任务: $task")
            }
        }.trim()

        skillManager.recordExecution(skillName, parameters.mapValues { it.value.toString() }, result)
        return ToolResult.success(result)
    }

    private fun runWechatSendMessage(contact: String, message: String): ToolResult {
        val logs = mutableListOf<String>()

        fun call(name: String, params: Map<String, Any> = emptyMap()): ToolResult {
            val r = toolRegistry.execute(name, params)
            logs += "$name(${params.entries.joinToString()}) => ${if (r.success) "OK" else "ERR"}:${r.result.ifBlank { r.error.orEmpty() }}"
            return r
        }

        call("phone_open_app", mapOf("packageName" to "com.tencent.mm"))
        call("phone_wait", mapOf("milliseconds" to 1500L))

        var foundChat = call("phone_click_text", mapOf("text" to contact)).success
        if (!foundChat) {
            call("phone_click_text", mapOf("text" to "搜索"))
            call("phone_wait", mapOf("milliseconds" to 500L))
            call("phone_input_text", mapOf("text" to contact))
            call("phone_wait", mapOf("milliseconds" to 500L))
            foundChat = call("phone_click_text", mapOf("text" to contact)).success
        }

        if (!foundChat) {
            return ToolResult.error("未找到微信联系人: $contact\n${logs.joinToString("\n")}")
        }

        call("phone_wait", mapOf("milliseconds" to 800L))
        val inputOk = call("phone_input_text", mapOf("text" to message)).success
        if (!inputOk) {
            return ToolResult.error("消息输入失败\n${logs.joinToString("\n")}")
        }

        val sendByButton = call("phone_click_text", mapOf("text" to "发送")).success
        if (!sendByButton) {
            call("phone_ime_enter")
        }

        call("phone_wait", mapOf("milliseconds" to 600L))
        val confirmed = call("phone_wait_text", mapOf("text" to message, "timeoutMs" to 2000L)).success
        val summary = "微信发消息${if (confirmed) "成功" else "可能成功，请人工确认"}: $contact"
        skillManager.recordExecution(
            "wechat_send_message",
            mapOf("contact" to contact, "message" to message),
            summary
        )
        return ToolResult.success("$summary\n${logs.joinToString("\n")}")
    }

    override fun getToolSpecification(): ToolSpecification {
        return ToolSpecification.builder()
            .name(name)
            .description(description)
            .addParameter("name", JsonSchemaProperty.type("string"), JsonSchemaProperty.description("技能名称，例如 wechat_send_message"))
            .addOptionalParameter("task", JsonSchemaProperty.type("string"), JsonSchemaProperty.description("通用技能任务描述"))
            .addOptionalParameter("contact", JsonSchemaProperty.type("string"), JsonSchemaProperty.description("wechat_send_message 的联系人"))
            .addOptionalParameter("message", JsonSchemaProperty.type("string"), JsonSchemaProperty.description("wechat_send_message 的消息内容"))
            .build()
    }
}
