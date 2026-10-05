package io.github.aaroncchung.spoilerblocker.probe

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The probe's accessibility service. Android starts it when the owner turns
 * "SB Probe" on under Settings > Accessibility, and from then on sends it
 * events from whichever app is in front.
 *
 * What it does, by experiment:
 * - E1: writes the tree of on-screen elements to a private file, on request.
 * - E2: keeps a coloured box on one list item and logs how late each move was.
 * - E3: logs every signal that a finger went down or a scroll began.
 * - E5: takes a per-window screenshot, on request, and checks it for the box.
 * - E6: writes a heartbeat line every minute and logs every lifecycle call.
 *
 * It never logs text from the screen. Only the E1 dump contains any.
 *
 * Everything here runs on the main thread: Android delivers accessibility
 * events there, and windows may only be touched from there.
 */
class ProbeAccessibilityService : AccessibilityService() {

    /** The list item the box follows. */
    private class Target(val node: AccessibilityNodeInfo, val windowId: Int)

    /** What a pick should choose. */
    private enum class Pick {
        /** The middle item of the list that looks most like the feed. */
        BEST_GUESS,

        /** The middle item of the list picked from last time. Used when the item has gone. */
        SAME_LIST,

        /** The item after the current one, in the same list. */
        NEXT_ITEM,

        /** The middle item of the next scrollable element on the screen. */
        NEXT_LIST,
    }

    /** The timing of one box update, filled in as each stage reports back. All times are uptime ms. */
    private class Trace(
        val number: Int,
        val mechanism: OverlayMechanism,
        val trigger: String,
        val eventTime: Long,
        val received: Long,
        val boundsRead: Long,
        val top: Int,
        val moved: Int?,
        val scrollDeltaY: Int?,
        val wantsFrame: Boolean,
        val wantsCommit: Boolean,
    ) {
        var submitted = 0L
        var frame: Long? = null
        var committed: Long? = null
        var logged = false
    }

    /** One report that a finger went down. */
    private class Touch(val number: Int, val source: String, val downTime: Long, val received: Long) {
        /** The kinds of event already matched to this touch, so each is logged once. */
        val matched = HashSet<String>()
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var heartbeat: Heartbeat
    private lateinit var filmStrip: FilmStrip
    private lateinit var touchWatcher: OutsideTouchWatcher
    private lateinit var screenshots: ScreenshotProbe

    // E2
    private var overlay: BoxOverlay? = null
    private var overlayMechanism: OverlayMechanism? = null
    private var target: Target? = null
    private var pickedWindowId = -1
    private var listIndex = 0
    private var itemIndex = 0
    private var lastBox: Rect? = null

    /** True while the notification shade or a system dialog covers the app. The box is hidden meanwhile. */
    private var systemUiInFront = false
    private var updateCount = 0
    private val lagSamples = ArrayList<LagSample>()
    private var polling = false

    // E3
    private var touchCount = 0
    private var lastTouch: Touch? = null
    private var motionListening = false
    private var motionEventsSeen = 0
    private var scrollEventsWhileListening = 0

    // ---- Lifecycle (every call is logged for E6) ----

    override fun onCreate() {
        super.onCreate()
        heartbeat = Heartbeat(this, E6.A11Y)
        ProbeLog.log(E6.TAG, "${E6.A11Y} created")
    }

    override fun onServiceConnected() {
        instance = this
        connected = true
        // Overlay windows need the service's window token, which only exists
        // from this point on.
        filmStrip = FilmStrip(this)
        touchWatcher = OutsideTouchWatcher(this) { downTime, received -> onTouchSignal("outside-touch", downTime, received) }
        screenshots = ScreenshotProbe(this)
        ProbeLog.log(E6.TAG, "${E6.A11Y} ${E6.CONNECTED}")
        heartbeat.start()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        applySettingsNow()
        // An app may already be in front, with no window change coming to announce it.
        handler.postDelayed(frontCheck, FRONT_CHECK_DELAY_MS)
    }

    override fun onInterrupt() {
        ProbeLog.log(E6.TAG, "${E6.A11Y} interrupt")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ProbeLog.log(E6.TAG, "${E6.A11Y} ${E6.DISCONNECTED} (onUnbind)")
        shutDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ProbeLog.log(E6.TAG, "${E6.A11Y} destroyed")
        shutDown()
        super.onDestroy()
    }

