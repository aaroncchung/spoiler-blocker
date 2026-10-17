package io.github.aaroncchung.spoilerblocker.status

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

// Since Android 13 an app may post notifications only if the owner allows it.
// The app asks with a dialog that Android draws. After a refusal the owner
// can still allow it, on the app's notification screen in the system
// settings.

/**
 * True if the owner lets the app post notifications. This is about the app
 * as a whole: see also [isStatusChannelBlocked].
 */
fun canPostNotifications(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

/**
 * True if the owner has switched off the channel of the status notification,
 * "Blocking status" in the system settings. The app may still post, but
 * Android drops whatever is posted to that channel.
 */
fun isStatusChannelBlocked(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java)
        // Null before the channel is created, which StatusNotifier does
        // when the process starts.
        .getNotificationChannel(StatusNotifier.CHANNEL_ID)
        ?.importance == NotificationManager.IMPORTANCE_NONE

/**
 * True if the owner has refused the permission: in the dialog, or by
 * switching the app's notifications off in the system settings.
 *
 * Android has no call that says "refused". This one is meant for something
 * else, for apps that explain themselves before they ask a second time, but
 * it is true after one refusal. After a second refusal it is false again:
 * from then on Android answers "no" by itself without showing its dialog.
 */
fun wasPostPermissionRefused(activity: Activity): Boolean =
    activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)

/**
 * Opens the system settings screen where the owner can switch the status
 * notification back on: the screen of its channel if only that is off, or
 * else the screen for all of this app's notifications.
 */
fun openStatusNotificationSettings(context: Context) {
    val intent = if (canPostNotifications(context) && isStatusChannelBlocked(context)) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, StatusNotifier.CHANNEL_ID)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    context.startActivity(intent)
}

/** How far the blocker list has got with asking for the permission, since it was opened. */
enum class PostPermissionRequest { NOT_ASKED, ASKING, ANSWERED }

/** What the blocker list does about the permission to post notifications. */
enum class PostPermissionStep {
    NOTHING,

    /** Show Android's dialog. */
    ASK,

    /** Show the card that says the status notification cannot be shown. */
    SHOW_CARD,
}

/**
 * Decides what the blocker list does about the permission to post
 * notifications.
 *
 * The owner is asked at the moment the permission starts to matter: when a
 * blocker is on. Each time the blocker list is opened, Android's dialog is
 * shown at most once, and after a refusal not at all: then there is only the
 * card, which stays for as long as a blocker is on. A dialog closed without
 * an answer is not a refusal. The card shows until the list is next opened,
 * and then the dialog shows again.
 *
 * The card also shows when the app may post but the owner has switched off
 * the status notification's channel. The dialog would be no use then: it is
 * about the app as a whole, and Android answers "yes" without showing it.
 *
 * @param canPost see [canPostNotifications].
 * @param statusChannelBlocked see [isStatusChannelBlocked].
 * @param refusedBefore see [wasPostPermissionRefused].
 */
fun postPermissionStep(
    blockerOn: Boolean,
    canPost: Boolean,
    statusChannelBlocked: Boolean,
    refusedBefore: Boolean,
    request: PostPermissionRequest,
): PostPermissionStep = when {
    // With no blocker on there is no status notification to post.
    !blockerOn -> PostPermissionStep.NOTHING
    canPost && statusChannelBlocked -> PostPermissionStep.SHOW_CARD
    canPost -> PostPermissionStep.NOTHING
    // The dialog is in front. The card would show through behind it.
    request == PostPermissionRequest.ASKING -> PostPermissionStep.NOTHING
    request == PostPermissionRequest.NOT_ASKED && !refusedBefore -> PostPermissionStep.ASK
    // Refused before or just now, or the dialog was closed without an answer.
    else -> PostPermissionStep.SHOW_CARD
}
