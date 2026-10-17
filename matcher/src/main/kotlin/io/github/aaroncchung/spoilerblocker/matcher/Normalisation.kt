package io.github.aaroncchung.spoilerblocker.matcher

import java.text.Normalizer

/**
 * Cuts [text] into the words that get compared. Terms and texts both go
 * through here, so whatever it does to one it also does to the other.
 *
 * "Pérez wins the #JapaneseGP2026!" becomes perez, wins, the, japanese, gp,
 * 2026:
 *
 * - Everything is lower case and accents are removed. A few letters that
 *   are more than a plain letter with an accent get their plain spelling:
 *   "Ødegaard" is odegaard, "Straße" is strasse.
 * - Anything that is not a letter or a digit only separates words and is
 *   then forgotten: spaces, punctuation, "#", "@", "_", emoji. The apostrophe
 *   too, so "Max's" is max, s.
 * - Inside a run of letters and digits, a new word starts at a capital after
 *   a small letter and wherever letters meet digits. That takes hashtags and
 *   names like "McLaren" apart.
 * - After several capitals, the last one is a word of its own when a small
 *   letter follows it. It could end the capitals or start the next word, and
 *   nothing says which: "GPHighlights" is gp, h, ighlights and "@NBAonTNT"
 *   is nb, a, on, tnt. A term may run several words together, so "GP",
 *   "Highlights", "NBA" and "on" are all found. Not when that small letter is
 *   an "s" with no letter after it, which makes a plural: "GPs" and "NPCs"
 *   stay whole, while "#NBAIsBack" is nba, i, s, back.
 * - Letters of one case run together stay one word: "#suzukacircuit" is
 *   suzukacircuit, and "#SUZUKAF1" is suzukaf, 1.
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
            words += finished(word)
            word.clear()
        }
        if (isPartOfAWord) word.appendCodePoint(character)
    }
    if (word.isNotEmpty()) words += finished(word)
    return words
}

/**
 * A word that [normalise] has cut out, in the form that is compared. Lower
 * case comes only now, because the capitals said where to cut. The plain
 * spellings come after lower case, so their table needs only small letters.
 */
private fun finished(word: CharSequence): String = withPlainSpellings(word.toString().lowercase())

/** [word] with every letter from [plainSpellingOf] replaced by its plain spelling. */
private fun withPlainSpellings(word: String): String {
    // Most words have none of these letters and need no new string.
    if (word.all { plainSpellingOf(it) == null }) return word
    return buildString {
        for (character in word) {
            val plain = plainSpellingOf(character)
            if (plain == null) append(character) else append(plain)
        }
    }
}

/**
 * The plain spelling of a small letter that is more than a plain letter with
 * an accent, or null for any other character. NFKD leaves these letters as
 * they are, so without this "Odegaard" would not find "Ødegaard". Each is
 * spelled the way English text usually writes it.
 */
private fun plainSpellingOf(letter: Char): String? = when (letter) {
    'ø' -> "o" // Danish and Norwegian: Ødegaard
    'ł' -> "l" // Polish: Łukasz
    // Croatian and Vietnamese. English writes some Serbian names with "dj",
    // which this does not give: "Djokovic" is still not found in "Đoković".
    'đ' -> "d"
    'ð' -> "d" // Icelandic
    'ħ' -> "h" // Maltese
    'ŧ' -> "t" // Northern Sami
    'ı' -> "i" // Turkish dotless i: Diyarbakır
    'ß' -> "ss" // German: Straße
    'æ' -> "ae"
    'œ' -> "oe"
    'þ' -> "th" // Icelandic
    // Greek writes "s" as "ς" at the end of a word and as "σ" elsewhere.
    // Where words are run together, as in a hashtag, the last "s" of a word
    // is no longer at the end and is written "σ". So the two are one letter.
    'ς' -> "σ"
    else -> null
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
 * Letters that are more than a plain letter with an accent stay as they are
 * here: NFKD does not turn "ø" into "o", nor "ß" into "ss". [normalise] gives
 * them their plain spelling later, with [plainSpellingOf].
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
    return when {
        // "Suzuka2026", "P1": a number is a word of its own. This is also why
        // "P1" (p, 1) is not found in "P10" (p, 10).
        Character.isDigit(previous) != Character.isDigit(current) -> true
        // "JapaneseGP": a capital after a small letter.
        Character.isLowerCase(previous) && Character.isUpperCase(current) -> true
        // "GPHighlights", "NBAonTNT": the "H" and the "A" are words of their
        // own, so a word starts at them and another one right after them.
        isLastCapitalBeforeSmallLetter(characters, index) -> true
        isLastCapitalBeforeSmallLetter(characters, index - 1) -> true
        // No spaces to go by, so every character is a word. See below.
        isWrittenWithoutSpaces(previous) || isWrittenWithoutSpaces(current) -> true
        else -> false
    }
}

/**
 * True if the character at [index] is the last of several capitals and a
 * small letter follows it: the "H" in "GPHighlights", the "A" in "NBAonTNT".
 * [normalise] makes such a capital a word of its own, because it could end
 * the capitals ("NBA", "on") or start the next word ("GP", "Highlights").
 */
private fun isLastCapitalBeforeSmallLetter(characters: IntArray, index: Int): Boolean {
    // Before the first and after the last character, a space stands in for
    // "nothing there". (' '.code is the code point of a space.)
    val previous = characters.getOrElse(index - 1) { ' '.code }
    val current = characters.getOrElse(index) { ' '.code }
    val next = characters.getOrElse(index + 1) { ' '.code }
    val afterNext = characters.getOrElse(index + 2) { ' '.code }
    // "GPs", "NPCs", "PCs2026": a small "s" after capitals, with no letter
    // after it, makes a plural and stays with the capitals. In "#NBAIsBack" a
    // capital follows the "s", so the "s" belongs to the short word "Is".
    val isPluralS = next == 's'.code && !Character.isLetter(afterNext)
    return Character.isUpperCase(previous) && Character.isUpperCase(current) &&
        Character.isLowerCase(next) && !isPluralS
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
