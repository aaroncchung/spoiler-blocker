package io.github.aaroncchung.spoilerblocker.probe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * E5: takes a screenshot of one window with takeScreenshotOfWindow (Android
 * 14) and checks whether the probe's own box is in the picture.
 *
 * A screenshot of the whole display is taken straight afterwards as a control.
 * The box should be in that one. If the control cannot find the box, the
 * check itself is not working and the first answer means nothing.
 */
class ScreenshotProbe(private val service: AccessibilityService) {

    /** What is on screen when the screenshot is asked for. Rectangles are in pixels. */
    class Scene(
        val windowId: Int,
        val mechanism: OverlayMechanism,
        /** The box, measured from the window's top left corner. */
        val boxInWindow: Rect,
        /** The box, measured from the screen's top left corner. */
        val boxOnScreen: Rect,
        /** The app window's width, or 0 if it could not be found out. */
        val windowWidth: Int,
        val screenWidth: Int,
    )

    private val handler = Handler(Looper.getMainLooper())

    fun takeAndCheck(scene: Scene) {
        val stamp = LocalDateTime.now().format(FILE_STAMP)
        val requested = SystemClock.uptimeMillis()
        service.takeScreenshotOfWindow(scene.windowId, service.mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val took = SystemClock.uptimeMillis() - requested
                val windowVerdict = analyse(
                    "window", screenshot, took, scene.boxInWindow, scene.windowWidth,
                    scene.mechanism, "$stamp-${scene.mechanism.id}-window.png",
                )
                // The display screenshot has its own rate limit, but leave a gap anyway.
                handler.postDelayed({ takeControl(scene, stamp, windowVerdict) }, CONTROL_DELAY_MS)
            }

            override fun onFailure(errorCode: Int) {
                ProbeLog.log("E5", "window screenshot FAILED: ${errorName(errorCode)} after ${SystemClock.uptimeMillis() - requested}ms")
            }
        })
    }

    private fun takeControl(scene: Scene, stamp: String, windowVerdict: BoxPixelCheck.Verdict?) {
        val requested = SystemClock.uptimeMillis()
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val took = SystemClock.uptimeMillis() - requested
                val controlVerdict = analyse(
                    "display", screenshot, took, scene.boxOnScreen, scene.screenWidth,
                    scene.mechanism, "$stamp-${scene.mechanism.id}-display.png",
                )
                val conclusion = when {
                    controlVerdict != BoxPixelCheck.Verdict.BOX_PRESENT ->
                        "NO ANSWER: the whole-display control did not find the box, so the check is not working. " +
                            "Was the box on screen? Look at the saved images."
                    windowVerdict == BoxPixelCheck.Verdict.BOX_ABSENT ->
                        "the per-window screenshot LEAVES OUT the box"
                    windowVerdict == BoxPixelCheck.Verdict.BOX_PRESENT ->
                        "the per-window screenshot INCLUDES the box"
                    else -> "UNCLEAR: look at the saved images"
                }
                ProbeLog.log("E5", "result mech=${scene.mechanism.id}: $conclusion")
            }

            override fun onFailure(errorCode: Int) {
                ProbeLog.log("E5", "display screenshot (control) FAILED: ${errorName(errorCode)}")
            }
        })
    }

    /**
     * Looks for the box colour where the box should be, logs the outcome and
     * saves the image. [box] is in the coordinates of the thing photographed
     * and [fullWidth] is that thing's width, in case the image comes back at a
     * different scale. A [fullWidth] of 0 means "assume the same scale".
     */
    private fun analyse(
        kind: String,
        screenshot: ScreenshotResult,
        tookMillis: Long,
        box: Rect,
        fullWidth: Int,
        mechanism: OverlayMechanism,
        fileName: String,
    ): BoxPixelCheck.Verdict? {
        val copyStarted = SystemClock.uptimeMillis()
        // The screenshot arrives as a buffer in graphics memory. Its pixels
        // can only be read after copying it into an ordinary bitmap.
        val buffer = screenshot.hardwareBuffer
        val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
        buffer.close()
        if (bitmap == null) {
            ProbeLog.log("E5", "$kind screenshot: took=${tookMillis}ms but could not be read")
            return null
        }
        val copyMillis = SystemClock.uptimeMillis() - copyStarted

        val scale = if (fullWidth > 0) bitmap.width.toFloat() / fullWidth else 1f
        val region = Rect(
            (box.left * scale).toInt() + EDGE_INSET_PX,
            (box.top * scale).toInt() + EDGE_INSET_PX,
            (box.right * scale).toInt() - EDGE_INSET_PX,
            (box.bottom * scale).toInt() - EDGE_INSET_PX,
        )
        val onImage = region.intersect(0, 0, bitmap.width, bitmap.height)
        val verdict: BoxPixelCheck.Verdict?
        val outcome: String
        if (!onImage || region.isEmpty) {
            verdict = null
            outcome = "the box region $region is not inside the image"
        } else {
            val pixels = IntArray(region.width() * region.height())
            bitmap.getPixels(pixels, 0, region.width(), region.left, region.top, region.width(), region.height())
            val fraction = BoxPixelCheck.matchFraction(pixels, mechanism.colour)
            verdict = BoxPixelCheck.verdict(fraction)
            outcome = "boxRegion=${region.toShortString()} ${mechanism.colourName} pixels=${(fraction * 100).toInt()}% verdict=$verdict"
        }
        ProbeLog.log(
            "E5",
            "$kind screenshot: took=${tookMillis}ms copy=${copyMillis}ms size=${bitmap.width}x${bitmap.height} " +
                "$outcome file=$DIRECTORY/$fileName",
        )

        val directory = File(service.filesDir, DIRECTORY).apply { mkdirs() }
        ProbeLog.runInBackground {
            File(directory, fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        return verdict
    }

    /**
     * Finds the platform's rate limit by asking for two window screenshots a
     * known time apart, for a range of gaps. Takes about fifteen seconds.
     */
    fun rateLimitTest(windowId: Int) {
        ProbeLog.log("E5", "rate-test started: gaps ${RATE_TEST_GAPS_MS.joinToString()} ms")
        rateLimitStep(windowId, 0, mutableListOf(), mutableListOf(), mutableListOf())
    }

    private fun rateLimitStep(
        windowId: Int,
        index: Int,
        worked: MutableList<Long>,
        failed: MutableList<Long>,
        callTimes: MutableList<Long>,
    ) {
        if (index == RATE_TEST_GAPS_MS.size) {
            ProbeLog.log(
                "E5",
                "rate-test result: shortest gap that worked=${worked.minOrNull() ?: "none"}ms " +
                    "longest gap that failed=${failed.maxOrNull() ?: "none"}ms " +
                    "call time median=${if (callTimes.isEmpty()) "-" else LagStats.percentile(callTimes, 50)}ms " +
                    "max=${callTimes.maxOrNull() ?: "-"}ms n=${callTimes.size}",
            )
            return
        }
        val gap = RATE_TEST_GAPS_MS[index]
        // First shot: starts the platform's timer. Second shot: `gap` later.
        val firstRequested = SystemClock.uptimeMillis()
        shoot(windowId) { firstError, firstTook ->
            if (firstError == null) callTimes += firstTook
        }
        handler.postDelayed({
            val actualGap = SystemClock.uptimeMillis() - firstRequested
            shoot(windowId) { error, took ->
                if (error == null) {
                    worked += gap
                    callTimes += took
                    ProbeLog.log("E5", "rate-test gap=${gap}ms (actual ${actualGap}ms) -> ok, took=${took}ms")
                } else {
                    failed += gap
                    ProbeLog.log("E5", "rate-test gap=${gap}ms (actual ${actualGap}ms) -> ${errorName(error)}")
                }
                // Wait well past any limit before the next pair.
                handler.postDelayed({ rateLimitStep(windowId, index + 1, worked, failed, callTimes) }, RATE_TEST_REST_MS)
            }
        }, gap)
    }

    /** Takes a window screenshot and throws the image away. Reports the error code (null if none) and the time taken. */
    private fun shoot(windowId: Int, onDone: (errorCode: Int?, tookMillis: Long) -> Unit) {
        val requested = SystemClock.uptimeMillis()
        service.takeScreenshotOfWindow(windowId, service.mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                screenshot.hardwareBuffer.close()
                onDone(null, SystemClock.uptimeMillis() - requested)
            }

            override fun onFailure(errorCode: Int) {
                onDone(errorCode, SystemClock.uptimeMillis() - requested)
            }
        })
    }

    private fun errorName(code: Int): String = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "INTERNAL_ERROR"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESSIBILITY_ACCESS"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL_TIME_SHORT (asked again too soon)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "INVALID_WINDOW"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "SECURE_WINDOW (the app forbids screenshots)"
        else -> "error $code"
    }

    companion object {
        const val DIRECTORY = "screenshots"

        private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** Pixels ignored at each edge of the box, where it may be blended with what is behind it. */
        private const val EDGE_INSET_PX = 4
        private const val CONTROL_DELAY_MS = 500L
        private const val RATE_TEST_REST_MS = 1200L

        // AOSP's limit is "more than 333 ms since the last accepted request".
        // The gaps bracket that value so a different limit on One UI shows up.
        private val RATE_TEST_GAPS_MS = listOf(0L, 100L, 200L, 300L, 320L, 340L, 360L, 400L, 500L, 1000L)
    }
}
