package io.github.aaroncchung.spoilerblocker.probe

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import java.util.Locale

/**
 * A strip over the status bar that makes a slow-motion film readable.
 *
 * - The left third shows a running clock: the last five digits of the uptime
 *   clock in milliseconds, as "SS.mmm". The log stamps every line with the
 *   same clock (`up=...`), so a frame of film can be matched to a log line.
 * - The middle block lights up red when the probe is told a finger went down.
 * - The right block lights up blue when a scroll event arrives.
 *
 * The strip is drawn the same way a cover would be, so if the red block shows
 * up on film before the list moves, a cover would have been in time too.
 *
 * The clock is redrawn on every frame of the display. A number reaches the
 * screen one or two frames after it was drawn, so read differences between
 * two moments on film rather than single values.
 */
class FilmStrip(service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val view = StripView(service)
    private var shown = false

    // Choreographer calls doFrame once per display frame, for as long as the
    // callback keeps asking for the next one.
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!shown) return
            view.invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun show() {
        if (shown) return
        // Exactly as tall as the status bar, and on top of it. Any lower and it
        // would hide the notification banner that E4 is trying to film.
        val statusBarHeight = windowManager.maximumWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
            .top
        val minimumHeight = (MIN_HEIGHT_DP * view.resources.displayMetrics.density).toInt()
        val params = overlayLayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            maxOf(statusBarHeight, minimumHeight),
            "SB Probe film strip",
        )
        windowManager.addView(view, params)
        shown = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun hide() {
        if (!shown) return
        shown = false
        windowManager.removeViewImmediate(view)
    }

    /** Lights the "touch" block for a moment. */
    fun markTouch() {
        view.touchLitUntil = SystemClock.uptimeMillis() + LIT_MS
    }

    /** Lights the "scroll event" block for a moment. */
    fun markScrollEvent() {
        view.scrollLitUntil = SystemClock.uptimeMillis() + LIT_MS
    }

    private class StripView(context: Context) : View(context) {
        var touchLitUntil = 0L
        var scrollLitUntil = 0L

        private val density = resources.displayMetrics.density
        private val block = Paint()
        private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            textSize = 20 * density
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 11 * density
        }

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            val w = width.toFloat()
            val h = height.toFloat()
            val third = w / 3
            val baseline = h / 2 + digits.textSize / 3

            // The clock is on the left because many phones, the S25 Ultra
            // among them, have the camera in the middle of the status bar.
            canvas.drawColor(Color.BLACK)
            val clock = String.format(Locale.ROOT, "%02d.%03d", (now / 1000) % 100, now % 1000)
            canvas.drawText(clock, third / 2, baseline, digits)

            block.color = if (now < touchLitUntil) Color.RED else Color.DKGRAY
            canvas.drawRect(third, 0f, 2 * third, h, block)
            // Left of the block's centre, to stay clear of that camera.
            canvas.drawText("TOUCH", third * 1.25f, baseline, label)

            block.color = if (now < scrollLitUntil) Color.BLUE else Color.DKGRAY
            canvas.drawRect(2 * third, 0f, w, h, block)
            canvas.drawText("SCROLL EVENT", w - third / 2, baseline, label)
        }
    }

    private companion object {
        const val MIN_HEIGHT_DP = 28
        const val LIT_MS = 300L
    }
}
