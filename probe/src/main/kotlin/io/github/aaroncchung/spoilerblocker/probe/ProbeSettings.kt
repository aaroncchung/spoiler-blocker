package io.github.aaroncchung.spoilerblocker.probe

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * E4: the rule that a real app's notifications are only dismissed for a short
 * while.
 *
 * A dismissed notification is gone: the probe keeps no copy of it. Left on by
 * mistake, E4 would silently eat a friend's messages. So when the target is
 * any app but the probe itself, dismissing switches itself back after
 * [LIMIT_MS], and the control notification says so while it lasts.
 */
object DismissTimeLimit {
    const val LIMIT_MS = 15 * 60_000L

    /** True if the limit applies: dismissing is on and aimed at an app other than the probe. */
    fun applies(enabled: Boolean, targetPackage: String, ownPackage: String): Boolean =
        enabled && targetPackage.isNotEmpty() && targetPackage != ownPackage

    /** True if a limit is running ([expiresAt] is not 0) and [now] has reached it. */
    fun isDue(expiresAt: Long, now: Long): Boolean = expiresAt != 0L && now >= expiresAt
}

/** E2: what makes the box move. */
enum class TrackingMode(val id: String) {
    /** Move the box when the watched app sends an accessibility event. */
    EVENTS("events"),

    /** Ask the watched app where the item is on every frame, without waiting for events. */
    POLL("poll"),
}

/**
 * The probe's switches. They are saved, so they survive the process being
 * killed, and they are Compose state, so the activity redraws when a switch is
 * changed from adb or from a notification button.
 *
 * Change a value with the matching `update...` function, then call
 * [ProbeAccessibilityService.applySettings] so that a running service notices.
 */
object ProbeSettings {
    private lateinit var prefs: SharedPreferences

    var mechanism by mutableStateOf(OverlayMechanism.WM_MOVE)
        private set
    var trackingMode by mutableStateOf(TrackingMode.EVENTS)
        private set
    var boxEnabled by mutableStateOf(true)
        private set
    var filmStripEnabled by mutableStateOf(false)
        private set
    var touchWatchEnabled by mutableStateOf(false)
        private set
    var cancelEnabled by mutableStateOf(true)
        private set
    var cancelPackage by mutableStateOf("")
        private set

    /**
     * When dismissing a real app's notifications stops by itself, as a time
     * of day in milliseconds since 1970. 0 while the target is the probe
     * itself or dismissing is off. See [DismissTimeLimit].
     */
    var cancelExpiresAt by mutableLongStateOf(0L)
        private set

    private lateinit var ownPackage: String
    private val handler = Handler(Looper.getMainLooper())
    private val expiryCheck = Runnable { expireForeignTargetIfDue() }

    fun init(context: Context) {
        ownPackage = context.packageName
        prefs = context.getSharedPreferences("probe", Context.MODE_PRIVATE)
        mechanism = OverlayMechanism.fromId(prefs.getString(KEY_MECHANISM, null)) ?: OverlayMechanism.WM_MOVE
        trackingMode = TrackingMode.entries.firstOrNull { it.id == prefs.getString(KEY_TRACKING, null) }
            ?: TrackingMode.EVENTS
        boxEnabled = prefs.getBoolean(KEY_BOX, true)
        filmStripEnabled = prefs.getBoolean(KEY_FILM_STRIP, false)
        touchWatchEnabled = prefs.getBoolean(KEY_TOUCH_WATCH, false)
        cancelEnabled = prefs.getBoolean(KEY_CANCEL, true)
        // E4 dismisses the probe's own test notifications unless told otherwise.
        cancelPackage = prefs.getString(KEY_CANCEL_PACKAGE, null) ?: context.packageName
        cancelExpiresAt = prefs.getLong(KEY_CANCEL_EXPIRES, 0L)
        // The time limit may have run out while the process was not running.
        if (!expireForeignTargetIfDue() && cancelExpiresAt != 0L) {
            handler.postDelayed(expiryCheck, (cancelExpiresAt - System.currentTimeMillis()).coerceAtLeast(0))
        }
    }

