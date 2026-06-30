package com.android.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AccessibilityService untuk input injection tanpa Shizuku.
 * - Tap/Swipe via dispatchGesture() → ~5–20ms latency (vs 100–300ms Shizuku shell)
 * - Hardware keys via performGlobalAction() (Back, Home, Recents)
 * - Persist across reboots (enabled once in Settings > Accessibility)
 */
class ControlAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: ControlAccessibilityService? = null
            private set

        fun isAvailable(): Boolean = instance != null

        /**
         * Inject tap. Blocks caller thread max 2s. Returns true on success.
         */
        fun dispatchTapSync(x: Float, y: Float): Boolean {
            val svc = instance ?: return false
            val latch = CountDownLatch(1)
            var ok = false
            try {
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                svc.dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription) { ok = true; latch.countDown() }
                    override fun onCancelled(g: GestureDescription) { latch.countDown() }
                }, null)
            } catch (_: Exception) { latch.countDown() }
            latch.await(2, TimeUnit.SECONDS)
            return ok
        }

        /**
         * Inject swipe. Blocks caller thread max (durationMs + 2000)ms.
         */
        fun dispatchSwipeSync(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
            val svc = instance ?: return false
            val latch = CountDownLatch(1)
            var ok = false
            try {
                val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
                val stroke = GestureDescription.StrokeDescription(
                    path, 0L, durationMs.coerceIn(50L, 3000L)
                )
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                svc.dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription) { ok = true; latch.countDown() }
                    override fun onCancelled(g: GestureDescription) { latch.countDown() }
                }, null)
            } catch (_: Exception) { latch.countDown() }
            latch.await(durationMs + 2000, TimeUnit.MILLISECONDS)
            return ok
        }

        /**
         * Hardware key via performGlobalAction (Back/Home/Recents/Notifications).
         * Returns true if handled, false if caller should fall back to shell.
         */
        fun dispatchGlobalAction(keyCode: String): Boolean {
            val svc = instance ?: return false
            val action = when (keyCode) {
                "KEYCODE_BACK"        -> GLOBAL_ACTION_BACK
                "KEYCODE_HOME"        -> GLOBAL_ACTION_HOME
                "KEYCODE_APP_SWITCH"  -> GLOBAL_ACTION_RECENTS
                "KEYCODE_NOTIFICATION" -> GLOBAL_ACTION_NOTIFICATIONS
                else                  -> return false
            }
            return try { svc.performGlobalAction(action) } catch (_: Exception) { false }
        }

        /**
         * Long press via dispatchGesture with long duration stroke.
         */
        fun dispatchLongPressSync(x: Float, y: Float): Boolean {
            val svc = instance ?: return false
            val latch = CountDownLatch(1)
            var ok = false
            try {
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0L, 800L)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                svc.dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription) { ok = true; latch.countDown() }
                    override fun onCancelled(g: GestureDescription) { latch.countDown() }
                }, null)
            } catch (_: Exception) { latch.countDown() }
            latch.await(3, TimeUnit.SECONDS)
            return ok
        }
    }

    override fun onServiceConnected() {
        instance = this
        android.util.Log.d("ControlA11y", "✅ ControlAccessibilityService connected — no-Shizuku input ready")
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
