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
    
    fun attachService(svc: AccessibilityInputService) {
        service = svc
        Log.d(TAG, "✅ Service attaché — TENTATIVE DE RÉCUPÉRATION ÉCRAN")
        // Récupérer taille écran réelle
        val wm = svc.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val realSize = android.graphics.Point()
        display.getRealSize(realSize)
        displayWidth = realSize.x.toFloat()
        displayHeight = realSize.y.toFloat()
        currentX = displayWidth / 2f
        currentY = displayHeight / 2f
        Log.d(TAG, "📐 Taille écran: ${displayWidth}x${displayHeight}")
    }
    
    fun handleCommand(cmd: String) {
        Log.d(TAG, "📥 COMMANDE REÇUE: $cmd")
        val parts = cmd.split("|")
        if (parts.isEmpty()) return
        
        when (parts[0]) {
            "MOVE" -> {
                if (parts.size >= 3) {
                    val dx = parts[1].toFloatOrNull() ?: 0f
                    val dy = parts[2].toFloatOrNull() ?: 0f
                    Log.d(TAG, "👉 MOVE: dx=$dx, dy=$dy")
                    performMove(dx, dy)
                }
            }
            "CLICK" -> {
                Log.d(TAG, "👉 CLIC DEMANDÉ")
                performClick()
            }
        }
    }
    
    private fun performMove(dx: Float, dy: Float) {
        val svc = service ?: run {
            Log.e(TAG, "❌ Service NULL — Accessibilité ACTIVÉE ?")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "❌ Android N+ requis")
            return
        }
        
        // Déplacer le curseur (pas un geste continu mais un point)
        currentX = (currentX + dx).coerceIn(50f, displayWidth - 50f)
        currentY = (currentY + dy).coerceIn(50f, displayHeight - 50f)
        
        Log.d(TAG, "📍 Nouvelle position: ($currentX, $currentY)")
        
        // Geste de déplacement instantané
        val path = Path()
        path.moveTo(currentX, currentY)
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
            .build()
        
        svc.dispatchGesture(gesture, object : GestureDescription.OnGestureCompleteCallback {
            override fun onGestureComplete(p0: GestureDescription?, p1: Boolean) {
                Log.d(TAG, p1.let { "✅ Geste déplacement RÉUSSI" } ?: "❌ Geste déplacement ÉCHOUÉ")
            }
        }, null)
    }
    
    private fun performClick() {
        val svc = service ?: run {
            Log.e(TAG, "❌ Service NULL — Accessibilité ACTIVÉE ?")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "❌ Android N+ requis")
            return
        }
        
        Log.d(TAG, "👆 CLIC à ($currentX, $currentY)")
        
        val path = Path()
        path.moveTo(currentX, currentY)
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        
        svc.dispatchGesture(gesture, object : GestureDescription.OnGestureCompleteCallback {
            override fun onGestureComplete(p0: GestureDescription?, success: Boolean) {
                Log.d(TAG, if (success) "✅ CLIC RÉUSSI" else "❌ CLIC ÉCHOUÉ")
            }
        }, null)
    }
}
