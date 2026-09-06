package com.remotemouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
import kotlin.math.roundToInt

object InputDispatcher {
    private const val TAG = "InputDispatcher"
    private var service: AccessibilityInputService? = null
    
    fun attachService(svc: AccessibilityInputService) {
        service = svc
        Log.d(TAG, "✅ Service Accessibility attaché")
    }
    
    fun handleCommand(cmd: String) {
        val parts = cmd.split("|")
        if (parts.isEmpty()) return
        
        Log.d(TAG, "📥 Commande: $cmd")
        
        when (parts[0]) {
            "MOVE" -> {
                if (parts.size >= 3) {
                    val dx = parts[1].toFloatOrNull() ?: 0f
                    val dy = parts[2].toFloatOrNull() ?: 0f
                    performMove(dx, dy)
                }
            }
            "CLICK" -> performClick()
        }
    }
    
    private fun performMove(dx: Float, dy: Float) {
        val svc = service ?: run {
            Log.e(TAG, "❌ Service null")
            return
        }
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "❌ Android 7.0+ requis")
            return
        }
        
        try {
            val display: Display? = svc.display
            val centerX = (display?.width ?: 1080) / 2f
            val centerY = (display?.height ?: 2340) / 2f
            
            val path = Path()
            path.moveTo(centerX, centerY)
            path.lineTo(centerX + dx, centerY + dy)
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 10))
                .build()
            
            svc.dispatchGesture(gesture, null, null)
            Log.d(TAG, "👆 Mouvement: dx=$dx, dy=$dy")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur mouvement: ${e.message}")
        }
    }
    
    private fun performClick() {
        val svc = service ?: run {
            Log.e(TAG, "❌ Service null")
            return
        }
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "❌ Android 7.0+ requis")
            return
        }
        
        try {
            val display: Display? = svc.display
            val centerX = (display?.width ?: 1080) / 2f
            val centerY = (display?.height ?: 2340) / 2f
            
            val path = Path()
            path.moveTo(centerX, centerY)
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 30))
                .build()
            
            svc.dispatchGesture(gesture, null, null)
            Log.d(TAG, "👆 Clic à: $centerX,$centerY")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur clic: ${e.message}")
        }
    }
}
