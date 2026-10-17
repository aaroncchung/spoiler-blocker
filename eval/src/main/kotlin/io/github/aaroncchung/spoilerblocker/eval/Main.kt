package io.github.aaroncchung.spoilerblocker.eval

import io.github.aaroncchung.spoilerblocker.matcher.Reason
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.Locale
import kotlin.system.exitProcess

private const val USAGE = "Usage: ./gradlew -q :eval:run --args=\"<blocker.json> <examples.jsonl>\""

// Java's own System.out uses an old encoding on Windows. When that was tried,
// accents and Japanese came out of Gradle as question marks. These two always
// write UTF-8, whatever the Java version. They are only used with print() and
// "\n", so that every line ends the same way on every system.
private val output = PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8)
private val errors = PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8)

/** Runs the matcher over a file of labelled examples and prints the misses and over-blocks. */
fun main(args: Array<String>) {
    if (args.size != 2) stop(USAGE)
    val (blockerPath, examplesPath) = args

    val terms = try {
        parseBlocker(readTextFile(blockerPath))
    } catch (e: BadInputException) {
        stop("$blockerPath: ${e.message}")
    }
    val examples = try {
        parseExamples(readTextFile(examplesPath))
    } catch (e: BadInputException) {
        stop("$examplesPath: ${e.message}")
    }

    output.print("Blocker:  $blockerPath\nExamples: $examplesPath\n\n")
    output.print(describe(evaluate(terms, examples)))
}

/**
 * Prints [message] and ends the program with an error code. The return type
 * Nothing says that this function never returns, which is why the compiler
 * accepts it above where a value is expected.
 */
private fun stop(message: String): Nothing {
    errors.print(message + "\n")
    exitProcess(1)
}

/** The report as plain text for a terminal. */
internal fun describe(report: Report): String = buildString {
    val total = report.relatedCount + report.unrelatedCount
    if (total == 0) {
        appendLine("Warning: there are no examples, so nothing was measured.")
        appendLine()
    }
    appendLine("Examples:    $total (${report.relatedCount} related, ${report.unrelatedCount} unrelated)")

    // A rate is null when there was nothing to take a share of. Printing
    // "0 of 0" there would read like a perfect result.
    val missRate = report.missRate
    if (missRate == null) {
        appendLine("Misses:      not measured, there are no related examples")
    } else {
        appendLine(
            "Misses:      ${report.misses.size} of ${report.relatedCount} related examples were allowed" +
                " (${percentage(missRate)})",
        )
    }
    val overBlockRate = report.overBlockRate
    if (overBlockRate == null) {
        appendLine("Over-blocks: not measured, there are no unrelated examples")
    } else {
        appendLine(
            "Over-blocks: ${report.overBlocks.size} of ${report.unrelatedCount} unrelated examples were blocked" +
                " (${percentage(overBlockRate)})",
        )
    }

    if (report.misses.isNotEmpty()) {
        appendLine()
        appendLine("Misses")
        for (example in report.misses) {
            appendLine("  line ${example.line}: ${summary(example)}")
            if (example.note != null) appendLine("    note: ${example.note}")
        }
    }

    if (report.overBlocks.isNotEmpty()) {
        appendLine()
        appendLine("Over-blocks")
        for (overBlock in report.overBlocks) {
            appendLine("  line ${overBlock.example.line}: ${summary(overBlock.example)}")
            appendLine("    blocked by ${describe(overBlock.reason)}")
            if (overBlock.example.note != null) appendLine("    note: ${overBlock.example.note}")
        }
    }
}

/** A rate from 0.0 to 1.0 as "12.5%". */
private fun percentage(rate: Double): String =
    // Locale.ROOT keeps the decimal point a point, whatever language the PC is set to.
    String.format(Locale.ROOT, "%.1f%%", rate * 100)

private const val SUMMARY_LENGTH = 100

/** One line for an example: its sources in brackets, then its texts, cut short if long. */
private fun summary(example: Example): String {
    val sources = example.sources.joinToString(separator = "") { "[$it] " }
    val texts = example.texts.joinToString(separator = " | ")
    // Captions can contain line breaks. One example stays on one line.
    val oneLine = (sources + texts).replace(Regex("""\s+"""), " ")
    return if (oneLine.length <= SUMMARY_LENGTH) oneLine else oneLine.take(SUMMARY_LENGTH - 3) + "..."
}

private fun describe(reason: Reason): String = when (reason) {
    is Reason.Source -> "the source \"${reason.source}\""
    is Reason.StrongTerm -> "the strong term \"${reason.term}\""
    is Reason.WeakTerms -> "the weak terms " + reason.terms.joinToString { "\"$it\"" }
    is Reason.WeakTermBroad -> "the weak term \"${reason.term}\", because the breadth is broad"
}
