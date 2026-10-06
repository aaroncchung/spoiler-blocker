package io.github.aaroncchung.spoilerblocker.matcher

import org.junit.Assert.assertEquals
import org.junit.Test

// Characters that cannot be seen, or cannot be told from an ordinary one, are
// named here by their Unicode number and not typed into the tests.
private val COMBINING_ACUTE_ACCENT = Char(0x0301)
private val SOFT_HYPHEN = Char(0x00AD)
private val ZERO_WIDTH_NON_JOINER = Char(0x200C)
private val ZERO_WIDTH_JOINER = Char(0x200D)
private val WORD_JOINER = Char(0x2060)
private val BYTE_ORDER_MARK = Char(0xFEFF)
private val MODIFIER_LETTER_APOSTROPHE = Char(0x02BC)
private val FULL_WIDTH_F = Char(0xFF26)
private val FULL_WIDTH_1 = Char(0xFF11)
private val FI_LIGATURE = Char(0xFB01)

class NormalisationTest {

    private fun words(text: String): List<String> = normalise(text)

    @Test
    fun `the example from the documentation`() {
        assertEquals(
            listOf("perez", "wins", "the", "japanese", "gp", "2026"),
            words("Pérez wins the #JapaneseGP2026!"),
        )
    }

    @Test
    fun `words are lower case`() {
        assertEquals(listOf("suzuka", "suzuka", "suzuka"), words("Suzuka SUZUKA suzuka"))
    }

    @Test
    fun `accents are removed`() {
        assertEquals(listOf("perez"), words("Pérez"))
        assertEquals(listOf("perez"), words("PÉREZ"))
        // The same name written as "e" followed by a separate accent character.
        assertEquals(listOf("perez"), words("Pe${COMBINING_ACUTE_ACCENT}rez"))
        assertEquals(listOf("hulkenberg", "raikkonen"), words("Hülkenberg, Räikkönen"))
        assertEquals(listOf("sao", "paulo"), words("São Paulo"))
        // Turkish capital dotted I.
        assertEquals(listOf("istanbul"), words("İstanbul"))
    }

    @Test
    fun `letters that are more than a letter with an accent are kept`() {
        // Known limits: these will not match "Odegaard", "Fussball" and "DIYARBAKIR".
        assertEquals(listOf("ødegaard"), words("Ødegaard"))
        assertEquals(listOf("fußball"), words("Fußball"))
        assertEquals(listOf("diyarbakır"), words("Diyarbakır")) // Turkish dotless i
    }

    @Test
    fun `look-alike characters become the ordinary ones`() {
        // Full-width letters and digits, as typed with a Japanese keyboard.
        assertEquals(listOf("f", "1"), words("$FULL_WIDTH_F$FULL_WIDTH_1"))
        // "Max" in mathematical bold letters, which some posts use as decoration.
        assertEquals(listOf("max"), words("𝗠𝗮𝘅"))
        // "final" with the single character for "fi".
        assertEquals(listOf("final"), words("${FI_LIGATURE}nal"))
    }

    @Test
    fun `trade mark signs do not become letters`() {
        assertEquals(listOf("suzuka"), words("SUZUKA™"))
        assertEquals(listOf("star", "wars", "outlaws"), words("STAR WARS™ Outlaws"))
        assertEquals(listOf("suzuka", "tours"), words("Suzuka℠ Tours"))
    }

    @Test
    fun `invisible characters inside a word are dropped`() {
        val invisible = listOf(SOFT_HYPHEN, ZERO_WIDTH_NON_JOINER, ZERO_WIDTH_JOINER, WORD_JOINER, BYTE_ORDER_MARK)

        for (character in invisible) {
            val name = "U+%04X".format(character.code)
            assertEquals(name, listOf("suzuka"), words("Suz${character}uka"))
            assertEquals(name, listOf("at", "suzuka"), words("${character}at Suzuka$character"))
        }
    }

    @Test
    fun `everything that is not a letter or a digit separates words`() {
        assertEquals(listOf("japanese", "grand", "prix"), words("  Japanese\tGrand\nPrix  "))
        assertEquals(listOf("japanese", "grand", "prix"), words("Japanese-Grand-Prix!?"))
        assertEquals(listOf("sky", "sports", "f", "1"), words("@sky_sports.f1"))
        assertEquals(listOf("suzuka"), words("SUZUKA🏁")) // chequered flag
        assertEquals(listOf("lights", "out"), words("lights🔴🔴out")) // two red circles
    }

