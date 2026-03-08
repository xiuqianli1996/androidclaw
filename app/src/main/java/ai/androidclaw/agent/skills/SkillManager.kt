package ai.androidclaw.agent.skills

import com.google.gson.JsonParser
import java.io.File

data class Skill(
    val name: String,
    val description: String,
    val instruction: String,
    val parameters: List<SkillParameter> = emptyList(),
    val examples: List<String> = emptyList()
)

data class SkillParameter(
    val name: String,
    val description: String,
    val required: Boolean = false,
    val default: String? = null
)

data class SkillExecution(
    val skillName: String,
    val parameters: Map<String, String>,
    val result: String,
    val timestamp: Long = System.currentTimeMillis()
)

private enum class SkillMarkdownSection {
    NONE,
    INSTRUCTION,
    PARAMETERS,
    EXAMPLES
}

class SkillManager(private val skillsDir: String = "/data/data/ai.androidclaw/files/skills") {

    private val skills = mutableMapOf<String, Skill>()
    private val executionHistory = mutableListOf<SkillExecution>()
    private var loaded = false
    private var isLoading = false

    init {
        File(skillsDir).mkdirs()
    }

    fun registerSkill(skill: Skill) {
        ensureLoaded()
        skills[skill.name] = skill
        saveSkill(skill)
    }

    fun unregisterSkill(name: String) {
        ensureLoaded()
        skills.remove(name)
        File(skillsDir, "$name.md").delete()
        File(skillsDir, "$name.json").delete()
    }

    fun getSkill(name: String): Skill? {
        ensureLoaded()
        return skills[name]
    }

    fun getAllSkills(): List<Skill> {
        ensureLoaded()
        return skills.values.sortedBy { it.name }
    }

    fun getSkillNames(): Set<String> {
        ensureLoaded()
        return skills.keys
    }

    fun getSkillForPrompt(): String {
        ensureLoaded()
        if (skills.isEmpty()) return "无可用技能"
        
        return skills.values.joinToString("\n\n") { skill ->
            """
            |### Skill: ${skill.name}
            |Description: ${skill.description}
            |Instruction: ${skill.instruction}
            |Parameters: ${skill.parameters.joinToString(", ") { "${it.name}${if (it.required) "*" else ""}" }}
            |Examples: ${skill.examples.joinToString("; ")}
            """.trimMargin()
        }
    }

    fun getToolsForSkill(skillName: String): List<String> {
        ensureLoaded()
        return skills[skillName]?.parameters?.map { it.name } ?: emptyList()
    }

    fun getSkillSummaryForPrompt(maxItems: Int = 6): String {
        ensureLoaded()
        if (skills.isEmpty()) return "无可用技能"
        return skills.values.take(maxItems).joinToString("; ") { "${it.name}: ${it.description}" }
    }

    fun recordExecution(skillName: String, parameters: Map<String, String>, result: String) {
        executionHistory.add(SkillExecution(skillName, parameters, result))
    }

    fun getExecutionHistory(skillName: String? = null, limit: Int = 10): List<SkillExecution> {
        return if (skillName != null) {
            executionHistory.filter { it.skillName == skillName }.takeLast(limit)
        } else {
            executionHistory.takeLast(limit)
        }
    }

    private fun loadSkills() {
        if (isLoading || loaded) return
        isLoading = true
        try {
            val dir = File(skillsDir)
            if (!dir.exists()) {
                dir.mkdirs()
            }

            dir.listFiles()?.filter { it.extension.equals("md", ignoreCase = true) }?.forEach { file ->
                runCatching { parseMarkdownSkill(file.readText()) }
                    .onSuccess { skill ->
                        if (skill.name.isNotBlank()) {
                            skills[skill.name] = skill
                        }
                    }
                    .onFailure { e ->
                        android.util.Log.e("SkillManager", "Failed to load markdown skill: ${file.name}", e)
                    }
            }

            migrateLegacyJsonSkills(dir)
            registerDefaultSkills()
            loaded = true
        } finally {
            isLoading = false
        }
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        if (isLoading) return
        loadSkills()
    }

    private fun saveSkill(skill: Skill) {
        try {
            val file = File(skillsDir, "${skill.name}.md")
            file.writeText(renderMarkdownSkill(skill))
        } catch (e: Exception) {
            android.util.Log.e("SkillManager", "Failed to save skill: ${skill.name}", e)
        }
    }

    private fun migrateLegacyJsonSkills(dir: File) {
        dir.listFiles()?.filter { it.extension.equals("json", ignoreCase = true) }?.forEach { file ->
            runCatching {
                val skill = parseLegacyJsonSkill(file.readText())
                if (skill.name.isNotBlank()) {
                    skills[skill.name] = skill
                    saveSkill(skill)
                    file.delete()
                }
            }.onFailure { e ->
                android.util.Log.e("SkillManager", "Failed to migrate legacy skill: ${file.name}", e)
            }
        }
    }

    private fun parseLegacyJsonSkill(json: String): Skill {
        val obj = JsonParser.parseString(json).asJsonObject
        val name = obj.get("name")?.asString.orEmpty()
        val description = obj.get("description")?.asString.orEmpty()
        val instruction = obj.get("instruction")?.asString.orEmpty()

        val parameters = obj.getAsJsonArray("parameters")?.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val p = element.asJsonObject
            SkillParameter(
                name = p.get("name")?.asString.orEmpty(),
                description = p.get("description")?.asString.orEmpty(),
                required = p.get("required")?.asBoolean ?: false,
                default = p.get("default")?.asString
            )
        }.orEmpty()

        val examples = obj.getAsJsonArray("examples")?.mapNotNull {
            if (it.isJsonPrimitive) it.asString else null
        }.orEmpty()

