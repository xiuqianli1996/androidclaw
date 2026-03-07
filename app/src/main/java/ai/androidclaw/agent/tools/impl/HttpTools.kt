package ai.androidclaw.agent.tools.impl

import ai.androidclaw.agent.tools.ClawToolExecutor
import ai.androidclaw.agent.tools.ToolCategory
import ai.androidclaw.agent.tools.ToolResult
import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.agent.tool.ToolSpecifications
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.lang.reflect.Method

class HttpTools(private val defaultTimeout: Int = 30000) {

    @Tool(name = "http_get", value = ["发送GET请求"])
    fun get(url: String, headers: Map<String, String> = emptyMap()): ToolResult {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = defaultTimeout
            connection.readTimeout = defaultTimeout
            connection.setRequestProperty("User-Agent", "AndroidClaw/1.0")
            
            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }
            
            val response = readResponse(connection)
            connection.disconnect()
            
            if (connection.responseCode in 200..299) {
                ToolResult.success(response)
            } else {
                ToolResult.error("HTTP ${connection.responseCode}: $response")
            }
        } catch (e: Exception) {
            ToolResult.error("GET请求失败: ${e.message}")
        }
    }

    @Tool(name = "http_post", value = ["发送POST请求"])
    fun post(
        url: String, 
        body: String = "", 
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap()
    ): ToolResult {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = defaultTimeout
            connection.readTimeout = defaultTimeout
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("User-Agent", "AndroidClaw/1.0")
            
            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }
            
            if (body.isNotEmpty()) {
                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(body)
                    writer.flush()
                }
            }
            
            val response = readResponse(connection)
            connection.disconnect()
            
            if (connection.responseCode in 200..299) {
                ToolResult.success(response)
            } else {
                ToolResult.error("HTTP ${connection.responseCode}: $response")
            }
        } catch (e: Exception) {
            ToolResult.error("POST请求失败: ${e.message}")
        }
    }

    @Tool(name = "http_put", value = ["发送PUT请求"])
    fun put(
        url: String, 
        body: String = "", 
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap()
    ): ToolResult {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.connectTimeout = defaultTimeout
            connection.readTimeout = defaultTimeout
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("User-Agent", "AndroidClaw/1.0")
            
            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }
            
            if (body.isNotEmpty()) {
                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(body)
                    writer.flush()
                }
            }
            
            val response = readResponse(connection)
            connection.disconnect()
            
            if (connection.responseCode in 200..299) {
                ToolResult.success(response)
            } else {
                ToolResult.error("HTTP ${connection.responseCode}: $response")
            }
        } catch (e: Exception) {
            ToolResult.error("PUT请求失败: ${e.message}")
        }
    }

    @Tool(name = "http_delete", value = ["发送DELETE请求"])
    fun delete(
        url: String, 
        headers: Map<String, String> = emptyMap()
    ): ToolResult {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "DELETE"
            connection.connectTimeout = defaultTimeout
            connection.readTimeout = defaultTimeout
            connection.setRequestProperty("User-Agent", "AndroidClaw/1.0")
            
            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }
            
            val response = readResponse(connection)
            connection.disconnect()
            
            if (connection.responseCode in 200..299) {
                ToolResult.success(response)
            } else {
                ToolResult.error("HTTP ${connection.responseCode}: $response")
            }
        } catch (e: Exception) {
            ToolResult.error("DELETE请求失败: ${e.message}")
        }
    }

    @Tool(name = "http_head", value = ["发送HEAD请求获取响应头"])
    fun head(url: String): ToolResult {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = defaultTimeout
            
            val headers = connection.headerFields
                .filter { it.key != null }
                .map { "${it.key}: ${it.value.joinToString(", ")}" }
                .joinToString("\n")
            
            connection.disconnect()
            ToolResult.success(headers)
        } catch (e: Exception) {
            ToolResult.error("HEAD请求失败: ${e.message}")
        }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        return if (connection.errorStream != null) {
            BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
        } else {
            BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        }
    }
}

class HttpToolExecutor(
    private val method: Method,
    private val tools: HttpTools
) : ClawToolExecutor {

    override val name: String = method.getAnnotation(Tool::class.java)?.name ?: method.name

    override val description: String = method.getAnnotation(Tool::class.java)?.value?.firstOrNull().orEmpty()

    override val category: ToolCategory = ToolCategory.HTTP

    override fun execute(parameters: Map<String, Any>): ToolResult {
        return try {
            val args = parameters.values.toTypedArray()
            val result = method.invoke(tools, *args)
            if (result is ToolResult) result
            else ToolResult.success(result?.toString() ?: "操作完成")
        } catch (e: Exception) {
            ToolResult.error("执行错误: ${e.message}")
        }
    }

    override fun getToolSpecification(): ToolSpecification {
        return ToolSpecifications.toolSpecificationFrom(method)
    }
}
