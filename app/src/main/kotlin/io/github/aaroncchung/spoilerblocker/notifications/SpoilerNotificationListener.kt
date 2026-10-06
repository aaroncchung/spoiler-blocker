package io.github.aaroncchung.spoilerblocker.notifications

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.aaroncchung.spoilerblocker.AppContainer
import io.github.aaroncchung.spoilerblocker.BuildConfig
import io.github.aaroncchung.spoilerblocker.SpoilerBlockerApplication
import io.github.aaroncchung.spoilerblocker.blocking.ActiveBlockers
import io.github.aaroncchung.spoilerblocker.data.HiddenNotification
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Dismisses notifications that match a blocker and records them in the
 * hidden list.
 *
 * Android creates this service and keeps it running for as long as the owner
 * has given the app notification access. It then calls [onNotificationPosted]
 * for every notification that any app posts or updates.
 *
 * Every callback arrives on the main thread. The decision and the dismissal
 * happen right there, without a hop to another thread, because by then the
 * notification is already on its way to the screen (risk 5 in
 * docs/ARCHITECTURE.md). Only the write to the hidden list is handed to a
 * background thread.
 *
 * **Groups.** An app can bundle its notifications into a group: several
 * children and one summary, which often repeats a line from each child. Each
 * notification is judged on its own text, whether it is a child or a summary.
 *
 * - A child that matches is dismissed. The rest of its group stays.
 * - A summary that matches is dismissed together with every child of its
 *   group, and each child gets an entry of its own in the hidden list.
 *   Android leaves no choice about the children: it dismisses them itself
 *   the moment their summary goes. Leaving a matching summary is not an
 *   option either. Its sub text shows in the header of the group, and once
 *   its last child has gone, Android shows the summary as a notification of
 *   its own, with all of its text.
 */
class SpoilerNotificationListener : NotificationListenerService() {

    // Dispatchers.Main.immediate keeps the collector in onCreate on the main
    // thread, where Android's callbacks also arrive. Everything in this class
    // therefore runs on one thread and needs no locks.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Runs one task at a time, so that entries reach the hidden list in the
    // order in which they were dismissed.
    private val storageDispatcher = Dispatchers.IO.limitedParallelism(1)

    private lateinit var container: AppContainer

    /** The blockers that are on. Null until the stored blockers have been read for the first time. */
    private var activeBlockers: ActiveBlockers? = null

    /**
     * App names that were looked up before. Reading a name loads part of the
     * other app, which is too slow to repeat for every notification.
     */
    private val appNames = HashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        container = (application as SpoilerBlockerApplication).container

