package io.github.aaroncchung.spoilerblocker.probe

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

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

    fun init(context: Context) {
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
    }

    fun updateCancelPackage(value: String) {
        cancelPackage = value.trim()
        prefs.edit { putString(KEY_CANCEL_PACKAGE, cancelPackage) }
    }

    private const val KEY_MECHANISM = "mechanism"
    private const val KEY_TRACKING = "tracking"
    private const val KEY_BOX = "box"
    private const val KEY_FILM_STRIP = "film_strip"
    private const val KEY_TOUCH_WATCH = "touch_watch"
    private const val KEY_CANCEL = "cancel"
    private const val KEY_CANCEL_PACKAGE = "cancel_package"
}
