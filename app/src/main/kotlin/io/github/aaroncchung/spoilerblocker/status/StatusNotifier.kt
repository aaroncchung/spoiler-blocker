package io.github.aaroncchung.spoilerblocker.status

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.MainThread
import io.github.aaroncchung.spoilerblocker.MainActivity
import io.github.aaroncchung.spoilerblocker.R
import io.github.aaroncchung.spoilerblocker.SpoilerBlockerApplication
import io.github.aaroncchung.spoilerblocker.data.Blocker
import io.github.aaroncchung.spoilerblocker.notifications.SpoilerNotificationListener
import io.github.aaroncchung.spoilerblocker.notifications.isNotificationAccessGranted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Shows a notification for as long as a blocker is on, so that a forgotten
 * blocker is noticed. If notifications are not really being hidden, the
 * notification says that instead. This is decision 12 in docs/ARCHITECTURE.md.
 *
 * There is deliberately no foreground service behind it. A foreground service
 * is for keeping a process alive, and nothing here needs that. Android keeps
 * the notification listener connected for as long as the app has notification
 * access, and this process with it. And a notification does not need its app:
 * once posted, Android goes on showing it after the process has gone.
 *
 * The price is that after a restart of the phone, or after the app is force
 * stopped, the notification is only back once this process runs again. With
 * notification access that takes seconds, because Android starts the process
 * to connect the listener. Without it, it is when the app is next opened.
 */
class StatusNotifier(private val context: Context) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    /** The names of the blockers that are on. Null until the stored blockers have been read. */
    private var enabledBlockerNames: List<String>? = null

    /**
     * When the process started, near enough: the Application creates this
     * object first thing. elapsedRealtime is a clock that the owner cannot
     * set and that goes on counting while the phone sleeps.
     */
    private val startedAtMillis = SystemClock.elapsedRealtime()

    /**
     * Starts following [blockers]: from now on the notification is posted,
     * updated or removed whenever a blocker is switched, renamed or deleted.
     * The Application calls this once, when the process starts.
     */
    @MainThread
    fun start(blockers: Flow<List<Blocker>>, scope: CoroutineScope) {
        createChannel()

        // Dispatchers.Main.immediate, as in the notification listener, so
        // that everything in this class runs on the main thread and
        // enabledBlockerNames needs no lock.
        scope.launch(Dispatchers.Main.immediate) {
            blockers
                .map { all -> all.filter { it.enabled }.map { it.name } }
                .distinctUntilChanged()
                .collect { names ->
                    enabledBlockerNames = names
                    refresh()
                }
        }

        // A warning that the listener is not connected waits until this
        // time is up (see isTooEarlyToWarn). The listener calls refresh()
        // when it connects. This is for when it never does.
        scope.launch(Dispatchers.Main.immediate) {
            delay(LISTENER_CONNECT_WAIT_MILLIS)
            refresh()
        }
    }

    /**
     * Posts the notification again, or removes it, to match the blockers
     * that are on and whether notifications are being hidden at this moment.
     *
     * Call this when something changed that this class does not follow by
     * itself: notification access was given or taken away, the listener
     * connected or disconnected, the owner switched the app's notifications
     * on, or the notification was swiped away.
     */
    @MainThread
    fun refresh() {
        // Not read from storage yet. The first read ends in a call to this.
        val names = enabledBlockerNames ?: return

        val hidingState = hidingState(
            notificationAccessGranted = isNotificationAccessGranted(context),
            listenerConnected = SpoilerNotificationListener.isConnected,
        )
        val content = statusNotificationContent(names, hidingState)
        if (content == null) {
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }
        if (isTooEarlyToWarn(hidingState, SystemClock.elapsedRealtime() - startedAtMillis)) {
            // Whatever is showing, or not showing, stays as it is until the
            // listener connects or the wait is over (see start).
            return
        }
        // Posting under the id of a notification that is showing replaces
        // it. If the owner has not allowed the app to post notifications, or
        // has switched off this one's channel, Android drops this without an
        // error, and the blocker list shows a card about it.
        notificationManager.notify(NOTIFICATION_ID, build(content))
    }

    /** A notification has to be put in a channel. The owner can silence or block each channel in the system settings. */
    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.status_channel_name),
            // In the shade, with an icon in the status bar, but without a
            // sound and without a banner sliding in over the screen.
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = context.getString(R.string.status_channel_description)
        // No dot on the app's icon for this.
        channel.setShowBadge(false)
        // Creating a channel that exists already updates its name and
        // description, so this can run at every start. Of the rest, only the
        // importance can change, and only downwards, if the owner has not
        // touched the channel. Android never raises it, and it ignores a new
        // sound or lock screen setting: see CHANNEL_ID.
        notificationManager.createNotificationChannel(channel)
    }

    private fun build(content: StatusNotificationContent): Notification {
        val resources = context.resources

        // getQuantityString takes the count first, to choose between the
        // wording for one and for several, and then the values to put into
        // the text, of which the count is one again.
        var names = content.names.joinToString(", ")
        if (content.unnamedCount > 0) {
            names = resources.getQuantityString(
                R.plurals.status_names_and_more,
                content.unnamedCount,
                names,
                content.unnamedCount,
            )
        }

        val title: String
        val text: String
        when (content.hidingState) {
            HidingState.HIDING -> {
                title = resources.getQuantityString(
                    R.plurals.status_title,
                    content.blockerCount,
                    content.blockerCount,
                )
                text = names
            }
            HidingState.NO_ACCESS -> {
                title = context.getString(R.string.status_not_hidden_title)
                text = context.getString(R.string.status_no_access_text, names)
            }
            HidingState.NOT_CONNECTED -> {
                title = context.getString(R.string.status_not_hidden_title)
                text = context.getString(R.string.status_not_connected_text, names)
            }
        }

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_notification)
            .setContentTitle(title)
            .setContentText(text)
            // Closed, a notification shows one line of its text. This style
            // shows all of it once the notification is expanded.
            .setStyle(Notification.BigTextStyle().bigText(text))
            // "Ongoing" keeps it out of "Clear all". Since Android 14 it no
            // longer stops the owner from swiping it away: see
            // StatusNotificationDismissedReceiver.
            .setOngoing(true)
            // The time would only say when this was last posted.
            .setShowWhen(false)
            // refresh() posts this again each time the app comes to the
            // front. If the channel ever makes a sound, it should sound when
            // the notification first appears, not on every update.
            .setOnlyAlertOnce(true)
            // A blocker's name is not a spoiler, so the lock screen may show
            // all of it.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())
            .setDeleteIntent(dismissedIntent())
            .build()
    }

    // A PendingIntent is a permit for someone else, here Android, to send an
    // intent in this app's name later. FLAG_IMMUTABLE means the receiver of
    // the permit cannot change the intent inside it.

    /** What a tap on the notification does. */
    private fun openAppIntent(): PendingIntent {
        // The same intent as the launcher sends for the app's icon. Android
        // then brings the app to the front as it was left. Any other intent
        // would put a second blocker list on top of what was open.
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /** What Android sends when the owner swipes the notification away. */
    private fun dismissedIntent(): PendingIntent {
        val intent = Intent(context, StatusNotificationDismissedReceiver::class.java)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        // The channel's id is stored by Android together with what the owner
        // chose for the channel, so it stays the same from build to build.
        // The one reason to change it: a louder channel, to show on the lock
        // screen for example. Android keeps an existing channel as it is, so
        // that needs a new id, and the old channel removed with
        // deleteNotificationChannel. Deleting and creating the same id again
        // does not work: Android brings the deleted channel back as it was.
        const val CHANNEL_ID = "status"

        // Any number will do. The app has only this one notification.
        private const val NOTIFICATION_ID = 1
    }
}

