package ai.androidclaw.mcp

import ai.androidclaw.agent.tools.ClawToolExecutor
import ai.androidclaw.agent.tools.ToolCategory
import ai.androidclaw.agent.tools.ToolResult
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.langchain4j.agent.tool.JsonSchemaProperty
import dev.langchain4j.agent.tool.ToolSpecification

class McpToolExecutor(
    private val serverConfig: McpServerConfig,
    private val mcpToolName: String,
    private val mcpToolDescription: String,
    private val inputSchema: JsonObject?
) : ClawToolExecutor {

    private val gson = Gson()

    override val name: String = "mcp_${serverConfig.name.lowercase().replace(" ", "_")}__${mcpToolName}"

    override val description: String = buildString {
        append("[MCP:${serverConfig.name}] ")
        append(mcpToolDescription.ifBlank { mcpToolName })
    }

    override val category: ToolCategory = ToolCategory.GENERAL

    override fun execute(parameters: Map<String, Any>): ToolResult {
        if (!serverConfig.enabled) {
            return ToolResult.error("MCP server is disabled: ${serverConfig.name}")
        }
        if (serverConfig.transport != McpTransport.STDIO) {
            return ToolResult.error("Unsupported MCP transport for runtime call: ${serverConfig.transport.displayName}")
        }

        return runCatching {
            val argsObj = gson.fromJson(gson.toJson(parameters), JsonObject::class.java)
            val result = McpStdioClient.callTool(serverConfig, mcpToolName, argsObj)
            ToolResult.success(result)
        }.getOrElse { e ->
            ToolResult.error("MCP call failed: ${e.message}")
        }
    }

    override fun getToolSpecification(): ToolSpecification {
        val builder = ToolSpecification.builder()
            .name(name)
            .description(description)

        val schemaProperties = inputSchema
            ?.getAsJsonObject("properties")
            ?.entrySet()
            ?.associate { (key, value) -> key to toSchemaProperties(value) }
            .orEmpty()

        val required = inputSchema
            ?.getAsJsonArray("required")
            ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
            .orEmpty()
            .toSet()

        schemaProperties.forEach { (paramName, props) ->
            if (required.contains(paramName)) {
                builder.addParameter(paramName, props.toList())
            } else {
                builder.addOptionalParameter(paramName, props.toList())
            }
        }

        return builder.build()
    }

    private fun toSchemaProperties(value: JsonElement): Array<JsonSchemaProperty> {
        if (!value.isJsonObject) {
            return arrayOf(JsonSchemaProperty.type("string"))
        }
        val obj = value.asJsonObject
        val type = obj.get("type")?.asString ?: "string"
        val description = obj.get("description")?.asString.orEmpty()
        return if (description.isNotBlank()) {
            arrayOf(JsonSchemaProperty.type(type), JsonSchemaProperty.description(description))
        } else {
            arrayOf(JsonSchemaProperty.type(type))
        }
    }
}
