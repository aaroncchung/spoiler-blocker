package io.github.aaroncchung.spoilerblocker.probe

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings

/** Questions about the phone's settings that E6 needs answered. */
object DeviceState {

    /**
     * The app's battery setting, in the words One UI uses for its three
     * choices: "unrestricted", "optimised" or "restricted".
     */
    fun batteryMode(context: Context): String {
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        return when {
            power.isIgnoringBatteryOptimizations(context.packageName) -> "unrestricted"
            activity.isBackgroundRestricted -> "restricted"
            else -> "optimised"
        }
    }

    /**
     * Android sorts apps into "standby buckets" by how recently they were
     * used. The rarer the bucket, the more the app's background work is held
     * back.
     */
    fun standbyBucket(context: Context): String {
        val bucket = context.getSystemService(UsageStatsManager::class.java).appStandbyBucket
        return STANDBY_BUCKET_NAMES[bucket] ?: "bucket-$bucket"
    }

    private val STANDBY_BUCKET_NAMES = mapOf(
        UsageStatsManager.STANDBY_BUCKET_ACTIVE to "active",
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET to "working-set",
        UsageStatsManager.STANDBY_BUCKET_FREQUENT to "frequent",
        UsageStatsManager.STANDBY_BUCKET_RARE to "rare",
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED to "restricted",
        // Two values Android uses but does not name in its public API.
        5 to "exempted",
        50 to "never-used",
    )

    /** True if the accessibility service is switched on in Settings. It may still not be running. */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val wanted = ComponentName(context, ProbeAccessibilityService::class.java)
        // The setting is a colon-separated list of "package/class" names.
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == wanted }
    }

    /** True if notification access is switched on in Settings. */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val listener = ComponentName(context, ProbeNotificationListener::class.java)
        return context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)
    }

    /** "on" or "off", plus whether Android's doze mode is active. For heartbeat lines. */
    fun screenAndDoze(context: Context): String {
        val power = context.getSystemService(PowerManager::class.java)
        return "screen=${if (power.isInteractive) "on" else "off"} doze=${power.isDeviceIdleMode}"
    }

    /**
     * Whether each service is switched on in Settings, for heartbeat lines.
     * Android switches an accessibility service off in Settings when its app
     * is force-stopped. If that happens overnight, the other service's
     * heartbeat shows when.
     */
    fun switchesInSettings(context: Context): String {
        fun onOff(on: Boolean) = if (on) "on" else "off"
        return "a11y-setting=${onOff(isAccessibilityServiceEnabled(context))} " +
            "nls-setting=${onOff(isNotificationListenerEnabled(context))}"
    }
}
