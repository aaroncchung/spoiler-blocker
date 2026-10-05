package io.github.aaroncchung.spoilerblocker.data

import kotlinx.serialization.Serializable

/**
 * A topic the owner does not want spoiled. While it is [enabled], anything
 * that matches its terms is hidden.
 *
 * Blockers are stored as JSON by [BlockerRepository], so adding a field here
 * changes the stored format. Two things then have to be done:
 * - give the field a default value, so that blockers saved by an older build
 *   still load;
 * - raise `CURRENT_VERSION` in `BlockerRepository.kt`, so that an older build
 *   refuses the new file instead of quietly dropping the field.
 *
 * `BlockerRepositoryTest` pins the format and fails until both are done.
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
