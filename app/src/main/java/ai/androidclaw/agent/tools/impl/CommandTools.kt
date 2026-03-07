package ai.androidclaw.agent.tools.impl

import ai.androidclaw.agent.tools.ClawToolExecutor
import ai.androidclaw.agent.tools.ToolCategory
import ai.androidclaw.agent.tools.ToolResult
import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.agent.tool.ToolSpecifications
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

class CommandTools(private val timeout: Long = 30000) {

    @Tool(name = "command_execute", value = ["执行shell命令并返回输出"])
    fun execute(command: String, workingDir: String = ""): ToolResult {
        return try {
            val processBuilder = ProcessBuilder("/bin/sh", "-c", command)
            if (workingDir.isNotEmpty()) {
                processBuilder.directory(java.io.File(workingDir))
            }
            processBuilder.redirectErrorStream(true)
            processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
            
            val process = processBuilder.start()
            val output = StringBuilder()
            
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < timeout) {
                    val line = reader.readLine() ?: break
                    output.appendLine(line)
                }
                if (process.isAlive) {
                    process.destroyForcibly()
                    output.appendLine("命令执行超时，已强制终止")
                }
            }
            
            val exitCode = process.waitFor()
            val result = output.toString().trim()
            if (exitCode == 0) {
                ToolResult.success(result.ifEmpty { "命令执行成功，无输出" })
            } else {
                ToolResult.error("命令执行失败 (exit $exitCode): $result")
            }
        } catch (e: Exception) {
            ToolResult.error("执行命令失败: ${e.message}")
        }
    }

    @Tool(name = "command_background", value = ["在后台执行shell命令"])
    fun executeBackground(command: String): ToolResult {
        return try {
            Runtime.getRuntime().exec(arrayOf("/bin/sh", "-c", "$command &"))
            ToolResult.success("已在后台执行命令")
        } catch (e: Exception) {
            ToolResult.error("后台执行失败: ${e.message}")
        }
    }

    @Tool(name = "command_kill", value = ["终止指定进程"])
    fun killProcess(pid: Int): ToolResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("kill", "-9", pid.toString()))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                ToolResult.success("已终止进程: $pid")
            } else {
                ToolResult.error("无法终止进程: $pid")
            }
        } catch (e: Exception) {
            ToolResult.error("终止进程失败: ${e.message}")
        }
    }

    @Tool(name = "command_list_processes", value = ["列出运行中的进程"])
    fun listProcesses(filter: String = ""): ToolResult {
        return try {
            val command = if (filter.isEmpty()) "ps -A" else "ps -A | grep $filter"
            val process = Runtime.getRuntime().exec(arrayOf("/bin/sh", "-c", command))
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText()
            }
            ToolResult.success(output)
        } catch (e: Exception) {
            ToolResult.error("列出进程失败: ${e.message}")
        }
    }

    @Tool(name = "command_get_pid", value = ["获取当前应用进程ID"])
    fun getCurrentPid(): ToolResult {
        return try {
            val pid = android.os.Process.myPid()
            ToolResult.success(pid.toString())
        } catch (e: Exception) {
            ToolResult.error("获取PID失败: ${e.message}")
        }
    }

    @Tool(name = "command_get_memory", value = ["获取设备内存信息"])
    fun getMemoryInfo(): ToolResult {
        return try {
            val reader = BufferedReader(InputStreamReader(Runtime.getRuntime().exec("cat /proc/meminfo").inputStream))
            val memInfo = reader.readText()
            reader.close()
            ToolResult.success(memInfo)
        } catch (e: Exception) {
            ToolResult.error("获取内存信息失败: ${e.message}")
        }
    }

    @Tool(name = "command_get_cpu", value = ["获取CPU信息"])
    fun getCpuInfo(): ToolResult {
        return try {
            val reader = BufferedReader(InputStreamReader(Runtime.getRuntime().exec("cat /proc/cpuinfo").inputStream))
            val cpuInfo = reader.readText()
            reader.close()
            ToolResult.success(cpuInfo)
        } catch (e: Exception) {
            ToolResult.error("获取CPU信息失败: ${e.message}")
        }
    }
}

class CommandToolExecutor(
    private val method: Method,
    private val tools: CommandTools
) : ClawToolExecutor {

    override val name: String = method.getAnnotation(Tool::class.java)?.name ?: method.name

    override val description: String = method.getAnnotation(Tool::class.java)?.value?.firstOrNull().orEmpty()

    override val category: ToolCategory = ToolCategory.COMMAND

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
