package ai.androidclaw.agent.tools

import dev.langchain4j.agent.tool.Tool

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class ClawTool(
    val name: String,
    val description: String,
    val category: ToolCategory = ToolCategory.GENERAL
)

enum class ToolCategory {
    PHONE,
    FILE,
    COMMAND,
    HTTP,
    GENERAL
}

data class ToolResult(
    val success: Boolean,
    val result: String,
    val error: String? = null
) {
    companion object {
        fun success(result: String) = ToolResult(true, result)
        fun error(error: String) = ToolResult(false, "", error)
    }
}

interface ClawToolExecutor {
    val name: String
    val description: String
    val category: ToolCategory
    
    fun execute(parameters: Map<String, Any>): ToolResult
    
    fun getToolSpecification(): dev.langchain4j.agent.tool.ToolSpecification
}
