package ai.androidclaw.agent.tools

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.agent.tool.ToolSpecifications

class ToolRegistry {
    private val tools = mutableMapOf<String, ClawToolExecutor>()
    private val categoryTools = mutableMapOf<ToolCategory, MutableList<ClawToolExecutor>>()

    fun register(tool: ClawToolExecutor) {
        tools[tool.name] = tool
        categoryTools.getOrPut(tool.category) { mutableListOf() }.add(tool)
    }

    fun unregister(name: String) {
        tools[name]?.let { tool ->
            categoryTools[tool.category]?.remove(tool)
            tools.remove(name)
        }
    }

    fun get(name: String): ClawToolExecutor? = tools[name]

    fun getByCategory(category: ToolCategory): List<ClawToolExecutor> {
        return categoryTools[category] ?: emptyList()
    }

    fun getAll(): List<ClawToolExecutor> = tools.values.toList()

    fun getToolSpecifications(): List<ToolSpecification> {
        return tools.values.map { it.getToolSpecification() }
    }

    fun getToolNames(): Set<String> = tools.keys

    fun execute(name: String, parameters: Map<String, Any>): ToolResult {
        val tool = tools[name] ?: return ToolResult.error("Tool not found: $name")
        return try {
            tool.execute(parameters)
        } catch (e: Exception) {
            ToolResult.error("Tool execution error: ${e.message}")
        }
    }

    fun size(): Int = tools.size
}
