package com.remotemouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log

object InputDispatcher {
    var service: AccessibilityInputService? = null
    private var posX = -1f
    private var posY = -1f
    private val TAG = "MouseInput"
    
    fun initPosition(svc: AccessibilityService) {
        val dm = svc.resources.displayMetrics
        posX = dm.widthPixels / 2f
        posY = dm.heightPixels / 2f
        Log.d(TAG, "Position initiale: $posX x $posY")
    }

    fun handleCommand(cmd: String) {
        Log.d(TAG, "Reçu: $cmd")
        val svc = service ?: run {
            Log.e(TAG, "❌ Service NULL ! Active l'accessibilité !")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "❌ Besoin d'Android 7.0+")
            return
        }
        if (posX < 0) initPosition(svc)
        
        val parts = cmd.split("|")
        when (parts[0]) {
            "MOVE" -> {
                val dx = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                val dy = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                move(svc, dx, dy)
            }
            "CLICK" -> click(svc)
        }
    }

    private fun move(svc: AccessibilityService, dx: Float, dy: Float) {
        val dm = svc.resources.displayMetrics
        val speed = 2.8f
        posX = (posX + dx * speed).coerceIn(50f, dm.widthPixels - 50f)
        posY = (posY + dy * speed).coerceIn(50f, dm.heightPixels - 50f)
        
        val path = Path().apply { moveTo(posX, posY); lineTo(posX, posY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 10))
            .build()
        svc.dispatchGesture(gesture, null, null)
        Log.d(TAG, "Déplacement: $dx,$dy → $posX,$posY")
    }

    private fun click(svc: AccessibilityService) {
        val path = Path().apply { moveTo(posX, posY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 30))
            .build()
        svc.dispatchGesture(gesture, null, null)
        Log.d(TAG, "Clic à: $posX,$posY")
    }
}
