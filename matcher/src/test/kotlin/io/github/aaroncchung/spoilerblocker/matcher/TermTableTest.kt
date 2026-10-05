package io.github.aaroncchung.spoilerblocker.matcher

import org.junit.Assert.assertEquals
import org.junit.Test

// Characters that cannot be seen, or cannot be told from an ordinary one, are
// named here by their Unicode number and not typed into the rows.
private val COMBINING_ACUTE_ACCENT = Char(0x0301)
private val SOFT_HYPHEN = Char(0x00AD)
private val ZERO_WIDTH_NON_JOINER = Char(0x200C)
private val ZERO_WIDTH_JOINER = Char(0x200D)
private val WORD_JOINER = Char(0x2060)
private val MODIFIER_LETTER_APOSTROPHE = Char(0x02BC)
private val FULL_WIDTH_F1 = "${Char(0xFF26)}${Char(0xFF11)}"

/**
 * A table of single terms against single texts: is the term found or not?
 *
 * Each row is tried with the term as a blocker's only strong term. Rows that
 * belong together are kept next to each other, so the table also documents
 * where the line is drawn. Every text is invented.
 */
class TermTableTest {

    private class Row(val term: String, val text: String, val found: Boolean)

    private fun yes(term: String, text: String) = Row(term, text, found = true)

    private fun no(term: String, text: String) = Row(term, text, found = false)

