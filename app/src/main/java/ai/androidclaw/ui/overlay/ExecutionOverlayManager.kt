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
    private var isVisible = false

    fun show(message: String) {
        runOnMainSync {
            if (!canShowOverlay()) return@runOnMainSync
            if (overlayView == null) {
                overlayView = TextView(appContext).apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xCC000000.toInt())
                    textSize = 12f
                    setPadding(24, 16, 24, 16)
                }

                windowManager.addView(overlayView, buildLayoutParams())
            }

            overlayView?.text = message
            overlayView?.visibility = View.VISIBLE
            isVisible = true
        }
    }

    fun update(message: String) {
        runOnMainSync {
            if (!isVisible) return@runOnMainSync
            overlayView?.text = message
        }
    }

    fun hide() {
        runOnMainSync {
            overlayView?.let {
                runCatching { windowManager.removeView(it) }
            }
            overlayView = null
            isVisible = false
        }
    }

    fun <T> runWithCaptureHidden(block: () -> T): T {
        val shouldRestore = runOnMainSyncWithResult {
            val visible = isVisible
            overlayView?.visibility = View.GONE
            isVisible = false
            visible
        }

        return try {
            block()
        } finally {
            if (shouldRestore) {
                runOnMainSync {
                    overlayView?.visibility = View.VISIBLE
                    isVisible = true
                }
            }
        }
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

    private fun <T> runOnMainSyncWithResult(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        var result: T? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result = block()
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        @Suppress("UNCHECKED_CAST")
        return result as T
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
