package ai.androidclaw.mcp

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

data class McpDiscoveredTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject?
)

object McpStdioClient {

    private const val TAG = "McpStdioClient"
    private val gson = Gson()
    private val idGen = AtomicInteger(1)

    fun listTools(config: McpServerConfig): List<McpDiscoveredTool> {
        return withSession(config) { session ->
            session.initialize()
            session.listTools()
        }
    }

    fun callTool(config: McpServerConfig, toolName: String, arguments: JsonObject): String {
        return withSession(config) { session ->
            session.initialize()
            session.callTool(toolName, arguments)
        }
    }

    private fun <T> withSession(config: McpServerConfig, block: (Session) -> T): T {
        require(config.transport == McpTransport.STDIO) {
            "Only stdio MCP transport is supported currently"
        }
        require(config.command.isNotBlank()) {
            "MCP command is empty"
        }

        val args = config.args.split(" ").filter { it.isNotBlank() }
        val processBuilder = ProcessBuilder(listOf(config.command) + args)
        if (config.envJson.isNotBlank()) {
            runCatching {
                val envMap = gson.fromJson(config.envJson, JsonObject::class.java)
                envMap.entrySet().forEach { entry ->
                    processBuilder.environment()[entry.key] = entry.value.asString
                }
            }
        }

        val process = processBuilder.start()
        val session = Session(
            process = process,
            input = BufferedInputStream(process.inputStream),
            output = BufferedOutputStream(process.outputStream)
        )

        return try {
            block(session)
        } finally {
            runCatching { session.close() }
            runCatching { process.destroy() }
        }
    }

    private class Session(
        private val process: Process,
        private val input: BufferedInputStream,
        private val output: BufferedOutputStream
    ) {
        private var initialized = false

        fun initialize() {
            if (initialized) return

            val params = JsonObject().apply {
                addProperty("protocolVersion", "2024-11-05")
                add("capabilities", JsonObject())
                add("clientInfo", JsonObject().apply {
                    addProperty("name", "AndroidClaw")
                    addProperty("version", "1.0")
                })
            }

            request("initialize", params)
            notify("notifications/initialized", JsonObject())
            initialized = true
        }

        fun listTools(): List<McpDiscoveredTool> {
            val result = request("tools/list", JsonObject())
            val tools = result.getAsJsonArray("tools") ?: JsonArray()
            return tools.mapNotNull { element ->
                val obj = element.asJsonObject
                val name = obj.get("name")?.asString ?: return@mapNotNull null
                val description = obj.get("description")?.asString ?: ""
                val schema = obj.get("inputSchema")?.takeIf { it.isJsonObject }?.asJsonObject
                McpDiscoveredTool(name, description, schema)
            }
        }

        fun callTool(toolName: String, arguments: JsonObject): String {
            val params = JsonObject().apply {
                addProperty("name", toolName)
                add("arguments", arguments)
            }
            val result = request("tools/call", params)

            val content = result.getAsJsonArray("content") ?: return result.toString()
            if (content.size() == 0) return result.toString()

            return buildString {
                content.forEach { part ->
                    val obj = part.asJsonObject
                    when (obj.get("type")?.asString) {
                        "text" -> appendLine(obj.get("text")?.asString.orEmpty())
                        else -> appendLine(obj.toString())
                    }
                }
            }.trim().ifEmpty { result.toString() }
        }

        private fun request(method: String, params: JsonObject): JsonObject {
            val id = idGen.getAndIncrement()
            val payload = JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", id)
                addProperty("method", method)
                add("params", params)
            }

            writeFrame(payload)

            while (true) {
                val message = readFrame()
                if (!message.isJsonObject) continue
                val obj = message.asJsonObject
                val messageId = obj.get("id")
                if (messageId == null || !messageId.isJsonPrimitive || messageId.asInt != id) {
                    continue
                }

                if (obj.has("error")) {
                    throw IllegalStateException("MCP error: ${obj.get("error")}")
                }
                return obj.getAsJsonObject("result") ?: JsonObject()
            }
        }

        private fun notify(method: String, params: JsonObject) {
            val payload = JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("method", method)
                add("params", params)
            }
            writeFrame(payload)
        }

        private fun writeFrame(json: JsonObject) {
            val body = gson.toJson(json).toByteArray(StandardCharsets.UTF_8)
            val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
            output.write(header)
            output.write(body)
            output.flush()
        }

        private fun readFrame(): JsonElement {
            val contentLength = readContentLength()
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n < 0) throw IllegalStateException("MCP process closed")
                read += n
            }
            val text = String(body, StandardCharsets.UTF_8)
            return gson.fromJson(text, JsonElement::class.java)
        }

        private fun readContentLength(): Int {
            val headerBytes = ByteArrayOutputStream()
            var lastFour = intArrayOf(-1, -1, -1, -1)
            while (true) {
                val b = input.read()
                if (b < 0) throw IllegalStateException("MCP process closed")
                headerBytes.write(b)
                lastFour[0] = lastFour[1]
                lastFour[1] = lastFour[2]
                lastFour[2] = lastFour[3]
                lastFour[3] = b
                if (lastFour[0] == '\r'.code && lastFour[1] == '\n'.code && lastFour[2] == '\r'.code && lastFour[3] == '\n'.code) {
                    break
                }
            }

            val header = headerBytes.toString(StandardCharsets.US_ASCII.name())
            val contentLengthLine = header.lines().firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?: throw IllegalStateException("Missing Content-Length header")
            return contentLengthLine.substringAfter(':').trim().toIntOrNull()
                ?: throw IllegalStateException("Invalid Content-Length header")
        }

        fun close() {
            runCatching { output.close() }
            runCatching { input.close() }
            runCatching { process.destroy() }
        }
    }
}