        scope.launch {
            container.blockerRepository.blockers
                // Only the blockers that are on matter here. distinctUntilChanged
                // then lets through only a real change to them, so editing a
                // blocker that is off does not cause a sweep.
                .map { blockers -> blockers.filter { it.enabled } }
                .distinctUntilChanged()
                .collect { enabledBlockers ->
                    activeBlockers = ActiveBlockers(enabledBlockers)
                    sweep()
                }
        }
    }

    override fun onDestroy() {
        isConnected = false
        scope.cancel()
        super.onDestroy()
    }

    /** Android calls this once the service may ask for and dismiss notifications. */
    override fun onListenerConnected() {
        isConnected = true
        sweep()
    }

    override fun onListenerDisconnected() {
        isConnected = false
    }

    /** Called for a new notification and again each time an app updates one. */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn)
    }

    /**
     * Checks every notification that is showing now.
     *
     * A notification reaches [onNotificationPosted] only at the moment it is
     * posted. Without this, anything posted earlier would stay: before a
     * blocker was switched on, before the listener was connected, or in the
     * moment after the process starts while the blockers are still being
     * read from storage.
     */
    private fun sweep() {
        // Android only answers a listener that is connected.
        if (!isConnected) return
        val blockers = activeBlockers ?: return
        if (blockers.isEmpty) return

        for (sbn in activeNotifications.orEmpty()) {
            handle(sbn)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        // Not read from storage yet. The sweep that follows the first read
        // comes back to this notification.
        val blockers = activeBlockers ?: return
        // While no blocker is on, a notification is not even looked at.
        if (blockers.isEmpty || !canBeDismissed(sbn)) return

        val receivedAt = System.currentTimeMillis()
        val content = readContent(sbn)
        val blockedBy = blockers.check(content.toCandidate())
        if (blockedBy == null) {
            log(sbn, "left alone")
            return
        }

        // The children have to be listed before the summary is dismissed.
        // Afterwards Android may already have removed them.
        val children = if (sbn.isGroupSummary) dismissibleChildrenOf(sbn) else emptyList()

        cancelNotifications((listOf(sbn) + children).map { it.key }.toTypedArray())
        val now = System.currentTimeMillis()
        // The first number is Android's share of the delay and the second is
        // this app's. The first is not all time on screen: Android may hold
        // a notification back before anything sees it (200 ms on the
        // emulator). In a sweep it is how long the notification had been
        // showing.
        val sincePosted = receivedAt - sbn.postTime
        log(sbn, "arrived $sincePosted ms after it was posted, dismissed ${now - receivedAt} ms later")

        // When an app has several loose notifications showing, Android puts
        // them under a summary of its own making, which has no text. It can
        // still match, through the app's name. It is dismissed like any
        // other, but there is nothing in it to list: its children are.
        if (!(sbn.isGroupSummary && content.hasNothingToShow)) {
            record(content.toHiddenNotification(sbn.key, blockedBy, hiddenAtMillis = now))
        }
        for (child in children) {
            val childContent = readContent(child)
            // A child that matches by itself is recorded with its own reason.
            // In a sweep it is also handled by itself, before or after its
            // summary. The repository keeps only one of the two entries.
            val ownBlockedBy = blockers.check(childContent.toCandidate())
            record(
                childContent.toHiddenNotification(
                    notificationKey = child.key,
                    blockedBy = ownBlockedBy ?: blockedBy,
                    hiddenAtMillis = now,
                    hiddenWithGroup = ownBlockedBy == null,
                ),
            )
            log(child, "dismissed with its group")
        }
    }

    private fun canBeDismissed(sbn: StatusBarNotification): Boolean =
        // Never this app's own notifications.
        sbn.packageName != packageName &&
            // Android does not let a listener dismiss an "ongoing"
            // notification: music that is playing, a call, a download.
            // Asking would do nothing, and the hidden list would then claim
            // that something was hidden which is still there.
            !sbn.isOngoing

    private fun dismissibleChildrenOf(summary: StatusBarNotification): List<StatusBarNotification> =
        activeNotifications.orEmpty().filter { sbn ->
            // The group key holds the user, the package and the group's name,
            // so it can only be equal within one app.
            sbn.groupKey == summary.groupKey && !sbn.isGroupSummary && canBeDismissed(sbn)
        }

    private fun record(entry: HiddenNotification) {
        // applicationScope and not this service's own scope: the entry must
        // still be written if Android destroys the service straight after.
        container.applicationScope.launch(storageDispatcher) {
            try {
                container.hiddenNotificationRepository.add(entry)
            } catch (e: IOException) {
                // A full disk must not crash the listener, because that would
                // stop the blocking too. The exception names the file, never
                // the notification. Only IOException is caught: the "written
                // by a newer build" error is meant to crash, as it is for the
                // blockers.
                Log.w(TAG, "Could not store a hidden notification", e)
            }
        }
    }

    /** Copies every piece of text out of [sbn] that could carry a spoiler. */
    private fun readContent(sbn: StatusBarNotification): NotificationContent {
        // An app describes its notification in "extras": a bag of values
        // under keys that the Notification class names.
        val extras = sbn.notification.extras

        // Text is stored as a CharSequence because it may carry styling.
        // toString() drops the styling.
        fun text(key: String): String = extras.getCharSequence(key)?.toString().orEmpty()

        val messages = Notification.MessagingStyle.Message
            .getMessagesFromBundleArray(
                extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java),
            )
            .map { message ->
                NotificationContent.Message(
                    sender = message.senderPerson?.name?.toString().orEmpty(),
                    text = message.text?.toString().orEmpty(),
                )
            }

        return NotificationContent(
            packageName = sbn.packageName,
            appName = appNameOf(sbn.packageName),
            title = text(Notification.EXTRA_TITLE),
            bigTitle = text(Notification.EXTRA_TITLE_BIG),
            text = text(Notification.EXTRA_TEXT),
            bigText = text(Notification.EXTRA_BIG_TEXT),
            subText = text(Notification.EXTRA_SUB_TEXT),
            summaryText = text(Notification.EXTRA_SUMMARY_TEXT),
            infoText = text(Notification.EXTRA_INFO_TEXT),
            conversationTitle = text(Notification.EXTRA_CONVERSATION_TITLE),
            lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty()
                .map { line -> line.toString() },
            messages = messages,
            tickerText = sbn.notification.tickerText?.toString().orEmpty(),
            pictureDescription = text(Notification.EXTRA_PICTURE_CONTENT_DESCRIPTION),
        )
    }

    private fun appNameOf(packageName: String): String {
        appNames[packageName]?.let { return it }
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val name = packageManager.getApplicationLabel(appInfo).toString()
            appNames[packageName] = name
            name
        } catch (e: PackageManager.NameNotFoundException) {
            // Android hides some apps from this one (see <queries> in the
            // manifest). The package name is the next best thing. It is not
            // remembered, because the next lookup may succeed.
            packageName
        }
    }

    private fun log(sbn: StatusBarNotification, decision: String) {
        // Only the package and the decision, and only in debug builds. A
        // notification's title or text must never be logged: other tools can
        // read the log, and it outlives the notification.
        if (BuildConfig.DEBUG) Log.d(TAG, "${sbn.packageName}: $decision")
    }

    companion object {
        private const val TAG = "SpoilerListener"

        /**
         * True while Android has this listener connected. The service and the
         * screens run in the same process, so the screens can simply read it.
         */
        @Volatile
        var isConnected: Boolean = false
            private set
    }
}

private val StatusBarNotification.isGroupSummary: Boolean
    get() = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
