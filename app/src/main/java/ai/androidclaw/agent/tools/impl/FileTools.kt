package ai.androidclaw.agent.tools.impl

import ai.androidclaw.agent.tools.ClawToolExecutor
import ai.androidclaw.agent.tools.ToolCategory
import ai.androidclaw.agent.tools.ToolResult
import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.agent.tool.ToolSpecifications
import java.io.File
import java.lang.reflect.Method

class FileTools(private val basePath: String = "/data/data/ai.androidclaw/files") {

    init {
        File(basePath).mkdirs()
    }

    @Tool(name = "file_read", value = ["读取指定路径的文件内容"])
    fun readFile(path: String): ToolResult {
        return try {
            val file = if (path.startsWith("/")) File(path) else File(basePath, path)
            if (!file.exists()) {
                return ToolResult.error("文件不存在: $path")
            }
            if (!file.canRead()) {
                return ToolResult.error("无法读取文件: $path")
            }
            val content = file.readText()
            ToolResult.success(content)
        } catch (e: Exception) {
            ToolResult.error("读取文件失败: ${e.message}")
        }
    }

    @Tool(name = "file_write", value = ["写入内容到指定文件"])
    fun writeFile(path: String, content: String, append: Boolean = false): ToolResult {
        return try {
            val file = if (path.startsWith("/")) File(path) else File(basePath, path)
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            if (append) {
                file.appendText(content)
            } else {
                file.writeText(content)
            }
            ToolResult.success("已写入文件: $path")
        } catch (e: Exception) {
            ToolResult.error("写入文件失败: ${e.message}")
        }
    }

    @Tool(name = "file_delete", value = ["删除指定文件"])
    fun deleteFile(path: String): ToolResult {
        return try {
            val file = if (path.startsWith("/")) File(path) else File(basePath, path)
            if (!file.exists()) {
                return ToolResult.error("文件不存在: $path")
            }
            val deleted = file.delete()
            if (deleted) ToolResult.success("已删除文件: $path")
            else ToolResult.error("删除文件失败")
        } catch (e: Exception) {
            ToolResult.error("删除文件失败: ${e.message}")
        }
    }

    @Tool(name = "file_list", value = ["列出指定目录下的文件"])
    fun listFiles(path: String = ""): ToolResult {
        return try {
            val dir = if (path.isEmpty()) File(basePath) 
                      else if (path.startsWith("/")) File(path) 
                      else File(basePath, path)
            if (!dir.exists()) {
                return ToolResult.error("目录不存在: $path")
            }
            if (!dir.isDirectory) {
                return ToolResult.error("不是目录: $path")
            }
            val files = dir.listFiles()?.map { 
                "${if (it.isDirectory) "D" else "F"}: ${it.name}"
            }?.joinToString("\n") ?: "空目录"
            ToolResult.success(files)
        } catch (e: Exception) {
            ToolResult.error("列出文件失败: ${e.message}")
        }
    }

    @Tool(name = "file_exists", value = ["检查文件或目录是否存在"])
    fun exists(path: String): ToolResult {
        return try {
            val file = if (path.startsWith("/")) File(path) else File(basePath, path)
            ToolResult.success("存在: ${file.exists()}, 是目录: ${file.isDirectory}")
        } catch (e: Exception) {
            ToolResult.error("检查失败: ${e.message}")
        }
    }

    @Tool(name = "file_mkdir", value = ["创建目录"])
    fun mkdir(path: String): ToolResult {
        return try {
            val dir = if (path.startsWith("/")) File(path) else File(basePath, path)
            val created = dir.mkdirs()
            if (created) ToolResult.success("已创建目录: $path")
            else ToolResult.error("创建目录失败")
        } catch (e: Exception) {
            ToolResult.error("创建目录失败: ${e.message}")
        }
    }

    @Tool(name = "file_get_external_dir", value = ["获取应用外部存储目录"])
    fun getExternalDir(): ToolResult {
        return try {
            ToolResult.success(basePath)
        } catch (e: Exception) {
            ToolResult.error("获取目录失败: ${e.message}")
        }
    }
}

class FileToolExecutor(
    private val method: Method,
    private val tools: FileTools
) : ClawToolExecutor {

    override val name: String = method.getAnnotation(Tool::class.java)?.name ?: method.name

    override val description: String = method.getAnnotation(Tool::class.java)?.value?.firstOrNull().orEmpty()

    override val category: ToolCategory = ToolCategory.FILE

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
