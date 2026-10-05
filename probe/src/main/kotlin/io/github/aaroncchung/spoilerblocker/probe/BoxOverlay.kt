package io.github.aaroncchung.spoilerblocker.probe

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.WindowManager
import androidx.core.view.doOnAttach
import java.util.concurrent.Executor

/**
 * E2: the ways Android 14 and later offer an accessibility service to draw a
 * box over another app. Each one has its own colour, so a film or a screenshot
 * shows which was in use.
 */
enum class OverlayMechanism(val id: String, val colour: Int, val colourName: String, val summary: String) {
    WM_MOVE(
        "wm-move", 0xFFFF00FF.toInt(), "magenta",
        "A small overlay window the size of the box, moved with WindowManager.updateViewLayout.",
    ),
    WM_CANVAS(
        "wm-canvas", 0xFF00FFFF.toInt(), "cyan",
        "One full-screen overlay window. The box is redrawn inside it; the window never moves.",
    ),
    SC_DISPLAY(
        "sc-display", 0xFFFFFF00.toInt(), "yellow",
        "A SurfaceControl attached to the display, moved with a SurfaceControl.Transaction.",
    ),
    SC_WINDOW(
        "sc-window", 0xFF00FF00.toInt(), "green",
        "A SurfaceControl attached to the watched app's window, moved with a SurfaceControl.Transaction.",
    );

    /** The mechanism after this one, wrapping round. Used by the "next mechanism" trigger. */
    fun next(): OverlayMechanism = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromId(id: String?): OverlayMechanism? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Where the tracked item is. [screen] is in screen pixels. [window] is the
 * same rectangle measured from the top left of the item's own window, which is
 * what an overlay attached to that window needs.
 */
data class BoxTarget(val windowId: Int, val screen: Rect, val window: Rect)

/** One way of drawing the box. */
interface BoxOverlay {
    /**
     * True if the box only moves when this app draws a frame of its own. For
     * these, the time of the next frame is worth logging.
     */
    val drawsInOwnFrames: Boolean

    /** True if the mechanism can tell when the system compositor took a change. */
    val reportsCommit: Boolean

    /**
     * Puts the box on [target], creating the overlay on first use.
     *
     * If [reportsCommit] is true, [onCommitted] is called with the time the
     * compositor took the change (uptime milliseconds), on an arbitrary
     * thread. Otherwise it is never called.
     */
    fun moveTo(target: BoxTarget, onCommitted: (Long) -> Unit)

    fun hide()

    /** Removes the overlay for good. */
    fun destroy()
}

/** Creates the overlay for [mechanism]. Must be called on the main thread. */
fun createBoxOverlay(service: AccessibilityService, mechanism: OverlayMechanism): BoxOverlay = when (mechanism) {
    OverlayMechanism.WM_MOVE -> WindowMoveBox(service, mechanism.colour)
    OverlayMechanism.WM_CANVAS -> WindowCanvasBox(service, mechanism.colour)
    OverlayMechanism.SC_DISPLAY -> SurfaceBox(service, mechanism.colour, attachToWindow = false)
    OverlayMechanism.SC_WINDOW -> SurfaceBox(service, mechanism.colour, attachToWindow = true)
}

/**
 * Layout parameters for an overlay window that is positioned in raw screen
 * pixels and lets every touch through to the app underneath.
 *
 * TYPE_ACCESSIBILITY_OVERLAY is a window type only an accessibility service
 * may use. It needs no "display over other apps" permission, and Android
 * treats it as trusted, so touches pass through it to the app below.
 */
// Gravity.LEFT rather than START: x is a raw screen coordinate, which does not
// flip in right-to-left languages.
@SuppressLint("RtlHardcoded")
fun overlayLayoutParams(width: Int, height: Int, title: String): WindowManager.LayoutParams =
    WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        // Without these two, Android keeps the window clear of the status bar
        // and the camera cut-out, and x/y would no longer be screen pixels.
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        this.title = title
    }

/** Runs a callback on whatever thread delivers it, so its timestamp is not delayed by a busy main thread. */
private val directExecutor = Executor { it.run() }

/** Mechanism "wm-move": the window is the box. */
private class WindowMoveBox(service: AccessibilityService, colour: Int) : BoxOverlay {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val view = View(service).apply { setBackgroundColor(colour) }
    private val params = overlayLayoutParams(0, 0, "SB Probe box (wm-move)").apply {
        // By default Android slides a window to its new position over a few
        // hundred milliseconds. That would be measured as lag, so turn it off.
        setCanPlayMoveAnimation(false)
    }
    private var added = false

    // The new position is sent to the window manager during this app's next frame.
    override val drawsInOwnFrames = true

    // The window manager then moves the window in its own process, on its own
    // schedule. This app is not told when, so there is no commit time.
    override val reportsCommit = false

    override fun moveTo(target: BoxTarget, onCommitted: (Long) -> Unit) {
        params.x = target.screen.left
        params.y = target.screen.top
        params.width = target.screen.width()
        params.height = target.screen.height()
        if (added) {
            windowManager.updateViewLayout(view, params)
        } else {
            windowManager.addView(view, params)
            added = true
        }
    }

