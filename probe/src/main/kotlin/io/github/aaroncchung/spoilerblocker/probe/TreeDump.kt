package io.github.aaroncchung.spoilerblocker.probe

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
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
 */
object TreeDump {
    const val DIRECTORY = "dumps"

    /** A guard against a broken or enormous tree. YouTube's feed is a few hundred nodes. */
    private const val MAX_NODES = 5000

    private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /**
     * Walks the tree under [root] and writes it as JSON. Returns a one-line
     * description for the log.
     *
     * The nodes are a flat list in reading order, each with its depth and the
     * index of its parent. A nested structure would be closer to the tree, but
     * feeds nest so deeply that some JSON readers refuse it.
     */
    fun write(context: Context, root: AccessibilityNodeInfo, label: String): String {
        val started = SystemClock.uptimeMillis()
        val nodes = mutableListOf<JSONObject>()
        addNode(root, parentIndex = -1, depth = 0, nodes)
        val walkMillis = SystemClock.uptimeMillis() - started

        val safeLabel = label.replace(Regex("[^A-Za-z0-9_-]"), "-").ifEmpty { "screen" }
        val meta = JSONObject()
            .put("label", safeLabel)
            .put("package", root.packageName?.toString())
            .put("windowId", root.windowId)
            .put("capturedAt", LocalDateTime.now().toString())
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            .put("nodeCount", nodes.size)
            .put("walkMillis", walkMillis)
            // These two decide what a service is shown, so a reader needs them.
            .put("includesNotImportantViews", true)
            .put("isAccessibilityTool", false)
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

        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        // The ".capture.json" ending is gitignored, as a second line of defence.
        val file = File(directory, "${LocalDateTime.now().format(FILE_STAMP)}-$safeLabel.capture.json")
        ProbeLog.runInBackground { file.writeText(json) }
        return "label=$safeLabel package=${root.packageName} nodes=${nodes.size} walk=${walkMillis}ms file=$DIRECTORY/${file.name}"
    }

    private fun addNode(node: AccessibilityNodeInfo, parentIndex: Int, depth: Int, out: MutableList<JSONObject>) {
        if (out.size >= MAX_NODES) return
        val index = out.size
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
        // JSONObject.put drops a key whose value is null, which keeps the file small.
        out.add(json)
        for (i in 0 until node.childCount) {
            // getChild asks the other app for the node. It can come back null
            // if the screen changed in the meantime.
            val child = node.getChild(i) ?: continue
            addNode(child, index, depth + 1, out)
        }
    }
}
