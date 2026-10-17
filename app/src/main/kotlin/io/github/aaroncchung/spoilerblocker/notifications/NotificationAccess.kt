package io.github.aaroncchung.spoilerblocker.notifications

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService

// "Notification access" is a permission that only the owner can give, on a
// screen in the system settings. An app can ask whether it has it and can
// open that screen, and nothing more.

private fun listenerComponent(context: Context) =
    ComponentName(context, SpoilerNotificationListener::class.java)

/** True if the owner has given the app notification access. */
fun isNotificationAccessGranted(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(listenerComponent(context))

/**
 * Opens the settings screen with this app's notification access switch. If
 * the phone has no such screen, opens the list of every app that asks for
 * notification access.
 */
fun openNotificationAccessSettings(context: Context) {
    val thisApp = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
        listenerComponent(context).flattenToString(),
    )
    try {
        context.startActivity(thisApp)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

/**
 * Asks Android to connect the listener if the app has notification access
 * but the listener is not connected.
 *
 * This is all an app is allowed to do about it, and it is a weak tool:
 * Android acts on the request only for a listener that was set aside with
 * `requestUnbind`, which this app never calls. In every other case Android
 * reconnects by itself, or the owner has to switch notification access off
 * and on again.
 */
fun requestListenerRebindIfDisconnected(context: Context) {
    if (isNotificationAccessGranted(context) && !SpoilerNotificationListener.isConnected) {
        NotificationListenerService.requestRebind(listenerComponent(context))
    }
}