    override fun hide() {
        if (added) {
            windowManager.removeViewImmediate(view)
            added = false
        }
    }

    override fun destroy() = hide()
}

/** Mechanism "wm-canvas": a full-screen transparent window with the box painted inside it. */
private class WindowCanvasBox(service: AccessibilityService, colour: Int) : BoxOverlay {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val view = CanvasView(service, colour)
    private var added = false

    override val drawsInOwnFrames = true
    override val reportsCommit = true

    override fun moveTo(target: BoxTarget, onCommitted: (Long) -> Unit) {
        if (!added) {
            val params = overlayLayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                "SB Probe box (wm-canvas)",
            )
            windowManager.addView(view, params)
            added = true
        }
        view.box = Rect(target.screen)
        view.invalidate()
        // Ask to be told when the frame containing the redrawn box has been
        // taken by the compositor. The empty transaction rides along with
        // that frame. rootSurfaceControl is null until the window is attached.
        val transaction = SurfaceControl.Transaction()
            .addTransactionCommittedListener(directExecutor) { onCommitted(SystemClock.uptimeMillis()) }
        if (view.rootSurfaceControl?.applyTransactionOnDraw(transaction) != true) {
            transaction.close()
        }
    }

    override fun hide() {
        view.box = null
        view.invalidate()
    }

    override fun destroy() {
        if (added) {
            windowManager.removeViewImmediate(view)
            added = false
        }
    }

    private class CanvasView(context: Context, colour: Int) : View(context) {
        var box: Rect? = null
        private val paint = Paint().apply { color = colour }

        override fun onDraw(canvas: Canvas) {
            box?.let { canvas.drawRect(it, paint) }
        }
    }
}

/**
 * Mechanisms "sc-display" and "sc-window": the box is a surface handed to the
 * system with attachAccessibilityOverlayToDisplay or ...ToWindow (Android 14).
 *
 * The surface is the size of the screen and filled with the box colour. Moving
 * and resizing the box is then one SurfaceControl.Transaction that sets a
 * position and a crop. Nothing is redrawn and the window manager is not
 * involved, which is why this path may be the fastest.
 */
private class SurfaceBox(
    private val service: AccessibilityService,
    private val colour: Int,
    private val attachToWindow: Boolean,
) : BoxOverlay {
    private var host: SurfaceControlViewHost? = null
    private var surface: SurfaceControl? = null

    /** The window the surface is attached to, for "sc-window". */
    private var attachedWindowId: Int? = null
    private var attachedToDisplay = false

    // Nothing is drawn after the first frame, so this app's frames do not matter.
    override val drawsInOwnFrames = false
    override val reportsCommit = true

    private fun createSurface(): SurfaceControl? {
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val screen = service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        // Square, with the screen's longer side, so it is big enough whichever way the phone is held.
        val side = maxOf(screen.width(), screen.height())
        // SurfaceControlViewHost shows a View on a surface that is not a window.
        // It is the route the AccessibilityService documentation describes.
        val newHost = SurfaceControlViewHost(service, display, Binder())
        val view = View(service).apply { setBackgroundColor(colour) }
        // An empty touchable region means the surface never takes a touch.
        view.doOnAttach { it.rootSurfaceControl?.setTouchableRegion(Region()) }
        newHost.setView(view, side, side)
        host = newHost
        return newHost.surfacePackage?.surfaceControl
    }

    override fun moveTo(target: BoxTarget, onCommitted: (Long) -> Unit) {
        val sc = surface ?: createSurface().also { surface = it } ?: return
        // An overlay attached to a window is positioned in that window's coordinates.
        val box = if (attachToWindow) target.window else target.screen
        if (attachToWindow) {
            if (attachedWindowId != target.windowId) {
                service.attachAccessibilityOverlayToWindow(target.windowId, sc)
                attachedWindowId = target.windowId
            }
        } else if (!attachedToDisplay) {
            service.attachAccessibilityOverlayToDisplay(Display.DEFAULT_DISPLAY, sc)
            attachedToDisplay = true
        }
        SurfaceControl.Transaction()
            .setPosition(sc, box.left.toFloat(), box.top.toFloat())
            .setCrop(sc, Rect(0, 0, box.width(), box.height()))
            // Above the app's own content when attached to its window.
            .setLayer(sc, Int.MAX_VALUE)
            .setVisibility(sc, true)
            .addTransactionCommittedListener(directExecutor) { onCommitted(SystemClock.uptimeMillis()) }
            .apply()
    }

    override fun hide() {
        val sc = surface ?: return
        SurfaceControl.Transaction().setVisibility(sc, false).apply()
    }

    override fun destroy() {
        val sc = surface ?: return
        // Detaching is done by giving the surface no parent, as the platform documentation says.
        SurfaceControl.Transaction().reparent(sc, null).apply()
        host?.release()
        host = null
        surface = null
        attachedWindowId = null
        attachedToDisplay = false
    }
}
