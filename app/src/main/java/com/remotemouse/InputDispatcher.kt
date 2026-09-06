package com.remotemouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build

object InputDispatcher {
    private var service: AccessibilityInputService? = null
    
    fun setService(svc: AccessibilityInputService) { service = svc }

    fun dispatchCommand(cmd: String) {
        val parts = cmd.split("|")
        if (parts.isEmpty()) return
        when (parts[0]) {
            "MOVE" -> performMove(
                parts.getOrNull(1)?.toFloatOrNull() ?: 0f,
                parts.getOrNull(2)?.toFloatOrNull() ?: 0f
            )
            "CLICK" -> performClick()
            "RIGHT_CLICK" -> performRightClick()
        }
    }

    private fun performMove(dx: Float, dy: Float) {
        val svc = service ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val dm = svc.resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val centerY = dm.heightPixels / 2f
        val targetX = (centerX + dx * 1.5f).coerceIn(50f, dm.widthPixels - 50f)
        val targetY = (centerY + dy * 1.5f).coerceIn(50f, dm.heightPixels - 50f)
        val path = Path().apply { moveTo(centerX, centerY); lineTo(targetX, targetY) }
        svc.dispatchGesture(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, 30)).build(), null, null)
    }

    private fun performClick() {
        val svc = service ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val dm = svc.resources.displayMetrics
        val x = dm.widthPixels / 2f
        val y = dm.heightPixels / 2f
        val path = Path().apply { moveTo(x, y) }
        svc.dispatchGesture(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, 10)).build(), null, null)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            svc.dispatchGesture(GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(path, 10, 10)).build(), null, null)
        }, 50)
    }

    private fun performRightClick() {
        val svc = service ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
        }
    }
}
