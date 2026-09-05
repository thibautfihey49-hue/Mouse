package com.remotemouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
object InputDispatcher {
    var service: AccessibilityInputService? = null
    private const val SCREEN_WIDTH = 1080f
    private const val SCREEN_HEIGHT = 2340f
    private var lastX = SCREEN_WIDTH / 2
    private var lastY = SCREEN_HEIGHT / 2

    fun dispatchCommand(command: String) {
        val parts = command.split("|")
        when (parts[0]) {
            "MOVE" -> {
                val dx = parts[1].toFloatOrNull() ?: 0f
                val dy = parts[2].toFloatOrNull() ?: 0f
                lastX = (lastX + dx * 2).coerceIn(50f, SCREEN_WIDTH - 50f)
                lastY = (lastY + dy * 2).coerceIn(50f, SCREEN_HEIGHT - 50f)
            }
            "CLICK" -> performClick()
            "RIGHT_CLICK" -> performClick()
        }
    }

    private fun performClick() {
        val path = Path().apply { moveTo(lastX, lastY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        service?.dispatchGesture(gesture, null, null)
    }

    class AccessibilityInputService : AccessibilityService() {
        override fun onCreate() { super.onCreate(); service = this }
        override fun onDestroy() { super.onDestroy(); if (service === this) service = null }
        override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
        override fun onInterrupt() {}
    }
}
