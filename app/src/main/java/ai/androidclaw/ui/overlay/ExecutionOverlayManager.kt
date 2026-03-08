package ai.androidclaw.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.CountDownLatch

class ExecutionOverlayManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: TextView? = null
    private var shouldDisplay = false
    private var hiddenByCaptureCount = 0
    private var lastMessage: String = ""
    private var lastThinking: String = ""
    private var lastTool: String = ""
    private var currentStatus: String = ""

    fun show(message: String) {
        runOnMainSync {
            if (!canShowOverlay()) return@runOnMainSync
            ensureOverlayView()
            shouldDisplay = true
            lastMessage = message
            applyOverlayVisibility()
            overlayView?.text = message
        }
    }

    fun showThinking(step: String) {
        lastThinking = compactArgs(step, 180)
        if (currentStatus.isBlank()) {
            currentStatus = "思考中"
        }
        renderCombined()
    }

    fun showToolExecution(toolName: String, args: String) {
        val compactArgs = compactArgs(args, 200)
        lastTool = if (compactArgs.isBlank()) {
            toolName
        } else {
            "$toolName($compactArgs)"
        }
        currentStatus = "执行工具中"
        renderCombined()
    }

    fun showCurrentStatus(status: String) {
        currentStatus = compactArgs(status, 120)
        renderCombined()
    }

    private fun compactArgs(args: String, maxLen: Int): String {
        val singleLine = args.replace("\n", " ").replace("\\s+".toRegex(), " ").trim()
        if (singleLine.length <= maxLen) return singleLine
        return singleLine.take(maxLen) + "..."
    }

    private fun renderCombined() {
        val text = buildString {
            appendLine("当前: ${if (currentStatus.isBlank()) "待命" else currentStatus}")
            if (lastTool.isNotBlank()) appendLine("上次工具: $lastTool")
            if (lastThinking.isNotBlank()) appendLine("上次思考: $lastThinking")
        }.trim()
        show(text)
    }

    fun update(message: String) {
        showCurrentStatus(message)
    }

    fun hide() {
        runOnMainSync {
            shouldDisplay = false
            hiddenByCaptureCount = 0
            overlayView?.let {
                runCatching { windowManager.removeView(it) }
            }
            overlayView = null
        }
    }

    fun <T> runWithCaptureHidden(block: () -> T): T {
        runOnMainSync {
            if (!shouldDisplay || overlayView == null) return@runOnMainSync
            hiddenByCaptureCount += 1
            applyOverlayVisibility()
        }

        return try {
            block()
        } finally {
            runOnMainSync {
                if (hiddenByCaptureCount > 0) {
                    hiddenByCaptureCount -= 1
                }
                if (shouldDisplay && overlayView != null) {
                    overlayView?.text = lastMessage
                    applyOverlayVisibility()
                }
            }
        }
    }

    private fun ensureOverlayView() {
            if (overlayView == null) {
                overlayView = TextView(appContext).apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xCC000000.toInt())
                    textSize = 12f
                    maxWidth = (appContext.resources.displayMetrics.widthPixels * 0.75f).toInt()
                    setPadding(24, 16, 24, 16)
                }

                windowManager.addView(overlayView, buildLayoutParams())
            }
    }

    private fun applyOverlayVisibility() {
        overlayView?.visibility = if (shouldDisplay && hiddenByCaptureCount == 0) View.VISIBLE else View.GONE
    }

    private fun canShowOverlay(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext)
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 120
        }
    }

    private fun runOnMainSync(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    companion object {
        @Volatile
        private var instance: ExecutionOverlayManager? = null

        fun getInstance(context: Context): ExecutionOverlayManager {
            return instance ?: synchronized(this) {
                instance ?: ExecutionOverlayManager(context).also { instance = it }
            }
        }
    }
}
