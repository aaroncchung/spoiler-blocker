package io.github.aaroncchung.spoilerblocker.probe

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * E3: learns that a finger has touched the screen without taking the touch.
 *
 * It adds a one-pixel overlay window with FLAG_WATCH_OUTSIDE_TOUCH. Android
 * then sends that window a single ACTION_OUTSIDE event for the first finger of
 * every gesture that lands anywhere else, which is everywhere. The gesture
 * itself still goes to the app underneath. Later fingers, moves and the lift
 * are not reported.
 *
 * [onTouchDown] receives the time the finger went down, as stamped by the
 * touch screen driver, and the time this app was told. Both are uptime
 * milliseconds.
 */
class OutsideTouchWatcher(
    service: AccessibilityService,
    private val onTouchDown: (downTime: Long, received: Long) -> Unit,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var added = false

    private val view = object : View(service) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onTouchDown(event.eventTime, SystemClock.uptimeMillis())
            }
            return false
        }
    }

    fun start() {
        if (added) return
        val params = overlayLayoutParams(1, 1, "SB Probe touch watcher").apply {
            // Unlike the box, this window must be able to receive input, or it
            // would not be sent ACTION_OUTSIDE. It takes up one pixel in the
            // top left corner. NOT_TOUCH_MODAL lets touches elsewhere through.
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        windowManager.addView(view, params)
        added = true
    }

    fun stop() {
        if (!added) return
        windowManager.removeViewImmediate(view)
        added = false
    }
}