        return Skill(
            name = name,
            description = description,
            instruction = instruction,
            parameters = parameters,
            examples = examples
        )
    }

    private fun renderMarkdownSkill(skill: Skill): String {
        return buildString {
            appendLine("# Skill: ${skill.name}")
            appendLine("Description: ${skill.description}")
            appendLine()
            appendLine("## Instruction")
            appendLine(skill.instruction)
            appendLine()
            appendLine("## Parameters")
            if (skill.parameters.isEmpty()) {
                appendLine("- (none)")
            } else {
                skill.parameters.forEach { p ->
                    appendLine("- ${p.name}|${if (p.required) "required" else "optional"}|${p.description}|${p.default.orEmpty()}")
                }
            }
            appendLine()
            appendLine("## Examples")
            if (skill.examples.isEmpty()) {
                appendLine("- (none)")
            } else {
                skill.examples.forEach { ex -> appendLine("- $ex") }
            }
        }
    }

    private fun parseMarkdownSkill(content: String): Skill {
        val lines = content.lines()
        var name = ""
        var description = ""
        val instruction = StringBuilder()
        val parameters = mutableListOf<SkillParameter>()
        val examples = mutableListOf<String>()

        var section = SkillMarkdownSection.NONE

        lines.forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("# Skill:") -> {
                    name = line.substringAfter(":").trim()
                    section = SkillMarkdownSection.NONE
                }
                line.startsWith("# ") && name.isBlank() -> {
                    name = line.substringAfter("# ").trim()
                    section = SkillMarkdownSection.NONE
                }
                line.startsWith("Description:", ignoreCase = true) -> {
                    description = line.substringAfter(":").trim()
                    section = SkillMarkdownSection.NONE
                }
                line.equals("## Instruction", ignoreCase = true) -> section = SkillMarkdownSection.INSTRUCTION
                line.equals("## Parameters", ignoreCase = true) -> section = SkillMarkdownSection.PARAMETERS
                line.equals("## Examples", ignoreCase = true) -> section = SkillMarkdownSection.EXAMPLES
                section == SkillMarkdownSection.INSTRUCTION -> {
                    if (line.isNotBlank()) {
                        if (instruction.isNotEmpty()) instruction.append('\n')
                        instruction.append(raw.trimEnd())
                    }
                }
                section == SkillMarkdownSection.PARAMETERS && line.startsWith("-") -> {
                    val body = line.removePrefix("-").trim()
                    if (body != "(none)") {
                        val parts = body.split("|")
                        parameters += SkillParameter(
                            name = parts.getOrNull(0)?.trim().orEmpty(),
                            required = parts.getOrNull(1)?.trim().equals("required", ignoreCase = true),
                            description = parts.getOrNull(2)?.trim().orEmpty(),
                            default = parts.getOrNull(3)?.trim().takeIf { !it.isNullOrBlank() }
                        )
                    }
                }
                section == SkillMarkdownSection.EXAMPLES && line.startsWith("-") -> {
                    val body = line.removePrefix("-").trim()
                    if (body != "(none)" && body.isNotBlank()) examples += body
                }
            }
        }

        if (name.isBlank()) {
            throw IllegalArgumentException("Invalid markdown skill: missing name")
        }

        return Skill(
            name = name,
            description = description,
            instruction = instruction.toString().ifBlank { description.ifBlank { "No instruction" } },
            parameters = parameters.filter { it.name.isNotBlank() },
            examples = examples
        )
    }

    private fun registerDefaultSkills() {
        if (!skills.containsKey("screen_reader")) {
            upsertDefaultSkill(Skill(
                name = "screen_reader",
                description = "读取当前屏幕内容",
                instruction = "用于获取屏幕上所有可访问的文本内容，返回当前界面的文字信息",
                examples = listOf("读取屏幕", "看看当前页面有什么")
            ))
        }

        if (!skills.containsKey("app_launcher")) {
            upsertDefaultSkill(Skill(
                name = "app_launcher",
                description = "打开指定应用",
                instruction = "根据包名打开应用程序，如果不知道包名可以描述应用名称",
                parameters = listOf(SkillParameter("packageName", "应用包名", true)),
                examples = listOf("打开微信", "启动支付宝")
            ))
        }

        if (!skills.containsKey("text_clicker")) {
            upsertDefaultSkill(Skill(
                name = "text_clicker",
                description = "点击屏幕上的文本",
                instruction = "点击屏幕上包含指定文本的元素，用于按钮点击等操作",
                parameters = listOf(SkillParameter("text", "要点击的文本内容", true)),
                examples = listOf("点击确定", "点击登录按钮")
            ))
        }

        if (!skills.containsKey("swipe_navigator")) {
            upsertDefaultSkill(Skill(
                name = "swipe_navigator",
                description = "滑动屏幕导航",
                instruction = "执行上下左右滑动操作，用于翻页、返回等",
                parameters = listOf(
                    SkillParameter("direction", "滑动方向", true, "down"),
                    SkillParameter("times", "滑动次数", false, "1")
                ),
                examples = listOf("向下滑动", "向上翻页两次")
            ))
        }

        if (!skills.containsKey("wechat_send_message")) {
            upsertDefaultSkill(Skill(
                name = "wechat_send_message",
                description = "高可靠执行微信发消息",
                instruction = "优先打开微信并定位会话，输入消息后点击发送或触发输入法回车，并在发送后做一次确认。",
                parameters = listOf(
                    SkillParameter("contact", "联系人名称", true),
                    SkillParameter("message", "发送的消息内容", true)
                ),
                examples = listOf("给张三发：今晚8点开会", "给产品群发：版本已发布")
            ))
        }
    }

    private fun upsertDefaultSkill(skill: Skill) {
        skills[skill.name] = skill
        saveSkill(skill)
    }
}
