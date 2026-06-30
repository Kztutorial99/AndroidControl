package com.android.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Remote Control v2 — Pure AccessibilityService, zero Shizuku dependency.
 *
 * Capabilities:
 *  - Tap / Long Press / Swipe via dispatchGesture()
 *  - Hardware keys: Back, Home, Recents, Notifications, Vol+, Vol-
 *  - Text input via clipboard paste (works in any EditText)
 *  - DPAD keys via performGlobalAction
 */
class ControlAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: ControlAccessibilityService? = null
            private set

        fun isAvailable(): Boolean = instance != null

        fun dispatchTapSync(x: Float, y: Float): Boolean {
            val svc = instance ?: return false
            val latch = CountDownLatch(1)
            var ok = false
            return try {
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                svc.dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription) { ok = true; latch.countDown() }
                    override fun onCancelled(g: GestureDescription) { latch.countDown() }
                }, null)
                latch.await(2, TimeUnit.SECONDS)
                ok
            } catch (_: Exception) { false }
        }

        fun dispatchLongPressSync(x: Float, y: Float, durationMs: Long = 800L): Boolean {
            val svc = instance ?: return false
            val latch = CountDownLatch(1)
            var ok = false
            return try {
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(
                    path, 0L, durationMs.coerceIn(300L, 3000L)
                )
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                svc.dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription) { ok = true; latch.countDown() }
                    override fun onCancelled(g: GestureDescription) { latch.countDown() }
                }, null)
                latch.await(durationMs + 2000, TimeUnit.MILLISECONDS)
                ok
            } catch (_: Exception) { false }
        }

        fun dispatchSwipeSync(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
            val svc = instance ?: return false
            val latch = CountDownLatch(1)
            var ok = false
            return try {
                val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
                val stroke = GestureDescription.StrokeDescription(
                    path, 0L, durationMs.coerceIn(50L, 3000L)
                )
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                svc.dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription) { ok = true; latch.countDown() }
                    override fun onCancelled(g: GestureDescription) { latch.countDown() }
                }, null)
                latch.await(durationMs + 2000, TimeUnit.MILLISECONDS)
                ok
            } catch (_: Exception) { false }
        }

        /**
         * Hardware / nav key handler.
         * Returns true if handled natively, false = caller should use shell fallback.
         */
        fun dispatchKey(keyCode: String): Boolean {
            val svc = instance ?: return false
            return try {
                when (keyCode) {
                    "KEYCODE_BACK"         -> svc.performGlobalAction(GLOBAL_ACTION_BACK)
                    "KEYCODE_HOME"         -> svc.performGlobalAction(GLOBAL_ACTION_HOME)
                    "KEYCODE_APP_SWITCH"   -> svc.performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "KEYCODE_NOTIFICATION" -> svc.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                    // Vol / Power — Accessibility cannot inject these; return false → shell fallback
                    else -> false
                }
            } catch (_: Exception) { false }
        }

        /**
         * Type text into the currently focused EditText via clipboard paste.
         * Much faster and more reliable than character-by-character injection.
         */
        fun inputTextViaClipboard(context: Context, text: String): Boolean {
            val svc = instance ?: return false
            return try {
                // 1. Put text into clipboard
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("rc_text", text))
                Thread.sleep(80)

                // 2. Find focused node and paste
                val root = svc.rootInActiveWindow ?: return false
                val focused = findFocusedEditable(root)
                if (focused != null) {
                    focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    focused.recycle()
                    root.recycle()
                    return true
                }
                root.recycle()

                // 3. Fallback: paste via global action (Android 9+)
                svc.performGlobalAction(GLOBAL_ACTION_PASTE)
                true
            } catch (_: Exception) { false }
        }

        private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (root.isFocused && root.isEditable) return root
            for (i in 0 until root.childCount) {
                val child = root.getChild(i) ?: continue
                val found = findFocusedEditable(child)
                if (found != null) { if (found !== child) child.recycle(); return found }
                child.recycle()
            }
            return null
        }
    }

    override fun onServiceConnected() {
        instance = this
        android.util.Log.d("ControlA11y", "✅ ControlAccessibilityService v2 connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