/**
 * Puts the status notification back after the owner swiped it away.
 *
 * Since Android 14 the owner can swipe away an "ongoing" notification, unless
 * it belongs to a call or to a media player. The status notification is there
 * so that a blocker is not forgotten, so while a blocker is on it comes back.
 *
 * It comes back once for each swipe and not in a loop: Android sends the
 * notification's "delete intent", which starts this receiver, only when the
 * owner dismisses the notification. It does not send it when the app removes
 * the notification itself, nor when the owner blocks the app's notifications.
 *
 * The other way to notice the swipe would be the notification listener. This
 * way also works while the app has no notification access.
 */
class StatusNotificationDismissedReceiver : BroadcastReceiver() {

    // If Android had to start the process to deliver this, the blockers are
    // not read yet and refresh() does nothing. The Application has started
    // the StatusNotifier by then, which posts the notification after the
    // first read.
    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as SpoilerBlockerApplication
        application.container.statusNotifier.refresh()
    }
}

/**
 * Puts the status notification back after the owner switched the app's
 * notifications, or only the channel of the status notification, off and on
 * again in the system settings.
 *
 * Switching off removes the notification, and switching on does not bring it
 * back. Android announces both with a broadcast that goes to this app alone,
 * and starts the process to deliver it if need be. After switching off,
 * refresh() posts in vain, which does no harm: Android drops the notification.
 */
class NotificationsSwitchedReceiver : BroadcastReceiver() {

    // As in StatusNotificationDismissedReceiver: if Android had to start
    // the process to deliver this, refresh() does nothing, and the
    // StatusNotifier posts once the blockers have been read.
    override fun onReceive(context: Context, intent: Intent) {
        // Only Android can send to this receiver, and only these two, so the
        // check changes nothing today. Lint asks it of every receiver of a
        // system broadcast, in case the receiver is ever exported.
        when (intent.action) {
            NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED,
            NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED -> {
                val application = context.applicationContext as SpoilerBlockerApplication
                application.container.statusNotifier.refresh()
            }
        }
    }
}
