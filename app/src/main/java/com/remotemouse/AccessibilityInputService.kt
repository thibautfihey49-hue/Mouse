package com.remotemouse

import android.accessibilityservice.AccessibilityService
import android.util.Log

class AccessibilityInputService : AccessibilityService() {
    private val TAG = "AccessService"
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ SERVICE D'ACCESSIBILITÉ DÉMARRÉ !")
        InputDispatcher.setService(this)
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "✅ Service connecté à l'application")
        InputDispatcher.setService(this)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}

    override fun onInterrupt() {}
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "❌ Service arrêté")
    }
}
