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
import androidx.core.graphics.scale
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * E5: takes a screenshot of one window with takeScreenshotOfWindow (Android
 * 14) and checks whether the probe's own box is in the picture.
 *
 * "The box is not there" is only an answer if the picture really shows the
 * app. Two things guard that:
 *
 * - A screenshot of the whole display is taken straight afterwards as a
 *   control. The box must be found in that one, or the check is not working.
 * - The window picture is tested for being one flat colour. A blank picture
 *   would not contain the box either, and would prove nothing.
 *
 * Neither replaces looking at the saved window picture.
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

    /** What was found in one screenshot. [verdict] is null if the box region was not inside the picture. */
    private class Finding(val verdict: BoxPixelCheck.Verdict?, val blank: Boolean)

    /** The two results of one pair in the rate test, as they come in. */
    private class PairInProgress(val nominalGap: Long) {
        var measuredGap = 0L
        var firstDone = false
        var firstError: Int? = null
        var secondDone = false
        var secondError: Int? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    fun takeAndCheck(scene: Scene) {
        val stamp = LocalDateTime.now().format(FILE_STAMP)
        val requested = SystemClock.uptimeMillis()
        service.takeScreenshotOfWindow(scene.windowId, service.mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val took = SystemClock.uptimeMillis() - requested
                val window = analyse(
                    "window", screenshot, took, scene.boxInWindow, scene.windowWidth,
                    scene.mechanism, "$stamp-${scene.mechanism.id}-window.png",
                )
                // The display screenshot has its own rate limit, but leave a gap anyway.
                handler.postDelayed({ takeControl(scene, stamp, window) }, CONTROL_DELAY_MS)
            }

            override fun onFailure(errorCode: Int) {
                ProbeLog.log("E5", "window screenshot FAILED: ${errorName(errorCode)} after ${SystemClock.uptimeMillis() - requested}ms")
            }
        })
    }

    private fun takeControl(scene: Scene, stamp: String, window: Finding?) {
        val requested = SystemClock.uptimeMillis()
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val took = SystemClock.uptimeMillis() - requested
                val control = analyse(
                    "display", screenshot, took, scene.boxOnScreen, scene.screenWidth,
                    scene.mechanism, "$stamp-${scene.mechanism.id}-display.png",
                )
                val conclusion = when {
                    control?.verdict != BoxPixelCheck.Verdict.BOX_PRESENT ->
                        "NO ANSWER: the whole-display control did not find the box, so the check is not working. " +
                            "Was the box on screen? Look at the saved images."
                    window == null -> "NO ANSWER: the window screenshot could not be read."
                    window.blank ->
                        "NO ANSWER: the window screenshot is one flat colour, so it does not show the app at all."
                    window.verdict == BoxPixelCheck.Verdict.BOX_ABSENT ->
                        "the per-window screenshot LEAVES OUT the box. " +
                            "Open the window image and confirm the post under the box can be seen in it."
                    window.verdict == BoxPixelCheck.Verdict.BOX_PRESENT ->
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
    ): Finding? {
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

        // The whole picture, shrunk so that it is quick to go through: how
        // much of it has the box colour, and is it one flat colour?
        val small = bitmap.scale(
            (bitmap.width / WHOLE_IMAGE_SHRINK).coerceAtLeast(1),
            (bitmap.height / WHOLE_IMAGE_SHRINK).coerceAtLeast(1),
            filter = false,
        )
        val all = IntArray(small.width * small.height)
        small.getPixels(all, 0, small.width, 0, 0, small.width, small.height)
        val wholeFraction = BoxPixelCheck.matchFraction(all, mechanism.colour)
        val blank = BoxPixelCheck.isOneFlatColour(all)
        if (small !== bitmap) small.recycle()

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
                "$outcome wholeImage: ${mechanism.colourName}=${(wholeFraction * 100).toInt()}% " +
                "${if (blank) "BLANK (one flat colour)" else "not blank"} file=$DIRECTORY/$fileName",
        )

        val directory = File(service.filesDir, DIRECTORY).apply { mkdirs() }
        ProbeLog.runInBackground {
            File(directory, fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        return Finding(verdict, blank)
    }

    /**
     * Finds the platform's rate limit by asking for two window screenshots a
     * known time apart, for a range of gaps. Takes about fifteen seconds.
     */
    fun rateLimitTest(windowId: Int) {
        ProbeLog.log("E5", "rate-test started: gaps ${RATE_TEST_GAPS_MS.joinToString()} ms")
        rateLimitStep(windowId, 0, mutableListOf(), mutableListOf())
    }

    private fun rateLimitStep(windowId: Int, index: Int, pairs: MutableList<RatePair>, callTimes: MutableList<Long>) {
        if (index == RATE_TEST_GAPS_MS.size) {
            ProbeLog.log("E5", "rate-test result: ${RateTest.summarise(pairs, callTimes)}")
            return
        }
        val pair = PairInProgress(RATE_TEST_GAPS_MS[index])

        fun finishWhenBothAreIn() {
            if (!pair.firstDone || !pair.secondDone) return
            val firstError = pair.firstError
            val secondError = pair.secondError
            pairs += RatePair(pair.nominalGap, pair.measuredGap, firstError != null, secondError != null)
            ProbeLog.log(
                "E5",
                when {
                    // Without a first shot nothing started Android's timer,
                    // so the second shot's fate says nothing about the limit.
                    firstError != null ->
                        "rate-test gap=${pair.nominalGap}ms: INVALID, the first shot of the pair failed " +
                            "(${errorName(firstError)})"
                    secondError != null ->
                        "rate-test gap=${pair.nominalGap}ms (measured ${pair.measuredGap}ms) -> ${errorName(secondError)}"
                    else -> "rate-test gap=${pair.nominalGap}ms (measured ${pair.measuredGap}ms) -> ok"
                },
            )
            // Wait well past any limit before the next pair.
            handler.postDelayed({ rateLimitStep(windowId, index + 1, pairs, callTimes) }, RATE_TEST_REST_MS)
        }

        // First shot: starts the platform's timer. Second shot: one gap later.
        val firstRequested = SystemClock.uptimeMillis()
        shoot(windowId) { error, took ->
            pair.firstDone = true
            pair.firstError = error
            if (error == null) callTimes += took
            finishWhenBothAreIn()
        }
        handler.postDelayed({
            pair.measuredGap = SystemClock.uptimeMillis() - firstRequested
            shoot(windowId) { error, took ->
                pair.secondDone = true
                pair.secondError = error
                if (error == null) callTimes += took
                finishWhenBothAreIn()
            }
        }, pair.nominalGap)
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

        /** The whole-picture checks look at every fourth pixel in each direction. */
        private const val WHOLE_IMAGE_SHRINK = 4
        private const val CONTROL_DELAY_MS = 500L
        private const val RATE_TEST_REST_MS = 1200L

        // AOSP's limit is "more than 333 ms since the last accepted request".
        // The gaps bracket that value so a different limit on One UI shows up.
        private val RATE_TEST_GAPS_MS = listOf(0L, 100L, 200L, 300L, 320L, 340L, 360L, 400L, 500L, 1000L)
    }
}
