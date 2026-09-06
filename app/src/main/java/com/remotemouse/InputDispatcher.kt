package com.remotemouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log

object InputDispatcher {
    private var service: AccessibilityInputService? = null
    private val TAG = "InputDispatcher"
    
    fun setService(svc: AccessibilityInputService) {
        service = svc
        Log.d(TAG, "Service enregistré !")
    }

    fun dispatchCommand(cmd: String) {
        Log.d(TAG, "Commande reçue: $cmd")
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
            Log.e(TAG, "Service NULL ! Accessibilité activée ?")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Nécessite Android 7.0+")
            return
        }
        
        try {
            val dm = svc.resources.displayMetrics
            val centerX = dm.widthPixels / 2f
            val centerY = dm.heightPixels / 2f
            
            // Déplacement relatif — plus naturel
            val targetX = (centerX + dx * 3f).coerceIn(100f, dm.widthPixels - 100f)
            val targetY = (centerY + dy * 3f).coerceIn(100f, dm.heightPixels - 100f)
            
            val path = Path().apply {
                moveTo(centerX, centerY)
                lineTo(targetX, targetY)
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            
            svc.dispatchGesture(gesture, object : GestureDescription.OnGestureCompleteCallback {
                override fun onGestureComplete(gesture: GestureDescription, completed: Boolean) {
                    Log.d(TAG, "Déplacement: $completed")
                }
            }, null)
            
            Log.d(TAG, "Déplacement envoyé: dx=$dx, dy=$dy")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur déplacement: ${e.message}")
        }
    }

    private fun performClick() {
        val svc = service ?: run {
            Log.e(TAG, "Service NULL !")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        
        try {
            val dm = svc.resources.displayMetrics
            val x = dm.widthPixels / 2f
            val y = dm.heightPixels / 2f
            
            // Clic = toucher + relâcher
            val downPath = Path().apply { moveTo(x, y) }
            val downGesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(downPath, 0, 10))
                .build()
            
            svc.dispatchGesture(downGesture, object : GestureDescription.OnGestureCompleteCallback {
                override fun onGestureComplete(gesture: GestureDescription, completed: Boolean) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val upPath = Path().apply { moveTo(x, y) }
                        val upGesture = GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(upPath, 0, 10))
                            .build()
                        svc.dispatchGesture(upGesture, null, null)
                    }, 50)
                }
            }, null)
            
            Log.d(TAG, "Clic envoyé")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur clic: ${e.message}")
        }
    }

    private fun performRightClick() {
        val svc = service ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            Log.d(TAG, "Clic droit = affichage applications récentes")
        }
    }
}
