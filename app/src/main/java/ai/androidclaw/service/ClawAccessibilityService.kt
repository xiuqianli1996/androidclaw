package ai.androidclaw.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.TimeUnit

class ClawAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ClawAccessibilityService? = null
            private set

        private const val TAG = "ClawAccessibilityService"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Service destroyed")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    fun getCurrentWindowTitle(): String {
        val windows = windows
        for (window in windows) {
            val title = window.title
            if (!title.isNullOrEmpty()) {
                return title.toString()
            }
        }
        return "Unknown"
    }

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        val result = findNodeByTextRecursively(rootNode, text)
        return result
    }

    private fun findNodeByTextRecursively(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(text) == true) {
            return node
        }

        if (node.contentDescription?.toString()?.contains(text) == true) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByTextRecursively(child, text)
            if (result != null) {
                return result
            }
        }
        return null
    }

    fun findAndClick(text: String): Boolean {
        val node = findNodeByText(text) ?: return false
        return performClick(node)
    }

    fun findAndLongClick(text: String): Boolean {
        val node = findNodeByText(text) ?: return false
        return performLongClick(node)
    }

    fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.recycle()
                return result
            }
            val temp = parent
            parent = parent.parent
            temp.recycle()
        }
        return false
    }

    fun performLongClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isLongClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }

        var parent = node.parent
        while (parent != null) {
            if (parent.isLongClickable) {
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                parent.recycle()
                return result
            }
            val temp = parent
            parent = parent.parent
            temp.recycle()
        }
        return false
    }

    fun clickOnScreen(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun longClickOnScreen(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun scrollDown(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val result = rootNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        rootNode.recycle()
        return result
    }

    fun scrollUp(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val result = rootNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        rootNode.recycle()
        return result
    }

    fun openApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return true
        }
        return false
    }

    fun getScreenText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val text = StringBuilder()
        collectTextRecursively(rootNode, text)
        rootNode.recycle()
        return text.toString()
    }

    fun performAction(action: Int): Boolean {
        return when (action) {
            GLOBAL_ACTION_BACK,
            GLOBAL_ACTION_HOME,
            GLOBAL_ACTION_RECENTS -> performGlobalAction(action)
            else -> rootInActiveWindow?.performAction(action) ?: false
        }
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    private fun collectTextRecursively(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val text = node.text
        if (!text.isNullOrEmpty()) {
            sb.append(text).append("\n")
        }

        val contentDesc = node.contentDescription
        if (!contentDesc.isNullOrEmpty()) {
            sb.append("[").append(contentDesc).append("]\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextRecursively(child, sb)
        }
    }
}
