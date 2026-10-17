package io.github.aaroncchung.spoilerblocker.eval

import io.github.aaroncchung.spoilerblocker.matcher.BlockerTerms
import io.github.aaroncchung.spoilerblocker.matcher.Breadth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InputFilesTest {

    // The blocker file.

    @Test
    fun `a blocker file with everything in it`() {
        val terms = parseBlocker(
            """
            {
              "strong": ["Japanese Grand Prix", "Suzuka"],
              "weak": ["Max", "podium"],
              "sources": ["FORMULA 1"],
              "breadth": "broad"
            }
            """,
        )

        assertEquals(
            BlockerTerms(
                strong = listOf("Japanese Grand Prix", "Suzuka"),
                weak = listOf("Max", "podium"),
                sources = listOf("FORMULA 1"),
                breadth = Breadth.BROAD,
            ),
            terms,
        )
    }

    @Test
    fun `a blocker file needs only strong terms, and is narrow unless it says otherwise`() {
        val terms = parseBlocker("""{"strong":["Suzuka"]}""")

        assertEquals(BlockerTerms(strong = listOf("Suzuka"), breadth = Breadth.NARROW), terms)
    }

    @Test
    fun `a breadth that does not exist is refused`() {
        val error = assertThrows(BadInputException::class.java) {
            parseBlocker("""{"strong":["Suzuka"],"breadth":"wide"}""")
        }

        assertEquals("\"breadth\" must be \"narrow\" or \"broad\", not \"wide\"", error.message)
    }

    @Test
    fun `a blocker file that is not the right JSON is refused`() {
        assertThrows(BadInputException::class.java) { parseBlocker("") }
        assertThrows(BadInputException::class.java) { parseBlocker("strong: Suzuka") }
        assertThrows(BadInputException::class.java) { parseBlocker("""{"weak":["Max"]}""") } // no "strong"
        assertThrows(BadInputException::class.java) { parseBlocker("""{"strong":"Suzuka"}""") } // not a list
        assertThrows(BadInputException::class.java) { parseBlocker("""{"strong":[],"strnog":[]}""") } // unknown key
    }

    @Test
    fun `an error in a blocker file says what is wrong, without the file or hints for programmers`() {
        val error = assertThrows(BadInputException::class.java) {
            parseBlocker("""{"strong":["Suzuka"],"strnog":["Max"]}""")
        }
        val message = error.message!!

        assertTrue(message, message.contains("unknown key 'strnog'"))
        assertFalse(message, message.contains("Suzuka"))
        assertFalse(message, message.contains("ignoreUnknownKeys"))
        assertFalse(message, message.contains("\n"))
    }

    @Test
    fun `a key given twice in a blocker file is refused`() {
        // The JSON reader would keep ["b"] and drop ["a"] without a word.
        val error = assertThrows(BadInputException::class.java) {
            parseBlocker("""{"strong":["a"],"weak":["Max"],"strong":["b"]}""")
        }

        assertEquals("\"strong\" is given twice", error.message)
    }

    // The examples file.

    @Test
    fun `each line is one example, numbered from one`() {
        val examples = parseExamples(
            """
            {"label":"related","texts":["Rain at Suzuka"]}
            {"label":"unrelated","texts":["Dinner at 7?","Dad is cooking"],"sources":["WhatsApp","Family"],"note":"A message."}
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                Example(Label.RELATED, texts = listOf("Rain at Suzuka"), line = 1),
                Example(
                    Label.UNRELATED,
                    texts = listOf("Dinner at 7?", "Dad is cooking"),
                    sources = listOf("WhatsApp", "Family"),
                    note = "A message.",
                    line = 2,
                ),
            ),
            examples,
        )
    }

    @Test
    fun `an example is a post unless its line says it is a notification`() {
        val examples = parseExamples(
            """
            {"label":"related","texts":["Rain at Suzuka"]}
            {"kind":"post","label":"related","texts":["Rain at Suzuka"]}
            {"kind":"notification","label":"related","texts":["podium!!"],"sources":["WhatsApp","Max"]}
            """.trimIndent(),
        )

        assertEquals(listOf(Kind.POST, Kind.POST, Kind.NOTIFICATION), examples.map { it.kind })
    }

    @Test
    fun `blank lines are skipped but still counted`() {
        val examples = parseExamples(
            "\n" +
                """{"label":"related","texts":["one"]}""" + "\n" +
                "   \n" +
                """{"label":"related","texts":["two"]}""" + "\n",
        )

        assertEquals(listOf(2, 4), examples.map { it.line })
    }

    @Test
    fun `line endings from Windows are fine`() {
        val examples = parseExamples(
            """{"label":"related","texts":["one"]}""" + "\r\n" + """{"label":"unrelated","texts":["two"]}""" + "\r\n",
        )

        assertEquals(listOf(Label.RELATED, Label.UNRELATED), examples.map { it.label })
    }

    @Test
    fun `an empty file has no examples`() {
        assertEquals(emptyList<Example>(), parseExamples(""))
    }

    @Test
    fun `a bad line is reported with its number`() {
        val good = """{"label":"related","texts":["fine"]}"""
        val badLines = listOf(
            """{"label":"related","texts":["no closing bracket"}""",
            """not JSON at all""",
            """{"label":"maybe","texts":["a label that does not exist"]}""",
            """{"label":"related"}""", // no "texts"
            """{"label":"related","text":["a misspelt key"]}""",
            """{"label":"related","texts":"not a list"}""",
            """{"texts":["no label"]}""",
            """{"kind":"tweet","label":"related","texts":["a kind that does not exist"]}""",
            """{"label":"related","label":"unrelated","texts":["a key given twice"]}""",
            """{"label":"related","texts":[]}""", // nothing to check
            """{"label":"related","texts":["", "  "]}""", // nothing to check either
        )

        for (bad in badLines) {
            val error = assertThrows(bad, BadInputException::class.java) {
                parseExamples(good + "\n" + good + "\n" + bad + "\n" + good)
            }

            assertTrue("\"${error.message}\" should start with the line number", error.message!!.startsWith("line 3: "))
        }
    }

    @Test
    fun `an error says what is wrong and where, without the example's text or hints for programmers`() {
        // Each bad line, with what its error must still say.
        val badLines = mapOf(
            """{"label":"related","text":["Rain at Suzuka"]}""" to "unknown key 'text'",
            """{"label":"related","texts":["Rain at Suzuka"}""" to "offset 44",
            """{"label":"related","texts":["Rain at Suzuka"],}""" to "Trailing comma",
            """{"label":"related","texts":["Rain at Suzuka",null]}""" to "'null'",
        )

        for ((bad, expected) in badLines) {
            val message = assertThrows(bad, BadInputException::class.java) { parseExamples(bad) }.message!!

            assertTrue(message, message.startsWith("line 1: "))
            assertTrue(message, message.contains(expected))
            assertFalse(message, message.contains("Rain at Suzuka"))
            // Every hint kotlinx.serialization gives says how to change the 'Json {}' builder.
            assertFalse(message, message.contains("Json {}"))
            assertFalse(message, message.contains("\n"))
        }
    }

    @Test
    fun `a key given twice in an example is refused`() {
        // The JSON reader would keep the last value of each without a word:
        // the first would count the example as unrelated, the second would
        // drop the real text.
        val labelTwice = assertThrows(BadInputException::class.java) {
            parseExamples("""{"label":"related","label":"unrelated","texts":["Rain at Suzuka"]}""")
        }
        val textsTwice = assertThrows(BadInputException::class.java) {
            parseExamples(
                """{"label":"related","texts":["fine"]}""" + "\n" +
                    """{"label":"related","texts":["Rain at Suzuka"],"texts":["FORMULA 1"]}""",
            )
        }

        assertEquals("line 1: \"label\" is given twice", labelTwice.message)
        assertEquals("line 2: \"texts\" is given twice", textsTwice.message)
    }

    @Test
    fun `a text that looks like a key is not a key given twice`() {
        val examples = parseExamples("""{"label":"related","texts":["label","\"label\":\"unrelated\""],"note":"texts"}""")

        assertEquals(listOf("label", "\"label\":\"unrelated\""), examples.single().texts)
    }

    @Test
    fun `an example without any text is refused`() {
        val error = assertThrows(BadInputException::class.java) {
            parseExamples("""{"label":"unrelated","texts":[],"sources":["FORMULA 1"]}""")
        }

        assertEquals("line 1: \"texts\" has no text in it", error.message)
    }

    // Reading the files.

    // JUnit makes this folder before each test and deletes it afterwards.
    // "@get:Rule" puts the annotation on the property's getter, which is
    // where JUnit looks for it.
    @get:Rule
    val folder = TemporaryFolder()

    private fun fileWith(bytes: ByteArray): String {
        val file = folder.newFile()
        file.writeBytes(bytes)
        return file.path
    }

    @Test
    fun `a file is read as UTF-8`() {
        val path = fileWith("""{"strong":["Pérez","鈴鹿"]}""".toByteArray(Charsets.UTF_8))

        assertEquals("""{"strong":["Pérez","鈴鹿"]}""", readTextFile(path))
    }

    @Test
    fun `a byte-order mark at the start of a file is dropped`() {
        val byteOrderMark = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val path = fileWith(byteOrderMark + """{"strong":["Suzuka"]}""".toByteArray(Charsets.UTF_8))

        assertEquals("""{"strong":["Suzuka"]}""", readTextFile(path))
    }

    @Test
    fun `a file that is not UTF-8 is refused`() {
        // The way an old Windows editor saves "é": one byte that is not valid UTF-8.
        val path = fileWith("""{"strong":["Pérez"]}""".toByteArray(Charsets.ISO_8859_1))

        val error = assertThrows(BadInputException::class.java) { readTextFile(path) }

        assertTrue(error.message, error.message!!.contains("not UTF-8"))
    }

    @Test
    fun `a file that does not exist is refused`() {
        val missing = File(folder.root, "nothing-here.jsonl").path

        val error = assertThrows(BadInputException::class.java) { readTextFile(missing) }

        assertEquals("file not found", error.message)
    }

    @Test
    fun `a path that cannot be a path is refused`() {
        // No system allows the character with the number 0 in a path.
        val impossible = "nothing${Char(0)}here.jsonl"

        val error = assertThrows(BadInputException::class.java) { readTextFile(impossible) }

        assertEquals("this is not a valid file path", error.message)
    }
}
