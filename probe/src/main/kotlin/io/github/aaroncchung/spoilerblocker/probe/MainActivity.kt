package io.github.aaroncchung.spoilerblocker.probe

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * The probe's only screen: a section per experiment, with the switches that
 * can be set before going to another app, and a live view of the log.
 *
 * Unlike the real app, the wording is written here rather than in
 * strings.xml. The probe is thrown away after Phase 0, and keeping each label
 * next to the control it describes makes this file easier to follow.
 */
class MainActivity : ComponentActivity() {

    private var system by mutableStateOf<SystemState?>(null)
    private var summary by mutableStateOf(listOf("Reading the log..."))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                ProbeScreen(system ?: readSystemState(this), summary, ::refreshSummary)
            }
        }

        // Keep the screen up to date, but only while it is in front. The
        // block below starts each time the activity is resumed and is
        // cancelled when it is paused. Left running in the background it
        // would give the process work to do all night, and E6 is about what
        // happens to a process that has none.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var round = 0
                while (true) {
                    // Settings can change behind the app's back, in Settings
                    // itself or by adb, and Android does not announce it.
                    system = readSystemState(this@MainActivity)
                    // A summary read last night must not be taken for this
                    // morning's, so it is worked out again on every return
                    // to this screen and every half minute after that.
                    if (round % SUMMARY_EVERY_ROUNDS == 0) refreshSummary()
                    round++
                    delay(REFRESH_MS)
                }
            }
        }
    }

    /** Reads the log and works out the E6 summary afresh. */
    private fun refreshSummary() {
        ProbeLog.readAll { records ->
            val now = LogRecord(
                System.currentTimeMillis(), SystemClock.uptimeMillis(), SystemClock.elapsedRealtime(),
                Process.myPid(), E6.TAG, "",
            )
            val asOf = LocalTime.now().truncatedTo(ChronoUnit.SECONDS)
            summary = listOf("As of $asOf:") + HeartbeatSummary.describe(HeartbeatSummary.compute(records, now))
        }
    }

    private companion object {
        const val REFRESH_MS = 2_000L
        const val SUMMARY_EVERY_ROUNDS = 15
    }
}

/** The answers to "is it switched on in Settings?", which the app has to ask Android for. */
private data class SystemState(
    val accessibilityEnabled: Boolean,
    val listenerEnabled: Boolean,
    val notificationsAllowed: Boolean,
    val battery: String,
    val bucket: String,
)

private fun readSystemState(context: Context) = SystemState(
    accessibilityEnabled = DeviceState.isAccessibilityServiceEnabled(context),
    listenerEnabled = DeviceState.isNotificationListenerEnabled(context),
    notificationsAllowed = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
    battery = DeviceState.batteryMode(context),
    bucket = DeviceState.standbyBucket(context),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProbeScreen(system: SystemState, summary: List<String>, refreshSummary: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("SB Probe") }) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SetupSection(system)
            TreeDumpSection()
            BoxSection()
            TouchSection()
            NotificationSection()
            ScreenshotSection()
            KeepAliveSection(system, summary, refreshSummary)
            LogSection()
        }
    }
}

