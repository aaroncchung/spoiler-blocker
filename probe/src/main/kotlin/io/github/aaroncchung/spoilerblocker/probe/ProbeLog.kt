package io.github.aaroncchung.spoilerblocker.probe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.util.concurrent.Executors

/**
 * Where every measurement goes: a file in the app's private storage, logcat,
 * and a short in-memory tail for the activity's live view.
 *
 * Read the file from a PC with:
 * `adb exec-out run-as io.github.aaroncchung.spoilerblocker.probe cat files/probe.log`
 * or follow it live with `adb logcat -s SBProbe`.
 */
object ProbeLog {
    const val LOGCAT_TAG = "SBProbe"
    const val FILE_NAME = "probe.log"
    private const val TAIL_LINES = 300

    /** The newest lines, oldest first. Compose redraws when it changes. Main thread only. */
    val tail = mutableStateListOf<String>()

    // File writes happen on one background thread, in order. The timestamps are
    // taken by the caller before the line is queued, so a slow disk cannot
    // distort a measurement or hold up the next accessibility event.
    private val writer = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var file: File

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
    }

    /** Appends one line. [tag] says which experiment it belongs to: "E1" to "E6", or "APP". */
    fun log(tag: String, message: String) {
        val record = LogRecord(
            wallMillis = System.currentTimeMillis(),
            uptimeMillis = SystemClock.uptimeMillis(),
            realtimeMillis = SystemClock.elapsedRealtime(),
            pid = Process.myPid(),
            tag = tag,
            message = message.replace('\n', ' '),
        )
        val line = record.format()
        Log.i(LOGCAT_TAG, line)
        // appendText opens, writes and closes the file, so the line has left
        // this process even if Android kills the process a moment later. E6
        // depends on that.
        writer.execute { file.appendText(line + "\n") }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            addToTail(line)
        } else {
            mainHandler.post { addToTail(line) }
        }
    }

    private fun addToTail(line: String) {
        tail.add(line)
        if (tail.size > TAIL_LINES) tail.removeRange(0, tail.size - TAIL_LINES)
    }

    /**
     * Reads the whole file and hands the parsed lines to [onResult] on the main
     * thread. The read is queued behind any pending writes, so it sees them.
     */
    fun readAll(onResult: (List<LogRecord>) -> Unit) {
        writer.execute {
            val records = if (file.exists()) file.readLines().mapNotNull { LogRecord.parse(it) } else emptyList()
            mainHandler.post { onResult(records) }
        }
    }

    /** Empties the log file and the on-screen tail. */
    fun clear() {
        writer.execute { file.writeText("") }
        mainHandler.post { tail.clear() }
    }

    /** Runs [task] on the log's background thread. Used for writing dumps and screenshots. */
    fun runInBackground(task: () -> Unit) {
        writer.execute(task)
    }
}
