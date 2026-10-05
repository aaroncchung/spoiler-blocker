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
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Reads and writes the blockers, which are kept as one JSON file.
 *
 * There must be only one repository per file in the whole app. DataStore, the
 * library that does the reading and writing, throws if the same file is opened
 * twice in one process. The app's single repository is created by
 * `AppContainer`; screens and services all share it.
 *
 * @param produceFile returns the file the blockers are kept in. The file is
 *   created by the first change. This is a function, not a plain File, so
 *   that it runs on a background thread with the rest of the file work:
 *   asking Android for the app's storage folder reads the disk.
 * @param scope where the file reads and writes run. Tests pass their own.
 */
class BlockerRepository(
    produceFile: () -> File,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val dataStore: DataStore<StoredBlockers> = DataStoreFactory.create(
        // Okio is a file library. This is the storage DataStore's own Android
        // helpers use. Its other storage, built on java.io.File, cannot
        // replace an existing file when a unit test runs on Windows.
        storage = OkioStorage(FileSystem.SYSTEM, StoredBlockersSerializer) {
            produceFile().toOkioPath()
        },
        // Runs when the file cannot be read as blockers, and starts again from
        // an empty list. Without it the app would crash on every launch until
        // its data was cleared. But an empty list means nothing is blocked,
        // so the unreadable file is first kept beside the real one, as
        // blockers.json.unreadable. The blockers in it can then be recovered
        // by hand, and the copy shows why they went missing. If the copy
        // cannot be made, the read fails and the file is left alone.
        corruptionHandler = ReplaceFileCorruptionHandler {
            val unreadable = produceFile()
            unreadable.copyTo(
                File(unreadable.parentFile, unreadable.name + ".unreadable"),
                overwrite = true,
            )
            StoredBlockers()
        },
        scope = scope,
    )

    /**
     * Every blocker, on or off, oldest first. Collecting this gives the
     * current list straight away and then a new list after each change.
     *
     * Collecting it, like every change below, fails with an
     * IllegalStateException if the file was written by a newer build of the
     * app. See `CURRENT_VERSION`.
     */
    val blockers: Flow<List<Blocker>> = dataStore.data.map { it.blockers }

    /** Adds [blocker], or replaces the stored blocker that has the same id. */
    suspend fun save(blocker: Blocker) {
        update { blockers ->
            if (blockers.any { it.id == blocker.id }) {
                blockers.map { if (it.id == blocker.id) blocker else it }
            } else {
                blockers + blocker
            }
        }
    }

    /** Removes the blocker with this [id]. Does nothing if there is none. */
    suspend fun delete(id: String) {
        update { blockers -> blockers.filterNot { it.id == id } }
    }

    /** Switches the blocker with this [id] on or off. Does nothing if there is none. */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        update { blockers ->
            blockers.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }
    }

    // updateData runs one change at a time and returns once the file is
    // written, so two quick changes cannot overwrite each other.
    private suspend fun update(change: (List<Blocker>) -> List<Blocker>) {
        dataStore.updateData { stored ->
            // Whatever version the file had, it is in this build's layout now.
            stored.copy(version = CURRENT_VERSION, blockers = change(stored.blockers))
        }
    }
}

/**
 * The version of the file layout that this build writes.
 *
 * Raise it whenever the stored shape changes, and that includes adding a field
 * to [Blocker]. An older build does not know the new field. It could load the
 * file all the same, and would then write it back without the field. The
 * version is how the older build knows to refuse the file instead.
 */
private const val CURRENT_VERSION = 1

/** The contents of the file. */
@Serializable
private data class StoredBlockers(
    val version: Int = CURRENT_VERSION,
    val blockers: List<Blocker> = emptyList(),
)

/**
 * Just the version of a file. It is read by itself first, because a newer
 * build may have changed the rest into something this build cannot read.
 */
@Serializable
private class StoredVersion(val version: Int)

/** Tells DataStore how to turn [StoredBlockers] into the text of the file and back. */
private object StoredBlockersSerializer : OkioSerializer<StoredBlockers> {
    private val json = Json {
        // A field this build does not know is skipped. Without this it would
        // make the whole file count as unreadable. It is a safety net only:
        // the skipped field is lost at the next change, which is why the
        // version has to be raised when a field is added.
        ignoreUnknownKeys = true
        // Values equal to their default are normally left out. This keeps
        // "version" in the file.
        encodeDefaults = true
    }

    /** What the repository holds before the file exists. */
    override val defaultValue = StoredBlockers()

    override suspend fun readFrom(source: BufferedSource): StoredBlockers {
        val text = source.readUtf8()
        try {
            val version = json.decodeFromString<StoredVersion>(text).version
            // check throws IllegalStateException, which the catch below lets
            // through. That is deliberate. A CorruptionException would make
            // DataStore replace the file, and a file from a newer build must
            // be left exactly as it is.
            check(version <= CURRENT_VERSION) {
                "blockers.json was written by a newer build (version $version). " +
                    "Install the newer build again, or clear the app's data."
            }
            return json.decodeFromString<StoredBlockers>(text)
        } catch (e: SerializationException) {
            // CorruptionException is what makes DataStore run the corruption
            // handler above.
            throw CorruptionException("The blockers file is not valid.", e)
        }
    }

    override suspend fun writeTo(t: StoredBlockers, sink: BufferedSink) {
        sink.writeUtf8(json.encodeToString(t))
    }
}