    fun updateMechanism(value: OverlayMechanism) {
        mechanism = value
        prefs.edit { putString(KEY_MECHANISM, value.id) }
    }

    fun updateTrackingMode(value: TrackingMode) {
        trackingMode = value
        prefs.edit { putString(KEY_TRACKING, value.id) }
    }

    fun updateBoxEnabled(value: Boolean) {
        boxEnabled = value
        prefs.edit { putBoolean(KEY_BOX, value) }
    }

    fun updateFilmStripEnabled(value: Boolean) {
        filmStripEnabled = value
        prefs.edit { putBoolean(KEY_FILM_STRIP, value) }
    }

    fun updateTouchWatchEnabled(value: Boolean) {
        touchWatchEnabled = value
        prefs.edit { putBoolean(KEY_TOUCH_WATCH, value) }
    }

    fun updateCancelEnabled(value: Boolean) {
        cancelEnabled = value
        prefs.edit { putBoolean(KEY_CANCEL, value) }
        restartTimeLimit()
    }

    fun updateCancelPackage(value: String) {
        cancelPackage = value.trim()
        prefs.edit { putString(KEY_CANCEL_PACKAGE, cancelPackage) }
        restartTimeLimit()
    }

    /** Starts the clock again whenever the E4 target or its switch changes. */
    private fun restartTimeLimit() {
        handler.removeCallbacks(expiryCheck)
        val limited = DismissTimeLimit.applies(cancelEnabled, cancelPackage, ownPackage)
        cancelExpiresAt = if (limited) System.currentTimeMillis() + DismissTimeLimit.LIMIT_MS else 0L
        prefs.edit { putLong(KEY_CANCEL_EXPIRES, cancelExpiresAt) }
        // This timer does not run while the phone sleeps, so it can be late.
        // The listener therefore checks the limit itself before every dismissal.
        if (limited) handler.postDelayed(expiryCheck, DismissTimeLimit.LIMIT_MS)
        if (limited && filmStripEnabled) {
            // The strip covers the status bar and could hide the banner E4 is about to film.
            updateFilmStripEnabled(false)
            ProbeAccessibilityService.applySettings()
            ProbeLog.log("E4", "film strip switched off: it covers the status bar and could hide a banner")
        }
        ProbeAccessibilityService.refreshControls()
    }

    /**
     * If the time limit for dismissing a real app's notifications has run
     * out, points E4 back at the probe itself. Returns true if it did.
     */
    fun expireForeignTargetIfDue(): Boolean {
        if (!DismissTimeLimit.isDue(cancelExpiresAt, System.currentTimeMillis())) return false
        val was = cancelPackage
        cancelPackage = ownPackage
        cancelExpiresAt = 0L
        prefs.edit {
            putString(KEY_CANCEL_PACKAGE, ownPackage)
            putLong(KEY_CANCEL_EXPIRES, 0L)
        }
        ProbeLog.log(
            "E4",
            "time limit reached: no longer dismissing notifications from $was. The target is the probe itself again.",
        )
        ProbeAccessibilityService.refreshControls()
        return true
    }

    /** A warning for the screen and the control notification while a real app's notifications are being dismissed. */
    fun foreignTargetWarning(): String? {
        if (cancelExpiresAt == 0L) return null
        val until = UNTIL_FORMAT.format(Instant.ofEpochMilli(cancelExpiresAt).atZone(ZoneId.systemDefault()))
        return "DISMISSING every notification from $cancelPackage until $until"
    }

    private val UNTIL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private const val KEY_CANCEL_EXPIRES = "cancel_expires"
    private const val KEY_MECHANISM = "mechanism"
    private const val KEY_TRACKING = "tracking"
    private const val KEY_BOX = "box"
    private const val KEY_FILM_STRIP = "film_strip"
    private const val KEY_TOUCH_WATCH = "touch_watch"
    private const val KEY_CANCEL = "cancel"
    private const val KEY_CANCEL_PACKAGE = "cancel_package"
}
