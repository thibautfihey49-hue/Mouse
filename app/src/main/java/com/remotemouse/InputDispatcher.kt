package com.remotemouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.WindowManager

object InputDispatcher {
    private const val TAG = "InputDispatcher"
    private var service: AccessibilityInputService? = null
    private var displayWidth = 1080f
    private var displayHeight = 2340f
    private var currentX = 540f
    private var currentY = 1170f
    private val moveSpeed = 1.2f
    
    fun attachService(svc: AccessibilityInputService) {
        service = svc
        Log.d(TAG, "✅ Service attaché")
        val wm = svc.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val realSize = android.graphics.Point()
        display.getRealSize(realSize)
        displayWidth = realSize.x.toFloat()
        displayHeight = realSize.y.toFloat()
        currentX = displayWidth / 2f
        currentY = displayHeight / 2f
        Log.d(TAG, "📐 Écran: ${displayWidth}x${displayHeight}")
    }
    
    fun handleCommand(cmd: String) {
        Log.d(TAG, "📥 Commande: $cmd")
        val parts = cmd.split("|")
        if (parts.isEmpty()) return
        
        when (parts[0]) {
            "MOVE" -> {
                if (parts.size >= 3) {
                    val dx = parts[1].toFloatOrNull() ?: 0f
                    val dy = parts[2].toFloatOrNull() ?: 0f
                    performMove(dx * moveSpeed, dy * moveSpeed)
                }
            }
            "CLICK" -> performClick()
        }
    }
    
    private fun performMove(dx: Float, dy: Float) {
        val svc = service ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        
        currentX = (currentX + dx).coerceIn(30f, displayWidth - 30f)
        currentY = (currentY + dy).coerceIn(30f, displayHeight - 30f)
        
        val path = Path()
        path.moveTo(currentX, currentY)
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
            .build()
        
        svc.dispatchGesture(gesture, null, null)
    }
    
    private fun performClick() {
        val svc = service ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        
        val path = Path()
        path.moveTo(currentX, currentY)
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
            .build()
        
        svc.dispatchGesture(gesture, null, null)
    }
}
