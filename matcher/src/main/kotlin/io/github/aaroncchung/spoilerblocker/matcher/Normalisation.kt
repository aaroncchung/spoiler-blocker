package io.github.aaroncchung.spoilerblocker.matcher

import java.text.Normalizer

/**
 * Cuts [text] into the words that get compared. Terms and texts both go
 * through here, so whatever it does to one it also does to the other.
 *
 * "Pérez wins the #JapaneseGP2026!" becomes perez, wins, the, japanese, gp,
 * 2026:
 *
 * - Everything is lower case and accents are removed.
 * - Anything that is not a letter or a digit only separates words and is
 *   then forgotten: spaces, punctuation, "#", "@", "_", emoji. The apostrophe
 *   too, so "Max's" is max, s.
 * - Inside a run of letters and digits, a new word starts at a capital after
 *   a small letter and wherever letters meet digits. That takes hashtags and
 *   names like "McLaren" apart.
 */
internal fun normalise(text: String): List<String> {
    val characters = charactersToCompare(text)
    val words = mutableListOf<String>()
    val word = StringBuilder()
    for (index in characters.indices) {
        val character = characters[index]
        val isPartOfAWord = Character.isLetterOrDigit(character)
        // The word so far is finished if this character is a separator, or if
        // it is the first character of the next word.
        if (word.isNotEmpty() && (!isPartOfAWord || startsNewWord(characters, index))) {
            // Lower case comes last, because the capitals say where to cut.
            words += word.toString().lowercase()
            word.clear()
        }
        if (isPartOfAWord) word.appendCodePoint(character)
    }
    if (word.isNotEmpty()) words += word.toString().lowercase()
    return words
}

/**
 * Returns the characters of [text] that count, with accents and invisible
 * characters gone.
 *
 * Unicode can write "é" as one character or as "e" followed by a separate
 * accent character. NFKD rewrites everything in the second way, and then the
 * accents are left out. NFKD also replaces look-alike characters with the
 * ordinary ones: full-width letters, the single character for "fi", the bold
 * letters some posts use as decoration.
 *
 * Letters that are more than a plain letter with an accent stay as they are:
 * "ø" is not turned into "o", nor "ß" into "ss".
 *
 * In scripts such as Thai, Hindi and Japanese some vowels and sound marks are
 * accents too, and they are left out as well. Terms and texts lose the same
 * marks, so nothing is missed. A match in those scripts is only a little
 * looser.
 *
 * The result holds code points, not Chars. A Char is 16 bits, and emoji and
 * some rare Chinese characters need two of them. A code point is the whole
 * character as one Int, however many Chars it takes.
 */
private fun charactersToCompare(text: String): IntArray {
    val decomposed = Normalizer.normalize(withoutSignsThatTurnIntoLetters(text), Normalizer.Form.NFKD)
    // There is at most one code point for each Char, so this is big enough.
    val kept = IntArray(decomposed.length)
    var count = 0
    var index = 0
    while (index < decomposed.length) {
        val codePoint = decomposed.codePointAt(index)
        index += Character.charCount(codePoint)
        if (!isLeftOut(codePoint)) {
            kept[count] = codePoint
            count++
        }
    }
    return kept.copyOf(count)
}

/**
 * NFKD would turn the trade mark sign into the letters "TM" and glue them to
 * the word in front: "SUZUKA™" would become the word "suzukatm". The same
 * goes for the service mark sign. Both become a space here.
 *
 * The third character looks like an apostrophe, and some keyboards type it as
 * one, but Unicode counts it as a letter. It becomes a real apostrophe, so
 * that "Max's" written with it is still max, s.
 */
private fun withoutSignsThatTurnIntoLetters(text: String): String =
    text.replace('™', ' ').replace('℠', ' ').replace(MODIFIER_LETTER_APOSTROPHE, '\'')

private val MODIFIER_LETTER_APOSTROPHE = Char(0x02BC)

/** True for a character that is dropped as if it had never been in the text. */
private fun isLeftOut(codePoint: Int): Boolean = when (codePoint) {
    // Invisible characters that apps put inside words: the soft hyphen, the
    // zero-width non-joiner and joiner, the word joiner, the byte-order mark.
    0x00AD, 0x200C, 0x200D, 0x2060, 0xFEFF -> true
    else -> when (Character.getType(codePoint)) {
        // The three kinds of "combining mark". Accents are of the first kind
        // once NFKD has separated them from their letter.
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
    }
}

/**
 * True if the letter or digit at [index] starts a new word although the
 * character before it is a letter or digit too. [normalise] only asks while a
 * word is under way, so there always is a character before it.
 */
private fun startsNewWord(characters: IntArray, index: Int): Boolean {
    val previous = characters[index - 1]
    val current = characters[index]
    // After the last character a space stands in for "nothing there".
    // (' '.code is the code point of a space.)
    val next = characters.getOrElse(index + 1) { ' '.code }
    val afterNext = characters.getOrElse(index + 2) { ' '.code }
    // "GPs", "NPCs": a single small "s" after capitals only makes a plural.
    val isPluralS = next == 's'.code && !Character.isLowerCase(afterNext)
    return when {
        // "Suzuka2026", "P1": a number is a word of its own. This is also why
        // "P1" (p, 1) is not found in "P10" (p, 10).
        Character.isDigit(previous) != Character.isDigit(current) -> true
        // "JapaneseGP": a capital after a small letter.
        Character.isLowerCase(previous) && Character.isUpperCase(current) -> true
        // "GPHighlights": the last of several capitals belongs to the word
        // that follows. A plural is the exception, so that "GPs" stays whole.
        Character.isUpperCase(previous) && Character.isUpperCase(current) &&
            Character.isLowerCase(next) && !isPluralS -> true
        // No spaces to go by, so every character is a word. See below.
        isWrittenWithoutSpaces(previous) || isWrittenWithoutSpaces(current) -> true
        else -> false
    }
}

/** The scripts whose words are not separated by spaces. Han is Chinese, and the kanji of Japanese. */
private val SCRIPTS_WITHOUT_SPACES = setOf(
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.THAI,
    Character.UnicodeScript.LAO,
    Character.UnicodeScript.KHMER,
    Character.UnicodeScript.MYANMAR,
)

/**
 * Chinese, Japanese and Thai put no spaces between words, so "whole word"
 * means nothing there: the name "鈴鹿" sits inside a longer run of characters.
 * Every such character is therefore a word of its own. A term is then a row
 * of one-character words and is found anywhere in a run, which amounts to a
 * plain substring search. The price is that a short term can also be found
 * inside a longer, unrelated word.
 */
private fun isWrittenWithoutSpaces(codePoint: Int): Boolean =
    // All these scripts come after U+0E00 in Unicode. Asking that first skips
    // the slower script lookup for ordinary Latin text. It was measured:
    // normalising a screen of English took 245 microseconds without this
    // line and 130 with it.
    codePoint >= 0x0E00 && Character.UnicodeScript.of(codePoint) in SCRIPTS_WITHOUT_SPACES
