package io.github.aaroncchung.spoilerblocker.data

import kotlinx.serialization.Serializable

/**
 * A topic the owner does not want spoiled. While it is [enabled], anything
 * that matches its terms is hidden.
 *
 * Blockers are stored as JSON by [BlockerRepository]. A field added later
 * needs a default value, so that blockers saved by an older version of the
 * app still load.
 */
@Serializable
data class Blocker(
    /** A random UUID. It never changes, so it still identifies the blocker after an edit. */
    val id: String,
    /** What the owner calls it, for example "2026 Japanese Grand Prix". */
    val name: String,
    /** Words and phrases. Any one of them is enough to block. */
    val strongTerms: List<String>,
    val enabled: Boolean,
    /** When it was created: milliseconds since 1970, as `System.currentTimeMillis()` gives. */
    val createdAtMillis: Long,
)
