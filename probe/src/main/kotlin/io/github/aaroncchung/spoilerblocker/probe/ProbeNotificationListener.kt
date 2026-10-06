package io.github.aaroncchung.spoilerblocker.probe

import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * E4: dismisses every notification from one chosen app as fast as it can and
 * logs how long each step took.
 *
 * The log cannot say whether a banner was seen. Android tells the status bar
 * about a notification at the same moment as it tells this listener. If the
 * status bar has put the banner up by the time the dismissal reaches it, it
 * keeps the banner for a minimum time before taking it down (in Android's
 * own status bar, 2 seconds, or half a second in some configurations). So the
 * outcome is all or nothing: no banner, or a banner for a second or so. Only
 * the film tells which. "removed" below is when Android dropped the
 * notification from its list, not when a banner left the screen.
 *
 * Android only connects this service after the owner turns on "notification
 * access" for SB Probe in Settings.
 *
 * Nothing from a notification's content is logged: only the sending app, the
 * notification's number and the times.
 */
class ProbeNotificationListener : NotificationListenerService() {

    private lateinit var heartbeat: Heartbeat

    /** Notifications this listener has asked Android to remove, with the time it asked (uptime ms). */
    private val cancelRequested = HashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        heartbeat = Heartbeat(this, E6.LISTENER)
        ProbeLog.log(E6.TAG, "${E6.LISTENER} created")
    }

    override fun onListenerConnected() {
        connected = true
        ProbeLog.log(E6.TAG, "${E6.LISTENER} ${E6.CONNECTED}")
        heartbeat.start()
    }

    override fun onListenerDisconnected() {
        connected = false
        heartbeat.stop()
        ProbeLog.log(E6.TAG, "${E6.LISTENER} ${E6.DISCONNECTED} (onListenerDisconnected)")
    }

    override fun onDestroy() {
        connected = false
        heartbeat.stop()
        ProbeLog.log(E6.TAG, "${E6.LISTENER} destroyed")
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val called = SystemClock.uptimeMillis()
        val calledWall = System.currentTimeMillis()
        // A real app's notifications are only dismissed for a limited time.
        // Check the limit here, before anything is dismissed.
        ProbeSettings.expireForeignTargetIfDue()
        if (!ProbeSettings.cancelEnabled || sbn.packageName != ProbeSettings.cancelPackage) return
        // When the chosen app is the probe itself, dismiss only the test
        // notifications. That leaves the control notification alone, and the
        // summary Android adds by itself when an app has several notifications.
        if (sbn.packageName == packageName && sbn.id < ProbeNotifications.FIRST_TEST_ID) return

        cancelRequested[sbn.key] = called
        cancelNotification(sbn.key)
        val cancelReturned = SystemClock.uptimeMillis()

        // postTime is the time of day at which Android accepted the
        // notification, so the first figure compares two time-of-day readings.
        var line = "pkg=${sbn.packageName} id=${sbn.id} posted=${sbn.postTime} " +
            "listenerCalled=+${calledWall - sbn.postTime}ms cancelReturned=+${calledWall - sbn.postTime + (cancelReturned - called)}ms " +
            "(ms after Android accepted the notification)"
        // For the probe's own test notification the moment notify() was
        // called is known on the uptime clock as well.
        val notifyCalled = ProbeNotifications.testNotifyUptime.remove(sbn.id)
        if (sbn.packageName == packageName && notifyCalled != null) {
            line += " sinceNotifyCall=+${called - notifyCalled}ms"
        }
        if (sbn.isOngoing) {
            line += " ONGOING: Android does not let a listener dismiss this kind"
        }
        ProbeLog.log("E4", "dismiss requested $line")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        val requested = cancelRequested.remove(sbn.key) ?: return
        // Reason 10 is REASON_LISTENER_CANCEL: removed because a listener asked.
        ProbeLog.log(
            "E4",
            "removed from Android's list pkg=${sbn.packageName} id=${sbn.id} reason=$reason " +
                "+${SystemClock.uptimeMillis() - requested}ms after the listener was called " +
                "(not the end of a banner: one that reached the screen stays about a second)",
        )
    }

    companion object {
        /** True while Android has this listener connected. Compose state, so the activity follows it. */
        var connected by mutableStateOf(false)
            private set
    }
}
