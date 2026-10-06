package io.github.aaroncchung.spoilerblocker.probe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * E1: writes down the tree of on-screen elements exactly as this accessibility
 * service is given it, which is what the real app will have to work with.
 *
 * This is the one place where the probe stores text from the screen. The file
 * goes to the app's private storage and nowhere else.
 *
 * The file is JSON laid out one line at a time:
 *
 * - line 1: `{"meta":{...},` with facts about the capture, including the list
 *   of windows that were on screen;
 * - then one line per element, each starting `{"i":`.
 *
 * Keys are only ever added to this format, never renamed or removed, so that
 * anything written to read an older dump still reads a newer one.
 */
object TreeDump {
    const val DIRECTORY = "dumps"

    /** A guard against a broken or enormous tree. If it is reached, the file says so. */
    private const val MAX_NODES = 5000

    private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** The elements collected by one walk of the tree. */
    private class Walk {
        val nodes = ArrayList<JSONObject>()
        var truncated = false
    }

    /**
     * Walks the tree under [root] and writes it as JSON. Returns a one-line
     * description for the log.
     *
     * The elements are a flat list in reading order, each with its depth and
     * the index of its parent. A nested structure would be closer to the
     * tree, but feeds nest so deeply that some JSON readers refuse it.
     */
    fun write(service: AccessibilityService, root: AccessibilityNodeInfo, label: String): String {
        // Android keeps a cache of elements this service has already been
        // given. What is in it depends on what the probe did a moment ago, so
        // a single timing would be luck. Time the walk twice instead: once
        // with the cache emptied, when every element has to be fetched from
        // the app, and once straight afterwards, with the cache full.
        service.clearCache()
        val coldStarted = SystemClock.uptimeMillis()
        val walk = Walk()
        addNode(root, parentIndex = -1, depth = 0, walk)
        val coldMillis = SystemClock.uptimeMillis() - coldStarted

        val warmStarted = SystemClock.uptimeMillis()
        addNode(root, parentIndex = -1, depth = 0, Walk())
        val warmMillis = SystemClock.uptimeMillis() - warmStarted

        val windows = windowsOnScreen(service)
        val nodes = walk.nodes
        val safeLabel = label.replace(Regex("[^A-Za-z0-9_-]"), "-").ifEmpty { "screen" }
        val meta = JSONObject()
            .put("label", safeLabel)
            .put("package", root.packageName?.toString())
            .put("windowId", root.windowId)
            .put("windowTitle", titleOfWindow(windows, root.windowId))
            .put("capturedAt", LocalDateTime.now().toString())
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            .put("nodeCount", nodes.size)
            // True if the tree was cut off at MAX_NODES and the file is incomplete.
            .put("truncated", walk.truncated)
            // "walkMillis" is the cold walk. It was the only timing in older dumps.
            .put("walkMillis", coldMillis)
            .put("walkWarmMillis", warmMillis)
            // These two decide what a service is shown, so a reader needs them.
            .put("includesNotImportantViews", true)
            .put("isAccessibilityTool", false)
            // Apps can lay themselves out differently when a screen reader or
            // another service is on, so note which were.
            .put("enabledAccessibilityServices", JSONArray(enabledAccessibilityServices(service)))
            .put("touchExplorationEnabled", service.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled)
            .put("windows", windows)
        // Valid JSON, laid out with one node per line so it can also be read,
        // searched and processed a line at a time.
        val json = buildString {
            append("{\"meta\":").append(meta).append(",\n\"nodes\":[\n")
            nodes.forEachIndexed { index, node ->
                append(node)
                append(if (index < nodes.lastIndex) ",\n" else "\n")
            }
            append("]}\n")
        }

        val directory = File(service.filesDir, DIRECTORY).apply { mkdirs() }
        // The ".capture.json" ending is gitignored, as a second line of defence.
        val file = File(directory, "${LocalDateTime.now().format(FILE_STAMP)}-$safeLabel.capture.json")
        ProbeLog.runInBackground { file.writeText(json) }
        return "label=$safeLabel package=${root.packageName} nodes=${nodes.size}" +
            (if (walk.truncated) " TRUNCATED at $MAX_NODES" else "") +
            " walkCold=${coldMillis}ms walkWarm=${warmMillis}ms windows=${windows.length()} file=$DIRECTORY/${file.name}"
    }

