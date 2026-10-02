package com.dilinkauto.client.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service holding the virtual-display binding for the car connection.
 *
 * Touch injection itself happens in the VD server process (running as shell UID)
 * over the dedicated input port — see ConnectionService.kt:357. The car-side
 * `injectTouch` path through AccessibilityService gestures is unused; only the
 * virtual-display metadata setters are live.
 *
 * The user must manually enable this service in:
 * Settings -> Accessibility -> DiLink Auto
 */
class InputInjectionService : AccessibilityService() {

    private val displayMetrics by lazy { resources.displayMetrics }

    // Virtual display targeting — set by ConnectionService when VD is created
    @Volatile private var virtualDisplayId: Int = -1
    @Volatile private var vdWidth: Int = 0
    @Volatile private var vdHeight: Int = 0

    fun setVirtualDisplay(displayId: Int, width: Int, height: Int) {
        virtualDisplayId = displayId
        vdWidth = width
        vdHeight = height
        Log.i(TAG, "Virtual display set: id=$displayId ${width}x${height}")
    }

    fun clearVirtualDisplay() {
        virtualDisplayId = -1
        vdWidth = 0
        vdHeight = 0
        Log.i(TAG, "Virtual display cleared")
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "InputInjectionService"

        @Volatile
        var instance: InputInjectionService? = null
            private set
    }
}
