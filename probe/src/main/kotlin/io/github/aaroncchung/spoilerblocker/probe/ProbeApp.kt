package io.github.aaroncchung.spoilerblocker.probe

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Runs once whenever Android starts the probe's process, before any activity
 * or service. For E6 that moment is itself a measurement: a "process-start"
 * line in the middle of the night means the process had died.
 */
class ProbeApp : Application() {

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> ProbeLog.log(E6.TAG, "screen on")
                Intent.ACTION_SCREEN_OFF -> ProbeLog.log(E6.TAG, "screen off")
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED ->
                    ProbeLog.log(E6.TAG, "doze ${DeviceState.screenAndDoze(context)}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ProbeLog.init(this)
        ProbeSettings.init(this)
        ProbeNotifications.createChannels(this)

        // The boot count goes up by one each time the phone starts. It lets
        // the summary tell a reboot from a process that merely died.
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, 0)
        ProbeLog.log(
            E6.TAG,
            "${E6.PROCESS_START} boot=$boot battery=${DeviceState.batteryMode(this)} bucket=${DeviceState.standbyBucket(this)}",
        )
        logEarlierExits()

        // These broadcasts are only delivered to receivers registered in code.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        registerReceiver(powerReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    /**
     * Android remembers why each of the app's earlier processes ended. This
     * is the best evidence of who stopped the probe: the low memory killer,
     * the user, a crash, or One UI's battery management.
     */
    private fun logEarlierExits() {
        val prefs = getSharedPreferences("probe", MODE_PRIVATE)
        val alreadyLogged = prefs.getLong(KEY_LAST_EXIT_LOGGED, 0)
        val exits = getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(null, 0, MAX_EXITS)
            .filter { it.timestamp > alreadyLogged }
            .sortedBy { it.timestamp }
        for (exit in exits) {
            val at = EXIT_TIME.format(Instant.ofEpochMilli(exit.timestamp).atZone(ZoneId.systemDefault()))
            ProbeLog.log(
                E6.TAG,
                "${E6.EXIT_INFO} at=$at reason=${reasonName(exit.reason)} importance=${exit.importance} " +
                    "description=${exit.description.orEmpty().ifEmpty { "-" }}",
            )
        }
        exits.lastOrNull()?.let { newest -> prefs.edit { putLong(KEY_LAST_EXIT_LOGGED, newest.timestamp) } }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "UNKNOWN($reason)"
    }

    companion object {
        private const val KEY_LAST_EXIT_LOGGED = "last_exit_logged"
        private const val MAX_EXITS = 5
        private val EXIT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        /**
         * E6: marks the start of a run in the log. The summary only counts
         * what comes after the newest marker, so two overnight runs with
         * different battery settings do not get mixed up.
         */
        fun logRunStart(context: Context, label: String) {
            ProbeLog.log(
                E6.TAG,
                "${E6.RUN_START} battery=${DeviceState.batteryMode(context)} bucket=${DeviceState.standbyBucket(context)} " +
                    "label=${label.ifEmpty { "-" }}",
            )
        }
    }
}

/**
 * E6: writes one log line a minute for as long as a service is connected.
 *
 * The timer is an ordinary Handler, which counts awake time only and never
 * wakes the phone. So the heartbeat stops while the phone is in deep sleep
 * and carries on afterwards, exactly as the real service's own work would.
 * A missing heartbeat while the phone was awake means the process was not
 * running. [HeartbeatSummary] works that out from the two clocks on each line.
 */
class Heartbeat(private val context: Context, private val service: String) {
    private val handler = Handler(Looper.getMainLooper())
    private var count = 0

    private val beat = object : Runnable {
        override fun run() {
            count++
            // The battery setting and the standby bucket are on every line
            // because One UI may move an unused app to a stricter bucket
            // days into a run. The summary reports each change.
            ProbeLog.log(
                E6.TAG,
                "${E6.HEARTBEAT} $service n=$count ${DeviceState.screenAndDoze(context)} " +
                    "${DeviceState.switchesInSettings(context)} " +
                    "battery=${DeviceState.batteryMode(context)} bucket=${DeviceState.standbyBucket(context)}",
            )
            handler.postDelayed(this, HeartbeatSummary.INTERVAL_MS)
        }
    }

    fun start() {
        stop()
        count = 0
        beat.run()
    }

    fun stop() {
        handler.removeCallbacks(beat)
    }
}
