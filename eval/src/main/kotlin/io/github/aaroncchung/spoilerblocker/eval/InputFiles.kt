package io.github.aaroncchung.spoilerblocker.eval

import io.github.aaroncchung.spoilerblocker.matcher.BlockerTerms
import io.github.aaroncchung.spoilerblocker.matcher.Breadth
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.InvalidPathException
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
    val file = try {
        Path.of(path)
    } catch (e: InvalidPathException) {
        // A character that cannot be in a path, such as "*" or "?" on Windows.
        throw BadInputException("this is not a valid file path")
    }
    if (!Files.isRegularFile(file)) throw BadInputException("file not found")
    val text = try {
        Files.readString(file)
    } catch (e: CharacterCodingException) {
        throw BadInputException("this is not UTF-8 text. Save the file with the encoding UTF-8 and try again.")
    } catch (e: IOException) {
        // CharacterCodingException is an IOException too, so this has to come
        // second. What is left is mostly a file that this user may not read,
        // or that another program holds open. The class name says which.
        throw BadInputException("the file could not be read (${e.javaClass.simpleName})")
    }
    // Some Windows editors start a file with an invisible byte-order mark,
    // which is not valid JSON.
    return text.removePrefix(BYTE_ORDER_MARK)
}

private val BYTE_ORDER_MARK = Char(0xFEFF).toString()

// parseBlocker() and parseExamples() below use Json.decodeFromString, and rely
// on two things it does:
//
// - It is strict. A key it does not know, such as "text" for "texts", is an
//   error and is not skipped, so a typo cannot quietly hide part of an example.
// - Whatever is wrong with the input (broken JSON, a missing key, a wrong
//   type, an unknown label), the exception is an IllegalArgumentException.
//
// It is not strict about one thing: a key that is written twice. It keeps the
// last value and says nothing, so both functions check for that themselves
// with firstRepeatedKey().

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
        throw BadInputException(whatIsWrong(e))
    }
    val repeatedKey = firstRepeatedKey(text)
    if (repeatedKey != null) throw BadInputException("\"$repeatedKey\" is given twice")
    val breadth = when (file.breadth) {
        "narrow" -> Breadth.NARROW
        "broad" -> Breadth.BROAD
        else -> throw BadInputException("\"breadth\" must be \"narrow\" or \"broad\", not \"${file.breadth}\"")
    }
    return BlockerTerms(strong = file.strong, weak = file.weak, sources = file.sources, breadth = breadth)
}

/**
 * Reads the content of an examples file. Each line is one JSON object:
 * `{"kind":"post","label":"related","texts":[...],"sources":[...],"note":"..."}`,
 * where "kind", "sources" and "note" may be left out. Blank lines are skipped.
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
            throw BadInputException("line $lineNumber: ${whatIsWrong(e)}")
        }
        val repeatedKey = firstRepeatedKey(line)
        if (repeatedKey != null) {
            throw BadInputException("line $lineNumber: \"$repeatedKey\" is given twice")
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

/**
 * What is wrong, from an error thrown by Json.decodeFromString. It is the
 * first line of the error's message, which says what and where, such as
 * "Unexpected JSON token at offset 20: Encountered an unknown key 'text' at path: $".
 *
 * The lines after it are left out. One repeats the input, which for an
 * example is its whole line again. The others are hints for programmers,
 * such as "Use 'ignoreUnknownKeys = true'", and following them would switch
 * off the strictness that this file relies on.
 */
private fun whatIsWrong(e: IllegalArgumentException): String =
    e.message?.substringBefore('\n') ?: "it is not in the expected format"

/**
 * The first key that is written twice in the JSON object [json], or null if
 * every key is different. Call it only on text that Json.decodeFromString has
 * already read without an error.
 */
private fun firstRepeatedKey(json: String): String? {
    val seen = mutableSetOf<String>()
    for (key in Json.decodeFromString(KeysInOrder, json)) {
        if (key in seen) return key
        seen += key
    }
    return null
}

/**
 * Reads a JSON object as the list of its keys, in the order they are written.
 * A key that is written twice is in the list twice, which is the point:
 * reading into a class or a Map would keep only one of them.
 *
 * kotlinx.serialization reads a Map one entry at a time, and this does the
 * same but keeps only the keys. The reader numbers the entries: 0 is the
 * first key, 1 its value, 2 the next key and so on. At the closing brace it
 * returns DECODE_DONE. Each value is read as a JsonElement, which can be any
 * JSON, and dropped.
 */
private object KeysInOrder : DeserializationStrategy<List<String>> {
    override val descriptor: SerialDescriptor =
        MapSerializer(String.serializer(), JsonElement.serializer()).descriptor

    override fun deserialize(decoder: Decoder): List<String> = decoder.decodeStructure(descriptor) {
        val keys = mutableListOf<String>()
        while (true) {
            val index = decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) break
            if (index % 2 == 0) {
                keys += decodeStringElement(descriptor, index)
            } else {
                decodeSerializableElement(descriptor, index, JsonElement.serializer())
            }
        }
        keys
    }
}
