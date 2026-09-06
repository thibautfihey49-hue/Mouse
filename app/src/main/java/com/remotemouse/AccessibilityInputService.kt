package com.remotemouse

import android.accessibilityservice.AccessibilityService
import android.util.Log

class AccessibilityInputService : AccessibilityService() {
    override fun onCreate() {
        super.onCreate()
        InputDispatcher.setService(this)
        Log.d("BluetoothMouse", "Service démarré")
    }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
