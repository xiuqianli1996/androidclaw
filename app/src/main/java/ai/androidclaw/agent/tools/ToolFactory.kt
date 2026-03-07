package ai.androidclaw.agent.tools

import ai.androidclaw.agent.tools.impl.CommandTools
import ai.androidclaw.agent.tools.impl.CommandToolExecutor
import ai.androidclaw.agent.tools.impl.FileTools
import ai.androidclaw.agent.tools.impl.FileToolExecutor
import ai.androidclaw.agent.tools.impl.HttpTools
import ai.androidclaw.agent.tools.impl.HttpToolExecutor
import ai.androidclaw.agent.tools.impl.PhoneTools

class ToolFactory(private val toolRegistry: ToolRegistry) {

    fun registerAllTools() {
        registerPhoneTools()
        registerFileTools()
        registerCommandTools()
        registerHttpTools()
    }

    private fun registerPhoneTools() {
        val phoneTools = PhoneTools()
        val methods = phoneTools::class.java.declaredMethods
        
        methods.forEach { method ->
            method.isAccessible = true
            val annotation = method.getAnnotation(dev.langchain4j.agent.tool.Tool::class.java)
            if (annotation != null) {
                toolRegistry.register(PhoneToolExecutor(method, phoneTools))
            }
        }
    }

    private fun registerFileTools() {
        val fileTools = FileTools()
        val methods = fileTools::class.java.declaredMethods
        
        methods.forEach { method ->
            method.isAccessible = true
            val annotation = method.getAnnotation(dev.langchain4j.agent.tool.Tool::class.java)
            if (annotation != null) {
                toolRegistry.register(FileToolExecutor(method, fileTools))
            }
        }
    }

    private fun registerCommandTools() {
        val commandTools = CommandTools()
        val methods = commandTools::class.java.declaredMethods
        
        methods.forEach { method ->
            method.isAccessible = true
            val annotation = method.getAnnotation(dev.langchain4j.agent.tool.Tool::class.java)
            if (annotation != null) {
                toolRegistry.register(CommandToolExecutor(method, commandTools))
            }
        }
    }

    private fun registerHttpTools() {
        val httpTools = HttpTools()
        val methods = httpTools::class.java.declaredMethods
        
        methods.forEach { method ->
            method.isAccessible = true
            val annotation = method.getAnnotation(dev.langchain4j.agent.tool.Tool::class.java)
            if (annotation != null) {
                toolRegistry.register(HttpToolExecutor(method, httpTools))
            }
        }
    }
}

class PhoneToolExecutor(
    private val method: java.lang.reflect.Method,
    private val tools: PhoneTools
) : ClawToolExecutor {
    
    override val name: String = method.getAnnotation(dev.langchain4j.agent.tool.Tool::class.java)?.name ?: method.name
    
    override val description: String =
        method.getAnnotation(dev.langchain4j.agent.tool.Tool::class.java)
            ?.value
            ?.firstOrNull()
            .orEmpty()
    
    override val category: ToolCategory = ToolCategory.PHONE

    @Suppress("UNCHECKED_CAST")
    override fun execute(parameters: Map<String, Any>): ToolResult {
        return try {
            val args = parameters.values.toTypedArray()
            val result = method.invoke(tools, *args)
            
            if (result is kotlin.coroutines.Continuation<*>) {
                ToolResult.success("异步操作已启动")
            } else if (result is ToolResult) result
            else ToolResult.success(result?.toString() ?: "操作完成")
        } catch (e: Exception) {
            ToolResult.error("执行错误: ${e.message}")
        }
    }

    override fun getToolSpecification(): dev.langchain4j.agent.tool.ToolSpecification {
        return dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(method)
    }
}
