package com.webo.tester

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class TesterService : AccessibilityService() {
    companion object {
        @Volatile var inst: TesterService? = null
    }

    override fun onServiceConnected() {
        inst = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Explorer.cur?.stop()
        inst = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun start(cfg: Cfg) {
        Explorer(this, cfg).begin()
    }
}
