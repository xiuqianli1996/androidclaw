package ai.androidclaw.agent.tools.impl

import ai.androidclaw.agent.tools.ClawTool
import ai.androidclaw.agent.tools.ClawToolExecutor
import ai.androidclaw.agent.tools.ToolCategory
import ai.androidclaw.agent.tools.ToolResult
import ai.androidclaw.ClawApplication
import ai.androidclaw.service.ClawAccessibilityService
import ai.androidclaw.ui.overlay.ExecutionOverlayManager
import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.agent.tool.ToolSpecifications

class PhoneTools {
    
    @Tool(name = "phone_click_text", value = ["点击屏幕上包含指定文本的元素"])
    fun clickText(text: String): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val success = service.findAndClick(text)
            if (success) ToolResult.success("已点击文本: $text")
            else ToolResult.error("未找到元素: $text")
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }

    @Tool(name = "phone_long_click_text", value = ["长按屏幕上包含指定文本的元素"])
    fun longClickText(text: String): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val success = service.findAndLongClick(text)
            if (success) ToolResult.success("已长按文本: $text")
            else ToolResult.error("未找到元素: $text")
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }

    @Tool(name = "phone_click_coordinates", value = ["在指定坐标点击屏幕"])
    fun clickCoordinates(x: Float, y: Float): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val success = service.clickOnScreen(x, y)
            if (success) ToolResult.success("已在坐标 ($x, $y) 点击")
            else ToolResult.error("点击失败")
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }

    @Tool(name = "phone_long_click_coordinates", value = ["在指定坐标长按屏幕"])
    fun longClickCoordinates(x: Float, y: Float): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val success = service.longClickOnScreen(x, y)
            if (success) ToolResult.success("已在坐标 ($x, $y) 长按")
            else ToolResult.error("长按失败")
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }

    @Tool(name = "phone_swipe", value = ["在屏幕上执行滑动手势，从(startX, startY)滑动到(endX, endY)"])
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 300): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val success = service.swipe(startX, startY, endX, endY, duration)
            if (success) ToolResult.success("已从 ($startX, $startY) 滑动到 ($endX, $endY)")
            else ToolResult.error("滑动失败")
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }

    @Tool(name = "phone_scroll_down", value = ["向下滚动屏幕"])
    fun scrollDown(): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val success = service.scrollDown()
            if (success) ToolResult.success("已向下滚动")
            else ToolResult.error("滚动失败")
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }

    @Tool(name = "phone_scroll_up", value = ["向上滚动屏幕"])
    fun scrollUp(): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val success = service.scrollUp()
            if (success) ToolResult.success("已向上滚动")
            else ToolResult.error("滚动失败")
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }

    @Tool(name = "phone_open_app", value = ["打开指定包名的应用"])
    fun openApp(packageName: String): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val success = service.openApp(packageName)
            if (success) ToolResult.success("已打开应用: $packageName")
            else ToolResult.error("无法打开应用: $packageName")
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }

    @Tool(name = "phone_get_screen_text", value = ["获取当前屏幕上的所有文本内容"])
    fun getScreenText(): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val overlay = ExecutionOverlayManager.getInstance(ClawApplication.instance)
            val text = overlay.runWithCaptureHidden { service.getScreenText() }
            ToolResult.success(text.ifEmpty { "屏幕为空或无法获取内容" })
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }

    @Tool(name = "phone_input_text", value = ["在当前可编辑输入框中输入文本"])
    fun inputText(text: String): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val success = service.inputText(text)
            if (success) ToolResult.success("已输入文本")
            else ToolResult.error("未找到可输入控件或输入失败")
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }

    @Tool(name = "phone_ime_enter", value = ["在当前输入框触发输入法回车/发送动作"])
    fun imeEnter(): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val success = service.pressImeEnter()
            if (success) ToolResult.success("已触发输入法回车")
            else ToolResult.error("触发输入法回车失败")
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }

    @Tool(name = "phone_get_screenshot", value = ["获取当前屏幕截图，返回data URL"])
    fun getScreenshot(): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val overlay = ExecutionOverlayManager.getInstance(ClawApplication.instance)
            val dataUrl = overlay.runWithCaptureHidden { service.captureScreenshotDataUrl() }
            if (!dataUrl.isNullOrBlank()) {
                ToolResult.success(dataUrl)
            } else {
                ToolResult.error("截图失败")
            }
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }

    @Tool(name = "phone_wait", value = ["阻塞等待指定毫秒数，用于等待页面加载"])
    fun waitFor(milliseconds: Long): ToolResult {
        val duration = milliseconds.coerceIn(100L, 10_000L)
        return try {
            Thread.sleep(duration)
            ToolResult.success("已等待${duration}ms")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ToolResult.error("等待被中断")
        }
    }

    @Tool(name = "phone_wait_text", value = ["等待屏幕出现指定文本，常用于等待页面加载完成"])
    fun waitText(text: String, timeoutMs: Long = 5000): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val found = service.waitForText(text, timeoutMs)
            if (found) ToolResult.success("已检测到文本: $text")
            else ToolResult.error("等待超时，未检测到文本: $text")
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }

    @Tool(name = "phone_get_window_title", value = ["获取当前窗口标题"])
    fun getWindowTitle(): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val title = service.getCurrentWindowTitle()
            ToolResult.success(title)
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }

    @Tool(name = "phone_back", value = ["按下返回键"])
    fun pressBack(): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val success = service.pressBack()
            if (success) ToolResult.success("已按下返回键")
            else ToolResult.error("按返回键失败")
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }

    @Tool(name = "phone_home", value = ["按下Home键返回主屏幕"])
    fun pressHome(): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val success = service.pressHome()
            if (success) ToolResult.success("已按下Home键")
            else ToolResult.error("按Home键失败")
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }

    @Tool(name = "phone_recents", value = ["打开最近任务列表"])
    fun openRecents(): ToolResult {
        val service = ClawAccessibilityService.instance
        return if (service != null) {
            val success = service.openRecents()
            if (success) ToolResult.success("已打开最近任务")
            else ToolResult.error("打开最近任务失败")
        } else {
            ToolResult.error("无障碍服务未启动")
        }
    }
}

class PhoneToolExecutor(
    private val method: java.lang.reflect.Method,
    private val tool: PhoneTools
) : ClawToolExecutor {
    
    override val name: String = method.getAnnotation(androidx.annotation.OptIn::class.java)?.let {
        method.name
    } ?: method.name.replace("_", ".")
    
    override val description: String = method.getAnnotation(Tool::class.java)?.value?.firstOrNull().orEmpty()
    
    override val category: ToolCategory = ToolCategory.PHONE

    override fun execute(parameters: Map<String, Any>): ToolResult {
        return try {
            val args = parameters.values.toTypedArray()
            val result = method.invoke(tool, *args)
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
