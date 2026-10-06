package io.github.aaroncchung.spoilerblocker.data

import io.github.aaroncchung.spoilerblocker.matcher.Breadth
import kotlinx.serialization.Serializable

/**
 * A topic the owner does not want spoiled. While it is [enabled], anything
 * that matches its lists is hidden. "Matching rules" in docs/ARCHITECTURE.md
 * says what each list does.
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

    // The fields below came with version 2 of the stored format. A blocker
    // stored by version 1 has none of them and gets the defaults.

    /**
     * What the owner typed to have the lists suggested, or empty if every
     * term was typed by hand. It is kept so that the lists can be made again
     * later.
     */
    val description: String = "",
    /** Ambiguous words. How many it takes to block depends on [breadth]. */
    val weakTerms: List<String> = emptyList(),
    /** Channel, account and app names. Anything that comes from one of them is blocked. */
    val sources: List<String> = emptyList(),
    /**
     * The matcher's own type. It is stored as the name of the constant,
     * "NARROW" or "BROAD", so renaming a constant in the matcher would change
     * the stored format. The frozen documents in `BlockerRepositoryTest`
     * catch that.
     */
    val breadth: Breadth = Breadth.NARROW,
)
