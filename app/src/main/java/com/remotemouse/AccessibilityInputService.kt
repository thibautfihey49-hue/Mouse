package com.remotemouse

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class AccessibilityInputService : AccessibilityService() {
    override fun onCreate() {
        super.onCreate()
        InputDispatcher.attachService(this)
        Log.d("WiFiMouse", "✅ Service Accessibility DÉMARRÉ")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
