package io.github.aaroncchung.spoilerblocker.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Reads and writes the "hidden while blocking" list, which is kept as one
 * JSON file. It is built exactly like [BlockerRepository], and the comments
 * there explain the parts that are only named here.
 *
 * As with the blockers, there must be only one repository per file in the
 * whole app. `AppContainer` creates it; the notification listener adds to it
 * and the screens read it.
 *
 * @param produceFile returns the file the list is kept in.
 * @param scope where the file reads and writes run. Tests pass their own.
 */
class HiddenNotificationRepository(
    private val produceFile: () -> File,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val dataStore: DataStore<StoredHiddenNotifications> = DataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, StoredHiddenNotificationsSerializer) {
            produceFile().toOkioPath()
        },
        // A file that cannot be read is kept beside the real one, as
        // hidden_notifications.json.unreadable, and the list starts again
        // empty.
        corruptionHandler = ReplaceFileCorruptionHandler {
            produceFile().copyTo(unreadableCopy(), overwrite = true)
            StoredHiddenNotifications()
        },
        scope = scope,
    )

    /** Where a file that could not be read is kept. */
    private fun unreadableCopy(): File {
        val file = produceFile()
        return File(file.parentFile, file.name + ".unreadable")
    }

    /**
     * The hidden notifications, newest first. Collecting this gives the
     * current list straight away and then a new list after each change.
     *
     * Collecting it, like every change below, fails with an
     * IllegalStateException if the file was written by a newer build of the
     * app. See `CURRENT_VERSION`.
     */
    val hiddenNotifications: Flow<List<HiddenNotification>> =
        dataStore.data.map { it.notifications }

    /**
     * Puts [notification] at the top of the list and drops the oldest entries
     * beyond [MAX_ENTRIES].
     *
     * An app may post a notification again without changing it, and it is
     * dismissed again each time. The list shows it once: if the newest entry
     * for the same notification has the same title and text, the new entry
     * takes its place. What stays is the time it was last hidden.
     */
    suspend fun add(notification: HiddenNotification) {
        dataStore.updateData { stored ->
            val previous = stored.notifications
                .firstOrNull { it.notificationKey == notification.notificationKey }
            val isRepeat = previous != null &&
                previous.title == notification.title &&
                previous.text == notification.text
            val others = if (isRepeat) stored.notifications - previous else stored.notifications

            stored.copy(
                version = CURRENT_VERSION,
                notifications = (listOf(notification) + others).take(MAX_ENTRIES),
            )
        }
    }

    /**
     * Removes every entry, and the copy of a file that could not be read if
     * there is one: it holds the text of notifications too.
     */
    suspend fun clear() {
        dataStore.updateData { stored ->
            stored.copy(version = CURRENT_VERSION, notifications = emptyList())
        }
        // DataStore does its own file work on a background thread. This
        // delete is not DataStore's, so it has to be sent there by hand.
        withContext(Dispatchers.IO) { unreadableCopy().delete() }
    }

    companion object {
        /**
         * How many entries are kept. The whole file is rewritten for each new
         * entry, so the list must not grow without limit.
         */
        const val MAX_ENTRIES = 500
    }
}

/**
 * The version of the file layout that this build writes. Raise it whenever
 * the stored shape changes, and that includes adding a field to
 * [HiddenNotification]. The constant of the same name in
 * `BlockerRepository.kt` explains why.
 */
private const val CURRENT_VERSION = 1

/** The contents of the file, newest entry first. */
@Serializable
private data class StoredHiddenNotifications(
    val version: Int = CURRENT_VERSION,
    val notifications: List<HiddenNotification> = emptyList(),
)

/** Just the version of a file, which is read by itself first. */
@Serializable
private class StoredHiddenNotificationsVersion(val version: Int)

/** Tells DataStore how to turn [StoredHiddenNotifications] into the text of the file and back. */
private object StoredHiddenNotificationsSerializer : OkioSerializer<StoredHiddenNotifications> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue = StoredHiddenNotifications()

    override suspend fun readFrom(source: BufferedSource): StoredHiddenNotifications {
        val text = source.readUtf8()
        try {
            val version = json.decodeFromString<StoredHiddenNotificationsVersion>(text).version
            // A file from a newer build is refused and left exactly as it is.
            // The IllegalStateException passes the catch below on purpose.
            check(version <= CURRENT_VERSION) {
                "hidden_notifications.json was written by a newer build (version $version). " +
                    "Install the newer build again, or clear the app's data."
            }
            return json.decodeFromString<StoredHiddenNotifications>(text)
        } catch (e: SerializationException) {
            // The exception that was caught is not passed on as the cause,
            // on purpose. Its message quotes the part of the file it could
            // not read, which here is the text of a notification, and this
            // exception can end up in the log.
            throw CorruptionException("The hidden notifications file is not valid.")
        }
    }

    override suspend fun writeTo(t: StoredHiddenNotifications, sink: BufferedSink) {
        sink.writeUtf8(json.encodeToString(t))
    }
}
