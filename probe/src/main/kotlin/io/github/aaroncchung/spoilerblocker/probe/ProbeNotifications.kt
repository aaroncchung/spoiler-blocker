package io.github.aaroncchung.spoilerblocker.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.RemoteViews

/**
 * The probe's own notifications:
 *
 * - One ongoing "control" notification whose buttons trigger actions while
 *   another app is in front, where the probe's own screen cannot be reached.
 * - The E4 test notification, posted at high importance so that Android shows
 *   it as a banner.
 */
object ProbeNotifications {
    const val CONTROL_CHANNEL = "controls"
    const val TEST_CHANNEL = "test"

    /** Marks a trigger that came from a notification button, so the shade is closed first. */
    const val EXTRA_FROM_SHADE = "from_shade"

    private const val CONTROLS_ID = 1

    /** Test notifications are numbered from here up. Lower numbers are the probe's other notifications. */
    const val FIRST_TEST_ID = 100

    private var nextTestId = FIRST_TEST_ID
    private val handler = Handler(Looper.getMainLooper())

    /**
     * When notify() was called for each test notification, in uptime
     * milliseconds. The listener uses it to report the delay on one clock.
     * Main thread only.
     */
    val testNotifyUptime = HashMap<Int, Long>()

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val controls = NotificationChannel(
            CONTROL_CHANNEL,
            context.getString(R.string.channel_controls),
            // Default rather than low importance keeps the notification out of
            // the "silent" section, where Android shows everything collapsed
            // and the buttons would take an extra tap to reach.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(controls)
        manager.createNotificationChannel(
            NotificationChannel(
                TEST_CHANNEL,
                context.getString(R.string.channel_test),
                // High importance is what makes Android show a banner.
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    /** Shows or refreshes the control notification. [status] is the line of text under the title. */
    fun showControls(context: Context, status: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) {
            ProbeLog.log("APP", "the control notification is not shown: allow notifications for SB Probe")
            return
        }
        // A standard notification shows three buttons at most. This one needs
        // seven, so its expanded form is a small layout of its own.
        val buttons = RemoteViews(context.packageName, R.layout.notification_controls).apply {
            setTextViewText(R.id.control_status, status)
            setOnClickPendingIntent(R.id.control_repick, trigger(context, ProbeActions.REPICK))
            setOnClickPendingIntent(R.id.control_next_list, trigger(context, ProbeActions.NEXT_LIST))
            setOnClickPendingIntent(R.id.control_next_mechanism, trigger(context, ProbeActions.MECHANISM))
            // The button also starts a fresh count, so that each filmed clip
            // gets a summary of its own without anything further to press.
            setOnClickPendingIntent(R.id.control_summary, trigger(context, ProbeActions.E2_SUMMARY, reset = true))
            setOnClickPendingIntent(R.id.control_screenshot, trigger(context, ProbeActions.SCREENSHOT))
            setOnClickPendingIntent(R.id.control_rate_test, trigger(context, ProbeActions.RATE_TEST))
            setOnClickPendingIntent(R.id.control_dump_tree, trigger(context, ProbeActions.DUMP_TREE))
        }
        // Tapping the body of the notification opens the probe's screen.
        val openProbe = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CONTROL_CHANNEL)
            .setSmallIcon(R.drawable.ic_probe)
            .setContentTitle(context.getString(R.string.controls_title))
            .setContentText(status)
            .setContentIntent(openProbe)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomBigContentView(buttons)
            .build()
        manager.notify(CONTROLS_ID, notification)
    }

    fun hideControls(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(CONTROLS_ID)
    }

    /** A broadcast to [TriggerReceiver], to be sent when a notification button is pressed. */
    private fun trigger(context: Context, probeAction: String, reset: Boolean = false): PendingIntent {
        val intent = Intent(probeAction)
            .setClass(context, TriggerReceiver::class.java)
            .putExtra(EXTRA_FROM_SHADE, true)
            .putExtra(ProbeActions.EXTRA_RESET, reset)
        // FLAG_IMMUTABLE: nobody who gets hold of this may alter the broadcast.
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** E4: posts one banner notification after [delayMillis], leaving time to start filming. */
    fun postTestAfter(context: Context, delayMillis: Long) {
        val appContext = context.applicationContext
        if (ProbeSettings.filmStripEnabled) {
            // The strip is an opaque band over the status bar, left on from E2
            // or E3. A banner, or part of one, could be hidden behind it, and
            // "no banner on film" would then mean nothing.
            ProbeSettings.updateFilmStripEnabled(false)
            ProbeAccessibilityService.applySettings()
            ProbeLog.log("E4", "film strip switched off: it covers the status bar and could hide a banner")
        }
        ProbeLog.log("E4", "test notification will be posted in ${delayMillis}ms")
        handler.postDelayed({ postTest(appContext) }, delayMillis)
    }

    private fun postTest(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) {
            ProbeLog.log("E4", "cannot post: notifications are not allowed for SB Probe")
            return
        }
        // A new id every time: updating an old notification may not show a banner again.
        val id = nextTestId++
        val notification = Notification.Builder(context, TEST_CHANNEL)
            .setSmallIcon(R.drawable.ic_probe)
            .setContentTitle(context.getString(R.string.test_notification_title, id))
            .setContentText(context.getString(R.string.test_notification_text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        val before = SystemClock.uptimeMillis()
        testNotifyUptime[id] = before
        manager.notify(id, notification)
        ProbeLog.log("E4", "test notification id=$id: notify() called at up=$before, returned after ${SystemClock.uptimeMillis() - before}ms")
    }
}
