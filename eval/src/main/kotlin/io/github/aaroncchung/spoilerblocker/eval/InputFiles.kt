package io.github.aaroncchung.spoilerblocker.eval

import io.github.aaroncchung.spoilerblocker.matcher.BlockerTerms
import io.github.aaroncchung.spoilerblocker.matcher.Breadth
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path

/** An input file is not in the expected format. The message is written for the person running the script. */
class BadInputException(message: String) : Exception(message)

/**
 * Reads a whole file as UTF-8 text.
 *
 * Files.readString refuses bytes that are not UTF-8. The simpler
 * File.readText would replace them with a placeholder character and carry
 * on, so a file saved in an old Windows encoding would give wrong counts
 * without any error: "Pérez" would no longer be "Pérez".
 */
internal fun readTextFile(path: String): String {
    val file = Path.of(path)
    if (!Files.isRegularFile(file)) throw BadInputException("file not found")
    val text = try {
        Files.readString(file)
    } catch (e: CharacterCodingException) {
        throw BadInputException("this is not UTF-8 text. Save the file with the encoding UTF-8 and try again.")
    }
    // Some Windows editors start a file with an invisible byte-order mark,
    // which is not valid JSON.
    return text.removePrefix(BYTE_ORDER_MARK)
}

private val BYTE_ORDER_MARK = Char(0xFEFF).toString()

// The two functions below use Json.decodeFromString, and rely on two things it does:
//
// - It is strict. A key it does not know, such as "text" for "texts", is an
//   error and is not skipped, so a typo cannot quietly hide part of an example.
// - Whatever is wrong with the input (broken JSON, a missing key, a wrong
//   type, an unknown label), the exception is an IllegalArgumentException.

/** The blocker file as it is written. Only "strong" has to be there. */
@Serializable
private data class BlockerFile(
    val strong: List<String>,
    val weak: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val breadth: String = "narrow",
)

/**
 * Reads the content of a blocker file:
 * `{"strong":[...],"weak":[...],"sources":[...],"breadth":"narrow"}`.
 */
fun parseBlocker(text: String): BlockerTerms {
    val file = try {
        Json.decodeFromString<BlockerFile>(text)
    } catch (e: IllegalArgumentException) {
        throw BadInputException(e.message ?: "not a blocker file")
    }
    val breadth = when (file.breadth) {
        "narrow" -> Breadth.NARROW
        "broad" -> Breadth.BROAD
        else -> throw BadInputException("\"breadth\" must be \"narrow\" or \"broad\", not \"${file.breadth}\"")
    }
    return BlockerTerms(strong = file.strong, weak = file.weak, sources = file.sources, breadth = breadth)
}

/**
 * Reads the content of an examples file. Each line is one JSON object:
 * `{"label":"related","texts":[...],"sources":[...],"note":"..."}`, where
 * "sources" and "note" may be left out. Blank lines are skipped.
 *
 * A line that cannot be read stops everything, and the error says which line
 * it was. Skipping it quietly would make the counts wrong.
 */
fun parseExamples(text: String): List<Example> {
    val examples = mutableListOf<Example>()
    for ((index, line) in text.lines().withIndex()) {
        if (line.isBlank()) continue
        val lineNumber = index + 1
        val example = try {
            Json.decodeFromString<Example>(line)
        } catch (e: IllegalArgumentException) {
            throw BadInputException("line $lineNumber: ${e.message}")
        }
        // An example without any text can never be blocked. It would count as
        // a miss or as correctly allowed without having tested anything.
        if (example.texts.all { it.isBlank() }) {
            throw BadInputException("line $lineNumber: \"texts\" has no text in it")
        }
        examples += example.copy(line = lineNumber)
    }
    return examples
}