    @Test
    fun `an apostrophe separates words too`() {
        assertEquals(listOf("max", "s", "lap"), words("Max's lap"))
        assertEquals(listOf("max", "s", "lap"), words("Max’s lap")) // curly apostrophe
        // Looks like an apostrophe, but Unicode counts it as a letter.
        assertEquals(listOf("max", "s", "lap"), words("Max${MODIFIER_LETTER_APOSTROPHE}s lap"))
    }

    @Test
    fun `a new word starts at a capital letter`() {
        assertEquals(listOf("japanese", "gp"), words("#JapaneseGP"))
        assertEquals(listOf("japanese", "gp", "highlights"), words("JapaneseGPHighlights"))
        assertEquals(listOf("mc", "laren"), words("McLaren"))
        assertEquals(listOf("i", "phone"), words("iPhone"))
        assertEquals(listOf("nba", "finals"), words("#NBAFinals"))
    }

    @Test
    fun `a plural s after capitals stays with them`() {
        assertEquals(listOf("three", "gps", "in", "a", "row"), words("Three GPs in a row"))
        assertEquals(listOf("npcs"), words("NPCs"))
        assertEquals(listOf("pcs", "2026"), words("PCs2026"))
        assertEquals(listOf("gps", "and", "more"), words("GPsAndMore"))
        // Not a plural: more small letters follow the "s", so the "T" starts a name.
        assertEquals(listOf("rb", "tsunoda"), words("#RBTsunoda"))
    }

    @Test
    fun `capitals without small letters are one word`() {
        assertEquals(listOf("suzuka"), words("SUZUKA"))
        assertEquals(listOf("formula"), words("FORMULA"))
    }

    @Test
    fun `no capitals means no cut`() {
        assertEquals(listOf("japanesegp"), words("#japanesegp"))
    }

    @Test
    fun `a number is a word of its own`() {
        assertEquals(listOf("p", "1"), words("P1"))
        assertEquals(listOf("p", "10"), words("P10"))
        assertEquals(listOf("suzuka", "2026"), words("#Suzuka2026"))
        assertEquals(listOf("2026", "japanese", "gp"), words("2026JapaneseGP"))
        assertEquals(listOf("f", "12026"), words("#F12026"))
        assertEquals(listOf("2026"), words("2026"))
    }

    @Test
    fun `nothing to find gives no words`() {
        val nothing = listOf("", " ", "\n\t", "?!...", "#", "'", "™", "🏁🏆", "$COMBINING_ACUTE_ACCENT", "$SOFT_HYPHEN")

        for (text in nothing) {
            assertEquals("words of \"$text\"", emptyList<String>(), words(text))
        }
    }

    @Test
    fun `scripts without spaces give one word per character`() {
        // "Suzuka Circuit" in Japanese: two kanji, then katakana.
        assertEquals(listOf("鈴", "鹿", "サ", "ー", "キ", "ッ", "ト"), words("鈴鹿サーキット"))
        // "Japan GP": kanji next to Latin letters.
        assertEquals(listOf("日", "本", "gp"), words("日本GP"))
        // "Chinese Grand Prix" in Chinese.
        assertEquals(listOf("中", "国", "大", "奖", "赛"), words("中国大奖赛"))
        // "Max" in Thai. The marks above the second and the last letter are removed with the accents.
        assertEquals(listOf("แ", "ม", "ก", "ซ"), words("แม็กซ์"))
        // The first two are rare Chinese characters that take two Chars each.
        assertEquals(listOf("𠮷", "𠮷", "野", "家"), words("𠮷𠮷野家"))
    }

    @Test
    fun `Japanese sound marks are removed with the accents`() {
        // "gasu" (gas) loses its two small strokes and becomes "kasu". A known looseness.
        assertEquals(words("カス"), words("ガス"))
    }

    @Test
    fun `scripts with spaces are cut at the spaces`() {
        assertEquals(listOf("гран", "при", "японии"), words("Гран-при Японии")) // Russian
        assertEquals(listOf("γκραν", "πρι", "ιαπωνιας"), words("Γκραν Πρι Ιαπωνίας")) // Greek
    }
}
