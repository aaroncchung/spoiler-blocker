package io.github.aaroncchung.spoilerblocker.notifications

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
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
 * - A summary that matches is dismissed, and every child of its group goes
 *   with it. Android leaves no choice about the children: it dismisses them
 *   itself the moment their summary goes. Leaving a matching summary is not
 *   an option either. Its sub text shows in the header of the group, and
 *   once its last child has gone, Android shows the summary as a
 *   notification of its own, with all of its text.
 * - Each child that goes with a summary gets an entry of its own in the
 *   hidden list. The children that are showing are listed together with the
 *   summary. A child that was posted in the instant between listing them
 *   and dismissing the summary is listed when Android reports that it has
 *   gone. That report carries a slimmed-down copy of the notification, so
 *   the entry has its title and text but not the messages of a chat.
 * - One kind of child cannot be listed. Android holds every new
 *   notification back for a moment before it shows it to anyone. A child
 *   that is still being held back when its summary is dismissed goes with
 *   the group, and no listener is ever told that it existed.
 *
 * **What a listener cannot hide.**
 *
 * - An ongoing notification, such as music that is playing, a call or a
 *   download. Android refuses to dismiss it for a listener, so it is left
 *   alone and not listed.
 * - A bubble, the floating circle some chat apps show. Dismissing its
 *   notification only takes the entry out of the notification shade. The
 *   bubble stays on screen and so does the text beside it, and nothing a
 *   listener can call removes them. It is still dismissed and listed.
 *
 * **When something goes wrong.** A notification is put together by another
 * app, and it can be malformed in ways that make reading it throw. An
 * exception that got out of a callback would end the process. Android would
 * start it again, the sweep would meet the same notification, and so on, with
 * nothing dismissed in the meantime. So each notification is handled inside a
 * guard: the one that cannot be handled is left alone, and the others are
 * not affected.
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

    private val dismissedGroups = DismissedGroups()

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
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) guarded(sbn.packageName) { handle(sbn) }
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

        // The outer guard is for the question to Android itself, which is
        // refused in the instant after notification access is taken away.
        guarded("the sweep") {
            for (sbn in activeNotifications.orEmpty()) {
                guarded(sbn.packageName) { handle(sbn) }
            }
        }
    }

    /**
     * Runs [block] and returns what it returns, or null if it threw. See
     * "When something goes wrong" in the class comment.
     *
     * @param what names what [block] deals with in the log: the package of
     *   the app whose notification it is, as a rule.
     */
    private inline fun <T> guarded(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: RuntimeException) {
            // The kind of exception and never its message: a message can
            // quote the notification that caused it.
            Log.w(TAG, "$what: could not be handled (${e.javaClass.simpleName})")
            null
        }

    /**
     * Android calls this for every notification that goes away, whatever the
     * cause. One cause matters here: a child went because its summary was
     * dismissed. If this listener dismissed that summary and the child is
     * not in the hidden list yet, it is listed now. See [DismissedGroups].
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        if (sbn == null || reason != REASON_GROUP_SUMMARY_CANCELED) return
        guarded(sbn.packageName) {
            val summaryBlockedBy = dismissedGroups
                .blockerForUnlistedChild(sbn.groupKey, sbn.key, SystemClock.elapsedRealtime())
                ?: return
            val blockers = activeBlockers ?: return
            // What Android hands over here is a slimmed-down copy of the
            // notification. Its title, text and lines are there. The
            // messages of a chat are not.
            val entry = blockers.entryForGroupChild(shown(sbn), summaryBlockedBy, System.currentTimeMillis())
            record(listOf(entry))
            log(sbn, "dismissed with its group, listed afterwards")
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        // Not read from storage yet. The sweep that follows the first read
        // comes back to this notification.
        val blockers = activeBlockers ?: return
        // While no blocker is on, a notification is not even looked at.
        if (blockers.isEmpty || !canBeDismissed(sbn)) return

        val receivedAt = System.currentTimeMillis()
        val hiding = blockers.decideAbout(shown(sbn), hiddenAtMillis = receivedAt) {
            // Only run for a summary that matches. The children have to be
            // listed before the summary is dismissed: afterwards Android may
            // already have removed them. If listing them fails, the summary
            // is dismissed all the same.
            guarded(sbn.packageName) { dismissibleChildrenOf(sbn) }.orEmpty()
        }
        if (hiding == null) {
            log(sbn, "left alone")
            return
        }

        // Only the notification itself. If it is a summary, Android
        // dismisses its children.
        cancelNotification(sbn.key)
        // The first number is Android's share of the delay and the second is
        // this app's. The first is not all time on screen: Android may hold
        // a notification back before anything sees it (200 ms on the
        // emulator). In a sweep it is how long the notification had been
        // showing.
        val sincePosted = receivedAt - sbn.postTime
        val sinceReceived = System.currentTimeMillis() - receivedAt
        log(sbn, "arrived $sincePosted ms after it was posted, dismissed $sinceReceived ms later")

        if (sbn.isGroupSummary) {
            dismissedGroups.remember(
                groupKey = sbn.groupKey,
                blockedBy = hiding.blockedBy,
                listedKeys = hiding.entries.map { it.notificationKey },
                nowMillis = SystemClock.elapsedRealtime(),
            )
            log(sbn, "a summary: its group goes with it")
        } else {
            // This may be a child that was posted just as its summary was
            // dismissed. Android will report it as gone with its summary
            // (see onNotificationRemoved), and it has its entry already.
            dismissedGroups.markListed(sbn.groupKey, sbn.key)
        }
        record(hiding.entries)
    }

    private fun canBeDismissed(sbn: StatusBarNotification): Boolean =
        // Never this app's own notifications.
        sbn.packageName != packageName &&
            // Android does not let a listener dismiss an "ongoing"
            // notification: music that is playing, a call, a download.
            // Asking would do nothing, and the hidden list would then claim
            // that something was hidden which is still there.
            !sbn.isOngoing

    private fun dismissibleChildrenOf(summary: StatusBarNotification): List<ShownNotification> =
        activeNotifications.orEmpty()
            .filter { sbn ->
                // The group key holds the user, the package and the group's
                // name, so it can only be equal within one app.
                sbn.groupKey == summary.groupKey && !sbn.isGroupSummary && canBeDismissed(sbn)
            }
            // A child that cannot be read is left out of the list. Android
            // dismisses it all the same and reports it afterwards, and it
            // gets its entry then if it can be read at all.
            .mapNotNull { child -> guarded(child.packageName) { shown(child) } }

    /** Writes [entries] to the hidden list, in one go and on a background thread. */
    private fun record(entries: List<HiddenNotification>) {
        if (entries.isEmpty()) return
        // applicationScope and not this service's own scope: the entries
        // must still be written if Android destroys the service straight
        // after.
        container.applicationScope.launch(storageDispatcher) {
            // Neither of the two failures may crash the process, because
            // that would stop the blocking too. The entries are lost. As
            // everywhere in this class, the log gets the kind of exception
            // and not its message.
            try {
                container.hiddenNotificationRepository.addAll(entries)
            } catch (e: IOException) {
                // The disk: it is full, for example.
                Log.w(TAG, "Could not store hidden notifications (${e.javaClass.simpleName})")
            } catch (e: IllegalStateException) {
                // The file was written by a newer build of the app. The
                // hidden list screen still fails loudly on such a file,
                // which is the signal meant for that developer-only case.
                Log.w(TAG, "Could not store hidden notifications (${e.javaClass.simpleName})")
            }
        }
    }

    private fun shown(sbn: StatusBarNotification) =
        ShownNotification(sbn.key, readContent(sbn), sbn.isGroupSummary)

    /**
     * Copies every piece of text out of [sbn] that could carry a spoiler.
     *
     * Each part is read by itself, through `readPart`. A part that is not
     * there, or that cannot be read, is left empty, and the rest is still
     * read and matched.
     */
    private fun readContent(sbn: StatusBarNotification): NotificationContent {
        val notification = sbn.notification
        // An app describes its notification in "extras": a bag of values
        // under keys that the Notification class names.
        val extras: Bundle? = notification.extras

        // Text is stored as a CharSequence because it may carry styling.
        // textOf drops the styling.
        fun text(key: String): String = readPart("") { textOf(extras?.getCharSequence(key)) }

        return NotificationContent(
            packageName = sbn.packageName,
            appName = readPart(sbn.packageName) { appNameOf(sbn.packageName) },
            title = text(Notification.EXTRA_TITLE),
            bigTitle = text(Notification.EXTRA_TITLE_BIG),
            text = text(Notification.EXTRA_TEXT),
            bigText = text(Notification.EXTRA_BIG_TEXT),
            subText = text(Notification.EXTRA_SUB_TEXT),
            summaryText = text(Notification.EXTRA_SUMMARY_TEXT),
            infoText = text(Notification.EXTRA_INFO_TEXT),
            conversationTitle = text(Notification.EXTRA_CONVERSATION_TITLE),
            lines = readPart(emptyList()) {
                textsOf(extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES))
            },
            messages = readPart(emptyList()) {
                Notification.MessagingStyle.Message
                    .getMessagesFromBundleArray(
                        extras?.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java),
                    )
                    .map { message ->
                        NotificationContent.Message(
                            sender = textOf(message.senderPerson?.name),
                            text = textOf(message.text),
                        )
                    }
            },
            tickerText = textOf(notification.tickerText),
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
