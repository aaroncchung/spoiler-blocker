package io.github.aaroncchung.spoilerblocker.matcher

/** How far a blocker reaches. See decision 5 in docs/ARCHITECTURE.md. */
enum class Breadth {
    /** One event, such as a single race. A weak term needs a second one beside it. */
    NARROW,

    /** A whole subject, such as all of F1. One weak term is enough. */
    BROAD,
}

/**
 * The term lists of one blocker, in the form the matcher needs.
 *
 * @property strong Terms that block by themselves: "Japanese Grand Prix", "Suzuka".
 * @property weak Ambiguous terms that need support: "Max", "podium".
 * @property sources Channel and account names: "FORMULA 1", "Sky Sports F1".
 */
data class BlockerTerms(
    val strong: List<String>,
    val weak: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val breadth: Breadth = Breadth.NARROW,
)

/**
 * Whether [term] has anything in it that the matcher can look for, which
 * means at least one letter or digit.
 *
 * A term or source of only punctuation or emoji, such as "!!!", can never be
 * found. A [Matcher] skips it without complaint, so the screen where terms
 * are typed should call this and tell the user.
 */
fun hasMatchableText(term: String): Boolean = normalise(term).isNotEmpty()

/**
 * One notification or post, reduced to the pieces of text the matcher looks at.
 *
 * The two lists are kept apart. Strong and weak terms are looked for in
 * [texts] only, and a blocker's sources in [sources] only. Whoever builds a
 * Candidate decides what goes where. Something that is both, such as the
 * title of a notification, can be put in both lists.
 *
 * @property texts Separate pieces of text, such as a title and a caption. A
 *   term must be found within one piece; it cannot start in one and finish in
 *   the next.
 * @property sources Where it came from: channel or account name; for a
 *   notification, the app name and the title.
 */
data class Candidate(
    val texts: List<String>,
    val sources: List<String> = emptyList(),
)

/**
 * What the matcher decided about one [Candidate].
 *
 * A sealed interface has a fixed set of implementations, all listed here. The
 * compiler therefore knows that a `when` over a Verdict which handles
 * [Allowed] and [Blocked] has handled everything.
 */
sealed interface Verdict {
    data object Allowed : Verdict
    data class Blocked(val reason: Reason) : Verdict
}

/**
 * Why something was blocked, so the app can always show it (see "Matching
 * rules" in docs/ARCHITECTURE.md). Terms and sources are given exactly as the
 * user typed them, not in their normalised form.
 */
sealed interface Reason {
    /** It came from one of the blocker's sources. */
    data class Source(val source: String) : Reason

    /** A strong term was found. */
    data class StrongTerm(val term: String) : Reason

    /** Two or more different weak terms were found. [terms] has all of them, in the order of the blocker's list. */
    data class WeakTerms(val terms: List<String>) : Reason

    /** A single weak term was found, and that is enough because the blocker's breadth is broad. */
    data class WeakTermBroad(val term: String) : Reason
}

/**
 * Decides whether a [Candidate] is about the topic of one blocker.
 *
 * A candidate is blocked when one of these is true. They are tried in this
 * order and the first that is true gives the [Reason]:
 *
 * 1. One of its sources contains one of the blocker's sources.
 * 2. One of its texts contains a strong term.
 * 3. Its texts contain two or more different weak terms between them.
 * 4. Its texts contain one weak term and the blocker's breadth is broad.
 *
 * "Contains" means the same thing for terms and for sources:
 *
 * > A term is found in a text when the letters and digits of the term, run
 * > together, are the letters and digits of one or more whole words of the
 * > text that follow each other, run together.
 *
 * Upper and lower case are the same and accents are ignored. Anything that is
 * not a letter or a digit separates words. Inside a hashtag or a name, a new
 * word also starts at a capital after a small letter and wherever letters
 * meet digits: "#JapaneseGP2026" is the words japanese, gp, 2026.
 *
 * So the spaces, punctuation and capitals inside a term do not matter. The
 * term "Japanese GP" is found in "Japanese G.P.", "#JapaneseGP",
 * "#japanesegp" and "JAPANESEGP 2026". Its two ends do matter: a term starts
 * where a word of the text starts and ends where one ends. "Max" is found in
 * "MAX's lap" and "#MaxVerstappen" but not in "maximum", and "P1" is not
 * found in "P10". [normalise] and [TermIndex] have the details.
 *
 * What it does not do:
 *
 * - It knows nothing about word endings: "podium" is not found in "podiums".
 * - It cannot tell where words start inside small letters that are run
 *   together: "Suzuka" is not found in "#suzukacircuit".
 * - It cannot tell where a number ends: "F1" is not found in "#F12026", which
 *   is the words f and 12026.
 * - Letters that are more than a plain letter with an accent stay different
 *   letters: "ø" is not "o", "ß" is not "ss", the Turkish "ı" is not "i".
 *
 * What it does too much of. These are accepted, because a miss costs more
 * than an over-block (decision 4 in docs/ARCHITECTURE.md):
 *
 * - Neighbouring words can add up to a term: "therapist" is found in "the
 *   rapist", and "stop" in "Max's top ten".
 * - A short term is found as a part of a name with a capital inside: "You"
 *   in "YouTube".
 * - Case is ignored, so the term "US" is found in "join us".
 *
 * A Matcher never changes after it is constructed, so one instance can be
 * shared and called from several threads at once. The notification listener
 * and the accessibility service will do exactly that. When the user edits a
 * blocker, build a new Matcher.
 */
class Matcher(terms: BlockerTerms) {

    // The lists are normalised and indexed once, here, and not on every call
    // to check().
    private val sources = TermIndex(terms.sources)
    private val strongTerms = TermIndex(terms.strong)
    private val weakTerms = TermIndex(terms.weak)
    private val breadth = terms.breadth

    /**
     * Applies the rules to [candidate]. If several sources or several strong
     * terms are found, the reason names the one that comes first in the
     * blocker's list.
     */
    fun check(candidate: Candidate): Verdict {
        val reason = reasonToBlock(candidate)
        return if (reason == null) Verdict.Allowed else Verdict.Blocked(reason)
    }

    private fun reasonToBlock(candidate: Candidate): Reason? {
        // Rule 1.
        val source = sources.findIn(candidate.sources.map { normalise(it) }).firstOrNull()
        if (source != null) return Reason.Source(source)

        // Each text is cut into words separately. That is why a term cannot
        // start at the end of a title and finish at the start of a caption.
        val texts = candidate.texts.map { normalise(it) }

        // Rule 2.
        val strongTerm = strongTerms.findIn(texts).firstOrNull()
        if (strongTerm != null) return Reason.StrongTerm(strongTerm)

        // Rules 3 and 4. findIn() returns each term once, however often it is
        // in the texts, so the size of the list is the number of different
        // weak terms.
        val weakFound = weakTerms.findIn(texts)
        return when {
            weakFound.size >= 2 -> Reason.WeakTerms(weakFound)
            weakFound.size == 1 && breadth == Breadth.BROAD -> Reason.WeakTermBroad(weakFound.single())
            else -> null
        }
    }
}
