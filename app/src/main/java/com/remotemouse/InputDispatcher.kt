package com.remotemouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import kotlin.math.abs

object InputDispatcher {
    private var service: AccessibilityInputService? = null
    private var currentX = -1f
    private var currentY = -1f
    private val TAG = "InputDispatcher"
    
    fun setService(svc: AccessibilityInputService) {
        service = svc
        Log.d(TAG, "Service enregistré !")
        
        svc.resources.displayMetrics.let { dm ->
            currentX = dm.widthPixels / 2f
            currentY = dm.heightPixels / 2f
        }
        Log.d(TAG, "Position initiale: X=$currentX, Y=$currentY")
    }

    fun dispatchCommand(cmd: String) {
        Log.d(TAG, "→ REÇU: $cmd")
        val parts = cmd.split("|")
        if (parts.isEmpty()) return
        
        when (parts[0]) {
            "MOVE" -> {
                val dx = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                val dy = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                performMove(dx, dy)
            }
            "CLICK" -> performClick()
            "RIGHT_CLICK" -> performRightClick()
        }
    }

    private fun performMove(dx: Float, dy: Float) {
        val svc = service ?: run {
            Log.e(TAG, "❌ Service NULL ! Active l'accessibilité !")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "❌ Nécessite Android 7.0+")
            return
        }
        
        try {
            val dm = svc.resources.displayMetrics
            val speed = 2.5f
            
            currentX = (currentX + dx * speed).coerceIn(30f, dm.widthPixels - 30f)
            currentY = (currentY + dy * speed).coerceIn(30f, dm.heightPixels - 30f)
            
            val path = Path().apply {
                moveTo(currentX - dx * speed * 0.5f, currentY - dy * speed * 0.5f)
                lineTo(currentX, currentY)
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 15))
                .build()
            
            svc.dispatchGesture(gesture, null, null)
            Log.d(TAG, "✓ Déplacement: ($dx, $dy) → Nouv pos: ($currentX, $currentY)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur déplacement: ${e.message}")
        }
    }

    private fun performClick() {
        val svc = service ?: run {
            Log.e(TAG, "❌ Service NULL !")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        
        try {
            val path = Path().apply { moveTo(currentX, currentY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 20))
                .build()
            
            svc.dispatchGesture(gesture, null, null)
            Log.d(TAG, "✓ Clic à ($currentX, $currentY)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur clic: ${e.message}")
        }
    }

    private fun performRightClick() {
        val svc = service ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            Log.d(TAG, "✓ Clic droit → Applications récentes")
        }
    }
}
