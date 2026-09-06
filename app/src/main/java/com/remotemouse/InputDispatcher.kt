package com.remotemouse

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.roundToInt

object InputDispatcher {
    private const val TAG = "InputDispatcher"
    private var service: AccessibilityService? = null
    
    fun attachService(svc: AccessibilityService) {
        service = svc
        Log.d(TAG, "✅ Service Accessibility attaché")
    }
    
    fun handleCommand(cmd: String) {
        val parts = cmd.split("|")
        if (parts.isEmpty()) return
        
        when (parts[0]) {
            "MOVE" -> {
                if (parts.size >= 3) {
                    val dx = parts[1].toFloatOrNull() ?: 0f
                    val dy = parts[2].toFloatOrNull() ?: 0f
                    performMove(dx, dy)
                }
            }
            "CLICK" -> performClick()
            "SCROLL_UP" -> performScroll(3)
            "SCROLL_DOWN" -> performScroll(-3)
        }
    }
    
    private fun performMove(dx: Float, dy: Float) {
        val svc = service ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val display = svc.display
                val centerX = display.width / 2f
                val centerY = display.height / 2f
                
                val path = Path()
                path.moveTo(centerX, centerY)
                path.relativeMoveTo(dx, dy)
                
                svc.dispatchGesture(
                    android.accessibilityservice.GestureDescription.Builder()
                        .setStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 1))
                        .build(),
                    null, null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur mouvement: ${e.message}")
        }
    }
    
    private fun performClick() {
        val svc = service ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val display = svc.display
                val centerX = display.width / 2f
                val centerY = display.height / 2f
                
                svc.dispatchGesture(
                    android.accessibilityservice.GestureDescription.Builder()
                        .setStroke(android.accessibilityservice.GestureDescription.StrokeDescription(
                            Path().apply { moveTo(centerX, centerY) }, 0, 10
                        ))
                        .build(),
                    null, null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur clic: ${e.message}")
        }
    }
    
    private fun performScroll(amount: Int) {
        Log.d(TAG, "Défilement: $amount")
    }
}