    private fun addNode(node: AccessibilityNodeInfo, parentIndex: Int, depth: Int, walk: Walk) {
        if (walk.nodes.size >= MAX_NODES) {
            walk.truncated = true
            return
        }
        val index = walk.nodes.size
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val json = JSONObject()
            .put("i", index)
            .put("parent", parentIndex)
            .put("depth", depth)
            .put("class", node.className?.toString())
            .put("id", node.viewIdResourceName)
            .put("text", node.text?.toString())
            .put("desc", node.contentDescription?.toString())
            .put("hint", node.hintText?.toString())
            .put("state", node.stateDescription?.toString())
            .put("pane", node.paneTitle?.toString())
            .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            .put("clickable", node.isClickable)
            .put("scrollable", node.isScrollable)
            .put("visible", node.isVisibleToUser)
            // False means the node is only here because the probe asks for
            // "not important" views too. A service that does not ask never sees it.
            .put("important", node.isImportantForAccessibility)
            .put("children", node.childCount)
            .put("longClickable", node.isLongClickable)
            .put("enabled", node.isEnabled)
            .put("focusable", node.isFocusable)
            // What a screen reader treats as one thing to stop on and read
            // out. Apps set it on the element that wraps a whole post.
            .put("screenReaderFocusable", node.isScreenReaderFocusable)
            .put("heading", node.isHeading)
        // A list can say how many rows and columns it has, and each item which
        // row and column it is. This is the app's own answer to "which
        // element is one item of the list?".
        node.collectionInfo?.let { info ->
            json.put(
                "collection",
                JSONObject()
                    .put("rowCount", info.rowCount)
                    .put("columnCount", info.columnCount)
                    .put("hierarchical", info.isHierarchical),
            )
        }
        node.collectionItemInfo?.let { item ->
            json.put(
                "collectionItem",
                JSONObject()
                    .put("rowIndex", item.rowIndex)
                    .put("rowSpan", item.rowSpan)
                    .put("columnIndex", item.columnIndex)
                    .put("columnSpan", item.columnSpan)
                    .put("selected", item.isSelected),
            )
        }
        // JSONObject.put drops a key whose value is null, which keeps the file small.
        walk.nodes.add(json)
        for (i in 0 until node.childCount) {
            // getChild asks the other app for the node. It can come back null
            // if the screen changed in the meantime.
            val child = node.getChild(i) ?: continue
            addNode(child, index, depth + 1, walk)
        }
    }

    /**
     * Every window on screen: the app's, the keyboard, the status bar, the
     * probe's own overlays. Only the window in front is walked; this list
     * says what else was there.
     *
     * Android only tells a service about windows if the service asks with
     * FLAG_RETRIEVE_INTERACTIVE_WINDOWS. The probe asks just for the length of
     * this call, so that the other experiments run exactly as before.
     */
    private fun windowsOnScreen(service: AccessibilityService): JSONArray {
        val result = JSONArray()
        val info = service.serviceInfo ?: return result
        val flagsBefore = info.flags
        info.flags = flagsBefore or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        service.serviceInfo = info
        try {
            val bounds = Rect()
            for (window in service.windows) {
                window.getBoundsInScreen(bounds)
                result.put(
                    JSONObject()
                        .put("id", window.id)
                        .put("type", windowTypeName(window.type))
                        .put("title", window.title?.toString())
                        .put("package", window.root?.packageName?.toString())
                        .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                        .put("layer", window.layer)
                        .put("active", window.isActive)
                        .put("focused", window.isFocused),
                )
            }
        } finally {
            info.flags = flagsBefore
            service.serviceInfo = info
        }
        return result
    }

    private fun titleOfWindow(windows: JSONArray, windowId: Int): String? {
        for (i in 0 until windows.length()) {
            val window = windows.getJSONObject(i)
            if (window.getInt("id") == windowId) return window.optString("title").ifEmpty { null }
        }
        return null
    }

    private fun windowTypeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input-method"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility-overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split-screen-divider"
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "magnification-overlay"
        // Newer Android versions have further types. They are written as their number.
        else -> "type-$type"
    }

    /** The accessibility services switched on in Settings, as "package/class" names. */
    private fun enabledAccessibilityServices(service: AccessibilityService): List<String> {
        val setting = Settings.Secure.getString(service.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return setting?.split(':')?.filter { it.isNotEmpty() }.orEmpty()
    }
}
