package io.github.aaroncchung.spoilerblocker.expansion

/**
 * How wide a blocker reaches. For the description "2026 Japanese Grand Prix",
 * [NARROW] means that race and [BROAD] means all of Formula 1.
 *
 * The matcher module has its own breadth type. This module does not depend on
 * the matcher, so the app converts between the two.
 */
enum class Breadth { NARROW, BROAD }

/**
 * What the model suggests for one description. These are suggestions: the
 * owner reviews and edits them before anything is saved.
 */
data class ExpandedTerms(
    /** Terms that block by themselves: "Japanese Grand Prix", "Suzuka". */
    val strong: List<String>,
    /** Ambiguous terms that need support from another one: "Max", "podium". */
    val weak: List<String>,
    /** Channel and account names: "FORMULA 1", "Sky Sports F1". */
    val sources: List<String>,
)

/** The outcome of [KeywordExpander.expand]. It never throws for a failed call. */
sealed interface ExpansionResult {
    data class Success(val terms: ExpandedTerms) : ExpansionResult

    /**
     * @param kind what went wrong. The app picks the sentence to show from this.
     * @param message the detail in English, for example the API's own error
     *   text. Useful in a "details" line or a log. It never contains the API key.
     */
    data class Failure(val kind: FailureKind, val message: String) : ExpansionResult
}

/**
 * Why an expansion failed.
 *
 * There is one kind for each thing the app would say differently. The exact
 * cause, such as the HTTP status, is in [ExpansionResult.Failure.message].
 *
 * @property canRetry whether it is worth offering "try again" with the same
 *   description. It is not a promise that trying again works. When it is
 *   false something has to change first: the key, the account or the
 *   description.
 */
enum class FailureKind(val canRetry: Boolean) {
    /** No API key has been set. */
    MISSING_API_KEY(canRetry = false),

    /** The API could not be reached: no connection, or the connection dropped. */
    NO_NETWORK(canRetry = true),

    /** The connection was made but no answer came in time. */
    TIMEOUT(canRetry = true),

    /** The API rejected the key (HTTP 401 or 403). */
    AUTH(canRetry = false),

    /**
     * HTTP 429. Usually too many requests in a short time, and waiting a
     * little is enough. It is also what the API answers when the account
     * has reached its monthly spending cap, and then it keeps failing until
     * access resumes, however often it is tried.
     */
    RATE_LIMITED(canRetry = true),

    /** The API is overloaded or failed on its side (HTTP 529 or another 5xx). */
    SERVER_ERROR(canRetry = true),

    /**
     * Any other HTTP error. The request or the account needs fixing: no
     * credit left, say, or a spending limit the owner set (the API answers
     * that one with HTTP 400). The message says which.
     */
    HTTP_OTHER(canRetry = false),

    /** The model declined to answer for this description. */
    REFUSED(canRetry = false),

    /**
     * A reply arrived but could not be used: it stopped before it was
     * complete, or it was not the three lists that were asked for.
     */
    BAD_REPLY(canRetry = true),

    /** The model returned no terms: the description was empty or too vague. */
    NOTHING_FOUND(canRetry = false),
}
