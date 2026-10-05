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
        // A file that cannot be read is replaced with an empty list. Without
        // this the app would crash on every launch until its data was cleared.
        corruptionHandler = ReplaceFileCorruptionHandler { StoredBlockers() },
        scope = scope,
    )

    /**
     * Every blocker, on or off, oldest first. Collecting this gives the
     * current list straight away and then a new list after each change.
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
        dataStore.updateData { stored -> stored.copy(blockers = change(stored.blockers)) }
    }
}

/**
 * The contents of the file. [version] is there so that a later version of the
 * app can tell an old layout from a new one and convert it.
 */
@Serializable
private data class StoredBlockers(
    val version: Int = 1,
    val blockers: List<Blocker> = emptyList(),
)

/** Tells DataStore how to turn [StoredBlockers] into the text of the file and back. */
private object StoredBlockersSerializer : OkioSerializer<StoredBlockers> {
    private val json = Json {
        // A file written by a later version of the app may have fields this
        // version does not know. They are skipped instead of failing the read.
        ignoreUnknownKeys = true
        // Values equal to their default are normally left out. This keeps
        // "version" in the file.
        encodeDefaults = true
    }

    /** What the repository holds before the file exists. */
    override val defaultValue = StoredBlockers()

    override suspend fun readFrom(source: BufferedSource): StoredBlockers =
        try {
            json.decodeFromString<StoredBlockers>(source.readUtf8())
        } catch (e: SerializationException) {
            // CorruptionException is what makes DataStore run the corruption
            // handler above.
            throw CorruptionException("The blockers file is not valid.", e)
        }

    override suspend fun writeTo(t: StoredBlockers, sink: BufferedSink) {
        sink.writeUtf8(json.encodeToString(t))
    }
}