@Composable
private fun SetupSection(system: SystemState) {
    val context = LocalContext.current
    val askForNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    SectionTitle("Setup")
    StatusLine(
        "Accessibility service",
        when {
            ProbeAccessibilityService.connected -> "ON and connected"
            system.accessibilityEnabled -> "ON in Settings but NOT connected"
            else -> "off"
        },
    )
    Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
        Text("Open accessibility settings")
    }
    StatusLine(
        "Notification access",
        when {
            ProbeNotificationListener.connected -> "ON and connected"
            system.listenerEnabled -> "ON in Settings but NOT connected"
            else -> "off"
        },
    )
    Button(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
        Text("Open notification access settings")
    }
    StatusLine("Probe may show notifications", if (system.notificationsAllowed) "yes" else "NO")
    if (!system.notificationsAllowed) {
        Button(onClick = { askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
            Text("Allow notifications")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxSection() {
    SectionTitle("E2: box on a list item")
    Text(
        "Open an app with a list. The box appears on one item. Use the SB Probe notification " +
            "(Next item, Next list, Next mech.) or adb to control it from there.",
    )
    SwitchRow("Show the box", ProbeSettings.boxEnabled) {
        ProbeSettings.updateBoxEnabled(it)
        ProbeAccessibilityService.applySettings()
    }
    Text("How the box is drawn", style = MaterialTheme.typography.labelLarge)
    for (mechanism in OverlayMechanism.entries) {
        RadioRow(
            "${mechanism.id} (${mechanism.colourName}): ${mechanism.summary}",
            selected = ProbeSettings.mechanism == mechanism,
        ) {
            ProbeSettings.updateMechanism(mechanism)
            ProbeAccessibilityService.applySettings()
        }
    }
    Text("What makes the box move", style = MaterialTheme.typography.labelLarge)
    RadioRow("events: when the app reports a scroll", ProbeSettings.trackingMode == TrackingMode.EVENTS) {
        ProbeSettings.updateTrackingMode(TrackingMode.EVENTS)
        ProbeAccessibilityService.applySettings()
    }
    RadioRow("poll: ask the app where the item is on every frame", ProbeSettings.trackingMode == TrackingMode.POLL) {
        ProbeSettings.updateTrackingMode(TrackingMode.POLL)
        ProbeAccessibilityService.applySettings()
    }
    SwitchRow("Film strip: clock, touch and scroll markers", ProbeSettings.filmStripEnabled) {
        ProbeSettings.updateFilmStripEnabled(it)
        ProbeAccessibilityService.applySettings()
    }
    Text(
        "A summary covers every box move since the last reset. Reset after each filmed clip, or clips " +
            "run together. The Summary button on the notification always resets.",
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            ProbeAccessibilityService.trigger(ProbeActions.E2_SUMMARY, Intent().putExtra(ProbeActions.EXTRA_RESET, true))
        }) {
            Text("Summary, then reset")
        }
        Button(onClick = { ProbeAccessibilityService.trigger(ProbeActions.E2_SUMMARY, Intent()) }) {
            Text("Summary, keep counting")
        }
    }
}

@Composable
private fun TouchSection() {
    SectionTitle("E3: when does a touch become known?")
    SwitchRow("Outside-touch watcher (should not disturb scrolling)", ProbeSettings.touchWatchEnabled) {
        ProbeSettings.updateTouchWatchEnabled(it)
        ProbeAccessibilityService.applySettings()
    }
    Text(
        "The next test asks Android for the touch screen's events for 10 seconds. Press the button, " +
            "then try to scroll this screen. If it does not move, the service has taken the touches. " +
            "It turns itself off.",
    )
    Button(onClick = { ProbeAccessibilityService.trigger(ProbeActions.MOTION_LISTEN, Intent()) }) {
        Text("Listen to motion events for 10 s")
    }
}

@Composable
private fun NotificationSection() {
    val context = LocalContext.current
    SectionTitle("E4: dismissing a notification")
    SwitchRow("Dismiss every notification from this app", ProbeSettings.cancelEnabled) {
        ProbeSettings.updateCancelEnabled(it)
    }
    OutlinedTextField(
        value = ProbeSettings.cancelPackage,
        onValueChange = { ProbeSettings.updateCancelPackage(it) },
        label = { Text("Package name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "The probe's own package is ${context.packageName}. For any other app, dismissing switches " +
            "itself back to the probe after ${DismissTimeLimit.LIMIT_MS / 60_000} minutes: a dismissed " +
            "notification is gone for good and the probe keeps no copy.",
    )
    ProbeSettings.foreignTargetWarning()?.let { warning ->
        Text(warning, color = MaterialTheme.colorScheme.error)
    }
    Text("Posting a test switches the film strip off, because the strip could hide the banner. Film the top of the screen, then:")
    Button(onClick = { ProbeNotifications.postTestAfter(context, 3000) }) {
        Text("Post a test notification in 3 s")
    }
}

@Composable
private fun TreeDumpSection() {
    SectionTitle("E1: what text does an app expose?")
    Text(
        "Acts on another app, so it is started from there: with the app in front, press Dump tree on " +
            "the SB Probe notification, or run the probe.ps1 script with \"dump <label>\" on the PC. The file stays " +
            "in this app's private storage. See docs/PROBE.md.",
    )
}

@Composable
private fun ScreenshotSection() {
    SectionTitle("E5: is the box in a per-window screenshot?")
    Text(
        "Also started from the other app, with the box showing: press Screenshot or Rate test on the " +
            "SB Probe notification, or use the PC. The verdict is written to the log and the images stay " +
            "in this app's private storage.",
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeepAliveSection(system: SystemState, summary: List<String>, refreshSummary: () -> Unit) {
    val context = LocalContext.current
    SectionTitle("E6: does it stay alive?")
    StatusLine("Battery setting", "${system.battery} (standby bucket: ${system.bucket})")
    Text(summary.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = refreshSummary) { Text("Refresh") }
        Button(onClick = {
            ProbeApp.logRunStart(context, "")
            refreshSummary()
        }) {
            Text("Start a new run")
        }
        Button(onClick = {
            val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
            context.startActivity(appDetails)
        }) {
            Text("Open app settings (battery)")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogSection() {
    val context = LocalContext.current
    SectionTitle("Log (newest first, since the app last started)")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { ProbeLog.clear() }) { Text("Clear log") }
        Button(onClick = {
            File(context.filesDir, TreeDump.DIRECTORY).deleteRecursively()
            File(context.filesDir, ScreenshotProbe.DIRECTORY).deleteRecursively()
            ProbeLog.log("APP", "dumps and screenshots deleted")
        }) {
            Text("Delete dumps and screenshots")
        }
    }
    // A fixed height with its own scrolling, so the log does not push the rest of the page away.
    Text(
        ProbeLog.tail.asReversed().joinToString("\n"),
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .verticalScroll(rememberScrollState()),
    )
}

@Composable
private fun SectionTitle(text: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun StatusLine(label: String, value: String) {
    Text("$label: $value")
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.weight(1f))
    }
}
