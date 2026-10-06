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

/** True if the owner lets the app post notifications. */
fun canPostNotifications(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

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

/** Opens the system settings screen for this app's notifications. */
fun openAppNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )
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
 * blocker is on. Android's dialog is shown once. After a refusal there is
 * only the card, which stays for as long as a blocker is on.
 *
 * @param refusedBefore see [wasPostPermissionRefused].
 */
fun postPermissionStep(
    blockerOn: Boolean,
    canPost: Boolean,
    refusedBefore: Boolean,
    request: PostPermissionRequest,
): PostPermissionStep = when {
    // With no blocker on there is no status notification to post.
    !blockerOn || canPost -> PostPermissionStep.NOTHING
    // The dialog is in front. The card would show through behind it.
    request == PostPermissionRequest.ASKING -> PostPermissionStep.NOTHING
    request == PostPermissionRequest.NOT_ASKED && !refusedBefore -> PostPermissionStep.ASK
    // Refused before or just now, or the dialog was closed without an answer.
    else -> PostPermissionStep.SHOW_CARD
}
