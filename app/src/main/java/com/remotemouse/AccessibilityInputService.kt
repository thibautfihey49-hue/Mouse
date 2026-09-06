package com.remotemouse

import android.accessibilityservice.AccessibilityService
import android.util.Log

class AccessibilityInputService : AccessibilityService() {
    private val TAG = "MouseService"
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ Service Accessibilité DÉMARRÉ")
        InputDispatcher.service = this
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "✅ Service connecté")
        InputDispatcher.service = this
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "❌ Service arrêté")
    }
}
