package ai.androidclaw.agent.skills

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

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

class SkillManager(private val skillsDir: String = "/data/data/ai.androidclaw/files/skills") {
    
    private val skills = mutableMapOf<String, Skill>()
    private val executionHistory = mutableListOf<SkillExecution>()
    private val gson = Gson()

    init {
        java.io.File(skillsDir).mkdirs()
        loadSkills()
    }

    fun registerSkill(skill: Skill) {
        skills[skill.name] = skill
        saveSkill(skill)
    }

    fun unregisterSkill(name: String) {
        skills.remove(name)
        java.io.File(skillsDir, "$name.json").delete()
    }

    fun getSkill(name: String): Skill? = skills[name]

    fun getAllSkills(): List<Skill> = skills.values.toList()

    fun getSkillNames(): Set<String> = skills.keys

    fun getSkillForPrompt(): String {
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
        return skills[skillName]?.parameters?.map { it.name } ?: emptyList()
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
        val dir = java.io.File(skillsDir)
        if (!dir.exists()) return

        dir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            try {
                val json = file.readText()
                val skill = gson.fromJson(json, Skill::class.java)
                skills[skill.name] = skill
            } catch (e: Exception) {
                android.util.Log.e("SkillManager", "Failed to load skill: ${file.name}", e)
            }
        }

        registerDefaultSkills()
    }

    private fun saveSkill(skill: Skill) {
        try {
            val file = java.io.File(skillsDir, "${skill.name}.json")
            file.writeText(gson.toJson(skill))
        } catch (e: Exception) {
            android.util.Log.e("SkillManager", "Failed to save skill: ${skill.name}", e)
        }
    }

    private fun registerDefaultSkills() {
        if (!skills.containsKey("screen_reader")) {
            registerSkill(Skill(
                name = "screen_reader",
                description = "读取当前屏幕内容",
                instruction = "用于获取屏幕上所有可访问的文本内容，返回当前界面的文字信息",
                examples = listOf("读取屏幕", "看看当前页面有什么")
            ))
        }

        if (!skills.containsKey("app_launcher")) {
            registerSkill(Skill(
                name = "app_launcher",
                description = "打开指定应用",
                instruction = "根据包名打开应用程序，如果不知道包名可以描述应用名称",
                parameters = listOf(SkillParameter("packageName", "应用包名", true)),
                examples = listOf("打开微信", "启动支付宝")
            ))
        }

        if (!skills.containsKey("text_clicker")) {
            registerSkill(Skill(
                name = "text_clicker",
                description = "点击屏幕上的文本",
                instruction = "点击屏幕上包含指定文本的元素，用于按钮点击等操作",
                parameters = listOf(SkillParameter("text", "要点击的文本内容", true)),
                examples = listOf("点击确定", "点击登录按钮")
            ))
        }

        if (!skills.containsKey("swipe_navigator")) {
            registerSkill(Skill(
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
    }
}
