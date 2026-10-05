package io.github.aaroncchung.spoilerblocker.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The actions [TriggerReceiver] understands. From a PC each one is sent with
 *
 * `adb shell am broadcast -n io.github.aaroncchung.spoilerblocker.probe/.TriggerReceiver -a <action> [extras]`
 *
 * where extras are written `--es name text`, `--ez name true` or `--ei name 123`.
 */
object ProbeActions {
    /** E2: move the box to the next item of the list. */
    const val REPICK = "sbprobe.REPICK"

    /** E2: move the box to another scrollable element, for when the probe guessed the wrong one. */
    const val NEXT_LIST = "sbprobe.NEXT_LIST"

    /** E2: `--es name wm-move|wm-canvas|sc-display|sc-window`. Without the extra: the next one. */
    const val MECHANISM = "sbprobe.MECHANISM"

    /** E2: `--es mode events|poll`. */
    const val TRACKING = "sbprobe.TRACKING"

    /** E2: `--ez on true|false` shows or hides the box. */
    const val BOX = "sbprobe.BOX"

    /** E2: log the timing summary. `--ez reset true` also starts a fresh count. */
    const val E2_SUMMARY = "sbprobe.E2_SUMMARY"

    /** E2 and E3: `--ez on true|false` shows or hides the film strip. */
    const val FILM_STRIP = "sbprobe.FILM_STRIP"

    /** E3: `--ez on true|false` turns the outside-touch watcher on or off. */
    const val TOUCH_WATCH = "sbprobe.TOUCH_WATCH"

    /** E3: listen for touch screen motion events for ten seconds. The screen may not respond meanwhile. */
    const val MOTION_LISTEN = "sbprobe.MOTION_LISTEN"

    /** E5: take a per-window screenshot and check it for the box. */
    const val SCREENSHOT = "sbprobe.SCREENSHOT"

    /** E5: find the screenshot rate limit. */
    const val RATE_TEST = "sbprobe.RATE_TEST"

    /** E1: write the tree the service sees. `--es label youtube-home` names the file. */
    const val DUMP_TREE = "sbprobe.DUMP_TREE"

    /** E4: post the test notification. `--ei delay_ms 3000` sets the delay. */
    const val POST_TEST = "sbprobe.POST_TEST"

    /** E4: `--ez on true|false` and/or `--es package com.example.app`. */
    const val CANCEL = "sbprobe.CANCEL"

    /** E6: mark the start of a run. `--es label unrestricted` is noted in the log. */
    const val RUN_START = "sbprobe.RUN_START"

    const val EXTRA_NAME = "name"
    const val EXTRA_MODE = "mode"
    const val EXTRA_ON = "on"
    const val EXTRA_RESET = "reset"
    const val EXTRA_LABEL = "label"
    const val EXTRA_DELAY_MS = "delay_ms"
    const val EXTRA_PACKAGE = "package"
}

/**
 * Receives the probe's triggers, from `adb shell am broadcast` and from the
 * buttons on the probe's own notifications. This is how the probe is operated
 * while YouTube or Instagram is in front.
 *
 * The manifest only lets adb and the probe itself send to it.
 */
class TriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        ProbeLog.log("APP", "trigger $action")
        when (action) {
            // Switches: save the new value. A running service is told below;
            // one that is not running reads the saved value when it starts.
            ProbeActions.MECHANISM -> {
                val named = OverlayMechanism.fromId(intent.getStringExtra(ProbeActions.EXTRA_NAME))
                ProbeSettings.updateMechanism(named ?: ProbeSettings.mechanism.next())
            }
            ProbeActions.TRACKING -> {
                val mode = TrackingMode.entries.firstOrNull { it.id == intent.getStringExtra(ProbeActions.EXTRA_MODE) }
                if (mode == null) {
                    ProbeLog.log("APP", "TRACKING needs --es mode events|poll")
                    return
                }
                ProbeSettings.updateTrackingMode(mode)
            }
            ProbeActions.BOX -> ProbeSettings.updateBoxEnabled(intent.getBooleanExtra(ProbeActions.EXTRA_ON, true))
            ProbeActions.FILM_STRIP ->
                ProbeSettings.updateFilmStripEnabled(intent.getBooleanExtra(ProbeActions.EXTRA_ON, true))
            ProbeActions.TOUCH_WATCH ->
                ProbeSettings.updateTouchWatchEnabled(intent.getBooleanExtra(ProbeActions.EXTRA_ON, true))

            // E4 and E6 need no accessibility service.
            ProbeActions.POST_TEST -> {
                ProbeNotifications.postTestAfter(context, intent.getIntExtra(ProbeActions.EXTRA_DELAY_MS, 3000).toLong())
                return
            }
            ProbeActions.CANCEL -> {
                if (intent.hasExtra(ProbeActions.EXTRA_ON)) {
                    ProbeSettings.updateCancelEnabled(intent.getBooleanExtra(ProbeActions.EXTRA_ON, true))
                }
                intent.getStringExtra(ProbeActions.EXTRA_PACKAGE)?.let { ProbeSettings.updateCancelPackage(it) }
                ProbeLog.log("E4", "dismissing=${ProbeSettings.cancelEnabled} package=${ProbeSettings.cancelPackage}")
                return
            }
            ProbeActions.RUN_START -> {
                ProbeApp.logRunStart(context, intent.getStringExtra(ProbeActions.EXTRA_LABEL).orEmpty())
                return
            }
        }
        // Everything that reaches this point is carried out by the
        // accessibility service, because it acts on the app in front.
        if (!ProbeAccessibilityService.trigger(action, intent)) {
            ProbeLog.log("APP", "the accessibility service is not running, so nothing happened on screen")
        }
    }
}