    /** Undoes onServiceConnected. Safe to call twice, because Android calls both onUnbind and onDestroy. */
    private fun shutDown() {
        if (!connected) return
        connected = false
        instance = null
        heartbeat.stop()
        handler.removeCallbacksAndMessages(null)
        polling = false
        unregisterReceiver(screenReceiver)
        // The window token may already be invalid here, in which case removing
        // a window throws. The windows die with the token anyway.
        runCatching {
            overlay?.destroy()
            filmStrip.hide()
            touchWatcher.stop()
        }
        overlay = null
        overlayMechanism = null
        target = null
        ProbeNotifications.hideControls(this)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                // Nothing to film with the screen off. Stop everything that
                // works every frame, so an overnight E6 run is not disturbed.
                setPolling(false)
                filmStrip.hide()
                setMotionListening(false)
            } else {
                applySettingsNow()
            }
        }
    }

    // ---- Settings ----

    private fun applySettingsNow() {
        if (ProbeSettings.touchWatchEnabled) touchWatcher.start() else touchWatcher.stop()
        if (ProbeSettings.filmStripEnabled) filmStrip.show() else filmStrip.hide()

        val mechanism = ProbeSettings.mechanism
        if (overlayMechanism != mechanism) {
            overlay?.destroy()
            overlay = createBoxOverlay(this, mechanism)
            overlayMechanism = mechanism
            lastBox = null
            ProbeLog.log("E2", "mechanism=${mechanism.id} (${mechanism.colourName} box): ${mechanism.summary}")
        }
        if (ProbeSettings.boxEnabled) {
            redrawBox()
        } else {
            overlay?.hide()
            lastBox = null
        }
        setPolling(ProbeSettings.boxEnabled && ProbeSettings.trackingMode == TrackingMode.POLL)

        ProbeNotifications.showControls(
            this,
            "${mechanism.id} (${mechanism.colourName}), tracking by ${ProbeSettings.trackingMode.id}",
        )
    }

    // ---- Events ----

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val received = SystemClock.uptimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                filmStrip.markScrollEvent()
                if (motionListening) scrollEventsWhileListening++
                onContentMoved("VIEW_SCROLLED", event, received, event.scrollDeltaY)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onContentMoved("CONTENT_CHANGED", event, received, null)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // A new screen or app. Wait for it to settle, then look at it once.
                handler.removeCallbacks(frontCheck)
                handler.postDelayed(frontCheck, FRONT_CHECK_DELAY_MS)
            }
            else ->
                // The touch and gesture event types. Android is only expected
                // to send them while a screen reader's "explore by touch" is
                // on, but if they do arrive, E3 wants to know.
                ProbeLog.log(
                    "E3",
                    "accessibility event ${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                        "evt=${event.eventTime} recv=+${received - event.eventTime}ms",
                )
        }
    }

    private fun onContentMoved(kind: String, event: AccessibilityEvent, received: Long, scrollDeltaY: Int?) {
        val current = target
        // Events from other windows (the status bar clock, for one) say nothing about the watched list.
        if (current != null && event.windowId != current.windowId) return
        if (systemUiInFront) {
            // The watched app is active while the shade was thought to be
            // open. Android does not always announce the shade closing, so
            // look again; the box comes back if the app is in front.
            handler.removeCallbacks(frontCheck)
            handler.post(frontCheck)
        }
        matchWithTouch(kind, event.eventTime, received)
        if (current != null && ProbeSettings.trackingMode == TrackingMode.EVENTS) {
            updateBox(kind, event.eventTime, received, scrollDeltaY)
        }
    }

    private val frontCheck = Runnable {
        val root = rootInActiveWindow
        val frontPackage = root?.packageName?.toString()
        systemUiInFront = frontPackage == SYSTEM_UI_PACKAGE
        when {
            // Android gives a service that is not an assistive tool nothing
            // at all for some windows, its own permission dialogs among them.
            // Whatever the box was on is no longer in front.
            root == null -> clearTarget("the window in front cannot be read by this service")
            // The notification shade or a system dialog. The app is still
            // underneath, so remember the item, but take the box away: it
            // is drawn above the shade and would cover the probe's buttons.
            systemUiInFront -> {
                overlay?.hide()
                lastBox = null
            }
            // The probe's own screen: a box here would only cover the controls.
            frontPackage == packageName -> clearTarget("the probe's own screen is in front")
            target?.windowId != root.windowId -> pick(root, Pick.BEST_GUESS)
            else -> redrawBox()
        }
    }

    /** Puts the box back on the item, for when it was hidden or its look has changed. */
    private fun redrawBox() {
        val now = SystemClock.uptimeMillis()
        updateBox("settings", now, now, null)
    }

    // ---- E2: picking the item and moving the box ----

    /**
     * Chooses a list item in the window under [root] and puts the box on it.
     *
     * Which element of a screen is "the feed" is a guess (see [findLists]),
     * and the first item picked may be a poor one to film. So a trigger can
     * ask for the next item of the same list, or for the next list.
     */
    private fun pick(root: AccessibilityNodeInfo, choice: Pick) {
        val lists = findLists(root)
        if (lists.isEmpty()) {
            clearTarget("no scrollable list found in ${root.packageName}")
            return
        }
        // "Next" only means something if the last pick was in this same window.
        val sameWindow = root.windowId == pickedWindowId
        listIndex = when {
            !sameWindow || choice == Pick.BEST_GUESS -> 0
            choice == Pick.NEXT_LIST -> (listIndex + 1) % lists.size
            else -> listIndex.coerceAtMost(lists.lastIndex)
        }
        val list = lists[listIndex]
        val items = itemsWorthBoxing(list)
        itemIndex = if (sameWindow && choice == Pick.NEXT_ITEM) {
            (itemIndex + 1) % items.size
        } else {
            // The middle item stays on screen longest, whichever way the list is scrolled.
            items.size / 2
        }
        pickedWindowId = root.windowId
        target = Target(items[itemIndex], root.windowId)
        lastBox = null
        // Class names and view ids describe structure. They are not screen text.
        ProbeLog.log(
            "E2",
            "picked item ${itemIndex + 1} of ${items.size} in list ${listIndex + 1} of ${lists.size} " +
                "(${list.className} id=${list.viewIdResourceName}) window=${root.windowId} package=${root.packageName}",
        )
        val now = SystemClock.uptimeMillis()
        updateBox("pick", now, now, null)
    }

    /**
     * Every scrollable element under [root] that has an item showing, with the
     * best guess at "the feed" first and the rest from largest to smallest.
     *
     * The guess is the innermost scrollable element that is still large and
     * shows two or more items. "Large" rules out a row of thumbnails inside
     * the feed. "Innermost" rules out the page frame around the feed, which
     * apps often report as scrollable too. "Two or more items" rules out a
     * pager showing one page at a time.
     */
    private fun findLists(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        class Candidate(val node: AccessibilityNodeInfo, val area: Long, val items: Int)

        // Breadth first, so later candidates are deeper in the tree.
        val candidates = ArrayList<Candidate>()
        val bounds = Rect()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SEARCH_NODES) {
            val node = queue.removeFirst()
            visited++
            val children = (0 until node.childCount).mapNotNull { node.getChild(it) }
            if (node.isScrollable && node.isVisibleToUser) {
                val items = children.count { it.isVisibleToUser }
                if (items > 0) {
                    node.getBoundsInScreen(bounds)
                    candidates += Candidate(node, bounds.width().toLong() * bounds.height(), items)
                }
            }
            queue.addAll(children)
        }

        val largestArea = candidates.filter { it.items >= 2 }.maxOfOrNull { it.area } ?: 0L
        val bestGuess = candidates.lastOrNull { it.items >= 2 && it.area * 2 >= largestArea }
        val others = candidates.filter { it !== bestGuess }.sortedByDescending { it.area }
        return (listOfNotNull(bestGuess) + others).map { it.node }
    }

    /**
     * The showing items of [list], without the thin ones. Feeds put dividers
     * and spacers between posts as items of their own, and a box a few pixels
     * tall is no use on film. If every item is thin, all of them are returned.
     */
    private fun itemsWorthBoxing(list: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val showing = (0 until list.childCount).mapNotNull { list.getChild(it) }.filter { it.isVisibleToUser }
        val minimumHeight = MIN_ITEM_HEIGHT_DP * resources.displayMetrics.density
        val bounds = Rect()
        val tallEnough = showing.filter {
            it.getBoundsInScreen(bounds)
            bounds.height() >= minimumHeight
        }
        return tallEnough.ifEmpty { showing }
    }

    private fun clearTarget(reason: String) {
        if (target != null) ProbeLog.log("E2", "box removed: $reason")
        target = null
        lastBox = null
        overlay?.hide()
    }

    /**
     * Asks the watched app where the item is now and moves the box there.
     *
     * [eventTime] is when the thing that caused this update happened and
     * [received] is when this service heard of it. For an accessibility event
     * the first is stamped by the watched app when it sent the event, which
     * can be up to 100 ms after the content actually moved: Android makes
     * apps hold scroll events back so that there are at most ten a second.
     */
    private fun updateBox(trigger: String, eventTime: Long, received: Long, scrollDeltaY: Int?) {
        if (!ProbeSettings.boxEnabled || systemUiInFront) return
        val current = target ?: return
        val box = overlay ?: return
        // The mechanism really in use. The setting can be a moment ahead of it.
        val mechanism = overlayMechanism ?: return

        // refresh() re-reads the node from the other app. It blocks until
        // that app's main thread answers, and returns false if the view is gone.
        if (!current.node.refresh()) {
            clearTarget("the item's view no longer exists")
            if (trigger != "pick") rootInActiveWindow?.let { if (it.windowId == current.windowId) pick(it, Pick.SAME_LIST) }
            return
        }
        val boundsRead = SystemClock.uptimeMillis()
        val onScreen = Rect().also { current.node.getBoundsInScreen(it) }
        val inWindow = Rect().also { current.node.getBoundsInWindow(it) }

        if (!current.node.isVisibleToUser || onScreen.isEmpty) {
            if (lastBox != null) {
                box.hide()
                lastBox = null
                ProbeLog.log("E2", "item scrolled out of view; box hidden until it returns")
            }
            return
        }
        if (onScreen == lastBox) return
        val moved = lastBox?.let { onScreen.top - it.top }
        lastBox = onScreen

        val trace = Trace(
            number = ++updateCount,
            mechanism = mechanism,
            trigger = trigger,
            eventTime = eventTime,
            received = received,
            boundsRead = boundsRead,
            top = onScreen.top,
            moved = moved,
            scrollDeltaY = scrollDeltaY,
            wantsFrame = box.drawsInOwnFrames,
            wantsCommit = box.reportsCommit,
        )
        box.moveTo(BoxTarget(current.windowId, onScreen, inWindow)) { committedAt ->
            // Called on another thread. The time was taken there; the rest happens here.
            handler.post {
                trace.committed = committedAt
                logTraceIfComplete(trace)
            }
        }
        trace.submitted = SystemClock.uptimeMillis()
        if (trace.wantsFrame) {
            if (trigger == "poll") {
                // A poll runs at the start of a frame, and the box is redrawn
                // later in that same frame.
                trace.frame = eventTime
            } else {
                Choreographer.getInstance().postFrameCallback {
                    trace.frame = SystemClock.uptimeMillis()
                    logTraceIfComplete(trace)
                }
            }
        }
        // If a stage never reports, log what there is.
        handler.postDelayed({ logTrace(trace) }, TRACE_TIMEOUT_MS)
    }

    private fun logTraceIfComplete(trace: Trace) {
        val frameDone = !trace.wantsFrame || trace.frame != null
        val commitDone = !trace.wantsCommit || trace.committed != null
        if (frameDone && commitDone) logTrace(trace)
    }

    private fun logTrace(trace: Trace) {
        if (trace.logged) return
        trace.logged = true
        fun after(time: Long?) = if (time == null) "-" else "+${time - trace.eventTime}"
        ProbeLog.log(
            "E2",
            "#${trace.number} mech=${trace.mechanism.id} by=${trace.trigger} evt=${trace.eventTime} " +
                "recv=${after(trace.received)} bounds=${after(trace.boundsRead)} submit=${after(trace.submitted)} " +
                "frame=${after(trace.frame)} commit=${after(trace.committed)} " +
                "top=${trace.top} moved=${trace.moved ?: "-"} dy=${trace.scrollDeltaY ?: "-"}",
        )
        if (lagSamples.size < MAX_LAG_SAMPLES) {
            lagSamples += LagSample(
                mechanism = trace.mechanism.id,
                trigger = trace.trigger,
                received = trace.received - trace.eventTime,
                boundsRead = trace.boundsRead - trace.eventTime,
                submitted = trace.submitted - trace.eventTime,
                frame = trace.frame?.let { it - trace.eventTime },
                committed = trace.committed?.let { it - trace.eventTime },
            )
        }
    }

    private fun logLagSummary(reset: Boolean) {
        // "pick" and "settings" updates are not caused by scrolling, so they would only blur the numbers.
        val scrolling = lagSamples.filter { it.trigger != "pick" && it.trigger != "settings" }
        LagStats.summarise(scrolling).forEach { ProbeLog.log("E2", "summary $it") }
        if (reset) {
            lagSamples.clear()
            ProbeLog.log("E2", "summary reset")
        }
    }

    // Tracking mode "poll": look at the item on every display frame instead of waiting for events.
    private val pollFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!polling) return
            val now = SystemClock.uptimeMillis()
            updateBox("poll", now, now, null)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun setPolling(on: Boolean) {
        if (on == polling) return
        polling = on
        if (on) Choreographer.getInstance().postFrameCallback(pollFrame)
    }

    // ---- E3: signals that a finger went down ----

    private fun onTouchSignal(source: String, downTime: Long, received: Long) {
        val previous = lastTouch
        if (previous != null && previous.downTime == downTime) {
            // A second mechanism reporting the same finger.
            ProbeLog.log("E3", "touch #${previous.number} src=$source down=$downTime recv=+${received - downTime}ms")
            return
        }
        touchCount++
        lastTouch = Touch(touchCount, source, downTime, received)
        filmStrip.markTouch()
        ProbeLog.log("E3", "touch #$touchCount src=$source down=$downTime recv=+${received - downTime}ms")
    }

    /** Logs how long after the latest touch the first event of each [kind] came. */
    private fun matchWithTouch(kind: String, eventTime: Long, received: Long) {
        val touch = lastTouch ?: return
        if (eventTime < touch.downTime || eventTime - touch.downTime > MATCH_WINDOW_MS) return
        if (!touch.matched.add(kind)) return
        ProbeLog.log(
            "E3",
            "touch #${touch.number}: first $kind sent +${eventTime - touch.downTime}ms and received " +
                "+${received - touch.downTime}ms after touch-down; the ${touch.source} signal was " +
                "${received - touch.received}ms ahead of it",
        )
    }

    /**
     * Android 14's onMotionEvent. The service asks for touch screen events for
     * a few seconds. The platform documentation says events asked for this way
     * "are not sent to the rest of the system", so the screen is expected to
     * stop responding until the timer turns it off again. The test is here so
     * that this can be seen rather than taken on trust.
     */
    private fun setMotionListening(on: Boolean) {
        if (on == motionListening) return
        val info = serviceInfo ?: return
        motionListening = on
        info.motionEventSources = if (on) InputDevice.SOURCE_TOUCHSCREEN else 0
        serviceInfo = info
        if (on) {
            motionEventsSeen = 0
            scrollEventsWhileListening = 0
            ProbeLog.log("E3", "motion-listen ON for ${MOTION_LISTEN_MS / 1000} s: swipe the list now")
            handler.postDelayed(motionListenOff, MOTION_LISTEN_MS)
        } else {
            handler.removeCallbacks(motionListenOff)
            ProbeLog.log(
                "E3",
                "motion-listen off: motion events seen=$motionEventsSeen, scroll events from the app meanwhile=" +
                    "$scrollEventsWhileListening (touches seen but no scroll events means the app never got the touch)",
            )
        }
    }

    private val motionListenOff = Runnable { setMotionListening(false) }

    override fun onMotionEvent(event: MotionEvent) {
        val received = SystemClock.uptimeMillis()
        motionEventsSeen++
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onTouchSignal("motion-event", event.eventTime, received)
    }

    // ---- Triggers: things asked for while another app is in front ----

    private fun handleTrigger(action: String, intent: Intent) {
        if (!intent.getBooleanExtra(ProbeNotifications.EXTRA_FROM_SHADE, false)) {
            runTrigger(action, intent)
            return
        }
        // A notification button was pressed, so the shade is covering the app.
        // Close it, wait for it to slide away, put the box back, and only
        // then act, so that a screenshot shows the app with its box.
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        handler.postDelayed({
            frontCheck.run()
            handler.postDelayed({ runTrigger(action, intent) }, BOX_REDRAW_MS)
        }, SHADE_CLOSE_MS)
    }

    private fun runTrigger(action: String, intent: Intent) {
        when (action) {
            ProbeActions.REPICK -> withFrontApp { root -> pick(root, Pick.NEXT_ITEM) }
            ProbeActions.NEXT_LIST -> withFrontApp { root -> pick(root, Pick.NEXT_LIST) }
            ProbeActions.DUMP_TREE -> withFrontApp { root ->
                val label = intent.getStringExtra(ProbeActions.EXTRA_LABEL) ?: root.packageName?.toString().orEmpty()
                ProbeLog.log("E1", "dump ${TreeDump.write(this, root, label)}")
            }
            ProbeActions.SCREENSHOT -> takeScreenshot()
            ProbeActions.RATE_TEST -> withFrontApp { root -> screenshots.rateLimitTest(target?.windowId ?: root.windowId) }
            ProbeActions.E2_SUMMARY -> logLagSummary(reset = intent.getBooleanExtra(ProbeActions.EXTRA_RESET, false))
            ProbeActions.MOTION_LISTEN -> setMotionListening(true)
            // The remaining triggers are switches. TriggerReceiver has already saved the new value.
            else -> applySettingsNow()
        }
    }

    /** Runs [block] with the top element of the app window in front. */
    private fun withFrontApp(block: (AccessibilityNodeInfo) -> Unit) {
        val root = rootInActiveWindow
        when {
            root == null -> ProbeLog.log("APP", "nothing done: there is no window in front that the service can read")
            root.packageName?.toString() == SYSTEM_UI_PACKAGE ->
                ProbeLog.log("APP", "nothing done: the notification shade or another system window is in front")
            else -> block(root)
        }
    }

    private fun takeScreenshot() {
        val current = target
        val boxOnScreen = lastBox
        val mechanism = overlayMechanism
        if (current == null || boxOnScreen == null || mechanism == null) {
            ProbeLog.log("E5", "nothing done: there is no box on screen. Open an app with a list, or re-pick.")
            return
        }
        val boxInWindow = Rect().also { current.node.getBoundsInWindow(it) }
        // The window's top element spans the window, so its width is the window's width.
        val root = rootInActiveWindow
        val windowWidth = if (root != null && root.windowId == current.windowId) {
            Rect().also { root.getBoundsInScreen(it) }.width()
        } else {
            0
        }
        val screenWidth = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.width()
        ProbeLog.log(
            "E5",
            "screenshot requested: mech=${mechanism.id} box=${boxOnScreen.toShortString()} window=${current.windowId}",
        )
        screenshots.takeAndCheck(
            ScreenshotProbe.Scene(
                windowId = current.windowId,
                mechanism = mechanism,
                boxInWindow = boxInWindow,
                boxOnScreen = boxOnScreen,
                windowWidth = windowWidth,
                screenWidth = screenWidth,
            ),
        )
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val FRONT_CHECK_DELAY_MS = 300L
        private const val SHADE_CLOSE_MS = 800L
        private const val BOX_REDRAW_MS = 300L
        private const val TRACE_TIMEOUT_MS = 500L
        private const val MATCH_WINDOW_MS = 5_000L
        private const val MOTION_LISTEN_MS = 10_000L
        private const val MAX_SEARCH_NODES = 3_000
        private const val MIN_ITEM_HEIGHT_DP = 48
        private const val MAX_LAG_SAMPLES = 50_000

        // Android creates and destroys the service; this only points at it in
        // between, and shutDown() clears it. That is what lint's leak warning
        // is about, and why it does not apply.
        @SuppressLint("StaticFieldLeak")
        private var instance: ProbeAccessibilityService? = null

        /** True while Android has the service connected. Compose state, so the activity follows it. */
        var connected by mutableStateOf(false)
            private set

        /** Makes a running service take up changed [ProbeSettings]. Does nothing if it is not running. */
        fun applySettings() {
            instance?.applySettingsNow()
        }

        /** Hands a trigger to the running service. Returns false if it is not running. */
        fun trigger(action: String, intent: Intent): Boolean {
            val service = instance ?: return false
            service.handleTrigger(action, intent)
            return true
        }
    }
}