    private val table = listOf(
        // Case.
        yes("Suzuka", "Suzuka"),
        yes("Suzuka", "SUZUKA"),
        yes("SUZUKA", "live from suzuka"),

        // A term starts where a word starts and ends where a word ends.
        yes("Max", "Can Max do it again?"),
        no("Max", "maximum attack"),
        no("Max", "MAXIMUM ATTACK"),
        no("Max", "the climax of the season"),
        no("Max", "Maxwell's equations"),
        no("podium", "podiums"),

        // Accents, both ways round.
        yes("Pérez", "Perez spins"),
        yes("Perez", "Pérez spins"),
        yes("Pérez", "PÉREZ SPINS"),
        yes("Perez", "Pe${COMBINING_ACUTE_ACCENT}rez spins"), // "e" followed by a separate accent character
        yes("Hulkenberg", "Hülkenberg in the points"),
        yes("İstanbul", "ISTANBUL PARK"), // Turkish capital dotted I
        no("Ødegaard", "Odegaard scores"), // known limit: "ø" is a letter of its own
        no("Diyarbakır", "DIYARBAKIR"), // known limit: so is the Turkish dotless "ı"

        // Hashtags and capitals inside a word.
        yes("Japanese GP", "#JapaneseGP"),
        yes("Japanese GP", "#JapaneseGPHighlights"),
        yes("#JapaneseGP", "Japanese GP highlights"),
        yes("#JapaneseGP", "#JapaneseGP"),
        yes("Suzuka", "#SuzukaCircuit"),
        yes("Suzuka", "#Suzuka2026"),
        yes("Max", "#MaxVerstappen"),
        yes("McLaren", "Mc Laren"),

        // The same letters, whatever the spaces, capitals and punctuation inside.
        yes("Japanese GP", "#japanesegp"),
        yes("Japanese GP", "#JAPANESEGP"),
        yes("Japanese GP", "Japanese G.P."),
        yes("#JapaneseGP", "#japanesegp"),
        yes("#japanesegp", "#JapaneseGP"),
        yes("#japanesegp", "Japanese GP"),
        yes("McLaren", "MCLAREN"),
        yes("McLaren", "Mclaren"),
        yes("mclaren", "McLaren"),
        yes("McLaren F1", "MCLAREN F1"),
        yes("McLaren F1", "Mclaren F1"),
        yes("McLaren F1", "mclaren f1"),
        yes("Rory McIlroy", "RORY MCILROY WINS THE MASTERS"),
        yes("LeBron James", "Lebron James"),
        yes("LeBron James", "LEBRON JAMES"),
        yes("Lebron James", "LeBron James"),
        yes("PlayStation 5", "Playstation 5"),
        yes("Sky Sports F1", "Skysports F1"),
        yes("Spider-Man: Brand New Day", "Spiderman Brand New Day trailer"),
        yes("Grey's Anatomy", "Greys Anatomy"),
        yes("Women's World Cup", "Womens World Cup"),
        yes("US Open", "U.S. Open"),
        yes("U.S. Open", "US Open"),
        yes("T.J. Watt", "TJ Watt"),
        yes("Agents of S.H.I.E.L.D.", "Agents of SHIELD"),

        // A number next to the term ends a word, so the term is still found.
        yes("Japanese GP", "#japanesegp2026"),
        yes("Japanese GP", "#JAPANESEGP2026"),
        yes("Japanese GP", "#2026japanesegp"),
        yes("Japanese GP", "#f1japanesegp"),
        yes("Max Verstappen", "maxverstappen1"),
        yes("Max Verstappen", "@maxverstappen33"),

        // Known limit: small letters run together give no hint where a word ends.
        no("Japanese GP", "#japanesegphighlights"),
        no("Suzuka", "#suzukacircuit"),

        // Possessives and punctuation.
        yes("Max", "Max's lap"),
        yes("Max", "Max’s lap"), // curly apostrophe
        yes("Max", "Max${MODIFIER_LETTER_APOSTROPHE}s lap"), // looks like an apostrophe, counts as a letter
        yes("Max", "(Max)"),
        yes("Max", "Max."),
        yes("Max", "max?!"),
        yes("Suzuka", "SUZUKA🏁"), // chequered flag
        yes("Suzuka", "...Suzuka!!!"),
        yes("Suzuka", "\"Suzuka\""),

        // Trade mark signs do not turn into the letters "TM" and stick to the word.
        yes("Suzuka", "SUZUKA™"),
        yes("Star Wars", "STAR WARS™ Outlaws"),
        yes("FIFA World Cup", "FIFA WORLD CUP™"),
        yes("Suzuka", "SUZUKA℠"),

        // Invisible characters inside a word do not split it.
        yes("Suzuka", "Suz${SOFT_HYPHEN}uka"),
        yes("Suzuka", "Su${ZERO_WIDTH_NON_JOINER}zuka"),
        yes("Suzuka", "Su${ZERO_WIDTH_JOINER}zuka"),
        yes("Suzuka", "Su${WORD_JOINER}zuka"),

        // Numbers.
        yes("P1", "P1"),
        yes("P1", "p1!"),
        yes("P1", "P 1"),
        no("P1", "P10"),
        no("P1", "P11"),
        no("P1", "FP1"),
        no("P10", "P1"),
        yes("F1", "#F1"),
        yes("F1", "F-1"),
        no("F1", "F12"),
        no("F1", "#F12026"), // known limit: nothing says the number is 1 and then 2026
        yes("2026", "Suzuka 2026"),
        yes("2026", "#Suzuka2026"),
        no("2026", "20260 points"),

        // Terms of several words.
        yes("Japanese Grand Prix", "2026 Japanese Grand Prix: race highlights"),
        yes("Japanese Grand Prix", "Japanese-Grand-Prix"),
        yes("Japanese Grand Prix", "Japanese   Grand\nPrix"),
        yes("Japanese  Grand--Prix", "Japanese Grand Prix"),
        yes("Japanese Grand Prix", "#JapaneseGrandPrix"),
        yes("Japanese Grand Prix", "#japanesegrandprix"),
        no("Japanese Grand Prix", "Japanese F1 Grand Prix"),
        no("Japanese Grand Prix", "Grand Prix Japanese"),
        no("Japanese Grand Prix", "Japanese Grand"),
        no("Japanese Grand Prix", "Grand Prix"),
        yes("Grand Prix", "Grand Grand Prix"),
        no("Grand Prix", "A grand"),

        // Known over-blocks, accepted because a miss costs more (decision 4).
        // Neighbouring words that happen to add up to the term:
        yes("therapist", "the rapist"),
        yes("stop", "Max's top ten"),
        yes("Manu", "Man U"),
        yes("iPhone", "I phone my mum"),
        yes("F1", "f/1.8 lens"),
        yes("C++", "plan c"), // only the letter is left of the term
        // A short term that is one part of a name with a capital inside:
        yes("You", "Watch it on YouTube"),
        yes("MC", "McLaren unveil the car"),
        // Not these: a plural "s" after capitals stays with them.
        no("PS", "Three GPs in a row"),
        no("CS", "NPCs everywhere"),

        // Look-alike characters.
        yes("F1", FULL_WIDTH_F1),
        yes("Max", "𝗠𝗮𝘅 wins"), // "Max" in mathematical bold letters

        // Texts with nothing to find.
        no("Suzuka", ""),
        no("Suzuka", "   "),
        no("Suzuka", "?!..."),
        no("Suzuka", "🏁🏆"),
        no("Suzuka", "1 2 3"),

        // Japanese, Chinese and Thai have no spaces between words, so a term in
        // those scripts is found anywhere in a run of characters.
        yes("鈴鹿", "鈴鹿サーキットで決勝"), // "Suzuka" in "the final at Suzuka Circuit"
        yes("日本GP", "F1日本GP決勝"), // "Japan GP" in "F1 Japan GP final"
        yes("中国大奖赛", "2026中国大奖赛正赛"), // "Chinese Grand Prix"
        yes("ซูซูกะ", "การแข่งขันที่ซูซูกะวันนี้"), // "Suzuka" in "the race at Suzuka today"
        yes("𠮷", "𠮷𠮷野家"), // a rare character that takes two Chars
        no("鈴鹿", "鹿鈴"), // the characters must be in the same order
        no("鈴鹿", "鈴木"), // "Suzuki" only shares the first character
        // The price: "Kyoto" is found inside "Tokyo Metropolis", an unrelated word.
        yes("京都", "東京都"),
        // Known looseness: Japanese sound marks go with the accents, so "gasu" (gas) is found in "kasu".
        yes("ガス", "カス"),

        // Known limit: Korean has spaces, but small words are attached to the
        // end of a noun. "Suzuka" is missed in "at Suzuka".
        no("스즈카", "스즈카에서 열린 결승"),

        // Known limit: Greek writes "s" differently at the end of a word, and
        // lower-casing capitals run together cannot know where the first word ended.
        yes("Ολυμπιακός Πειραιώς", "ΟΛΥΜΠΙΑΚΟΣ ΠΕΙΡΑΙΩΣ"),
        no("Ολυμπιακός Πειραιώς", "#ΟΛΥΜΠΙΑΚΟΣΠΕΙΡΑΙΩΣ"),
    )

    @Test
    fun `every row of the table`() {
        val wrongRows = table.mapNotNull { row ->
            val matcher = Matcher(BlockerTerms(strong = listOf(row.term)))
            val found = matcher.check(Candidate(texts = listOf(row.text))) is Verdict.Blocked
            if (found == row.found) null else "\"${row.term}\" in \"${row.text}\": expected found = ${row.found}"
        }

        assertEquals(emptyList<String>(), wrongRows)
    }

    @Test
    fun `a found term is reported as typed`() {
        for (row in table.filter { it.found }) {
            val matcher = Matcher(BlockerTerms(strong = listOf(row.term)))

            val verdict = matcher.check(Candidate(texts = listOf(row.text)))

            assertEquals(Verdict.Blocked(Reason.StrongTerm(row.term)), verdict)
        }
    }
}
