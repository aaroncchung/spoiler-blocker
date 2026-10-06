package io.github.aaroncchung.spoilerblocker.expansion

import java.io.File
import java.util.Properties
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private const val USAGE = """Usage, from the top of the repository. The breadth is narrow or broad.

  Git Bash:
    ./gradlew :expansion:run --args="\"2026 Japanese Grand Prix\" narrow"

  PowerShell, where the quotes are written differently:
    .\gradlew.bat :expansion:run --args='\"2026 Japanese Grand Prix\" narrow'
"""

// ${'$'} is how a raw string spells a dollar sign that is not a template.
private const val NO_KEY_HELP = """No Anthropic API key found. Set one in either of these ways:

  1. A line in local.properties at the top of the repository:
       anthropic.apiKey=sk-ant-...
     Git ignores that file. Never put the key in a file that is committed.

  2. An environment variable, for this terminal only:
       export ANTHROPIC_API_KEY=sk-ant-...        (Git Bash)
       ${'$'}env:ANTHROPIC_API_KEY = "sk-ant-..."      (PowerShell)

How to get a key: https://platform.claude.com/docs/en/get-api-key
"""

/**
 * Tries the expansion from a terminal on the PC, without the app. It makes
 * one real API call, which is billed to the key's account.
 */
fun main(args: Array<String>) {
    val breadth = when (args.getOrNull(1)?.lowercase()) {
        "narrow" -> Breadth.NARROW
        "broad" -> Breadth.BROAD
        else -> null
    }
    // In these two cases the program ends normally after explaining. If it
    // ended with an error code, Gradle would print "BUILD FAILED" underneath
    // and bury the explanation.
    if (args.size != 2 || breadth == null) {
        println(USAGE)
        return
    }
    val description = args[0]

    val apiKey = findApiKey()
    if (apiKey == null) {
        println(NO_KEY_HELP)
        return
    }

    println("Asking Claude about \"$description\" (${args[1]}). This can take a minute or more.")
    // runBlocking waits here for the suspend function; main() is not a coroutine.
    val result = runBlocking { KeywordExpander(apiKey).expand(description, breadth) }

    when (result) {
        is ExpansionResult.Success -> {
            printList("Strong terms", result.terms.strong)
            printList("Weak terms", result.terms.weak)
            printList("Sources", result.terms.sources)
        }

        is ExpansionResult.Failure -> {
            System.err.println("Failed (${result.kind}): ${result.message}")
            if (result.kind.canRetry) System.err.println("Trying again may work.")
        }
    }

    // OkHttp keeps a few threads alive for a minute after a call, which would
    // stop the program from ending. Exit now instead of waiting for them. A
    // call that failed ends with an error code, so that scripts can tell.
    exitProcess(if (result is ExpansionResult.Success) 0 else 1)
}

/** The environment variable wins. Otherwise the key comes from local.properties. */
private fun findApiKey(): String? {
    val fromEnvironment = System.getenv("ANTHROPIC_API_KEY")
    if (!fromEnvironment.isNullOrBlank()) return fromEnvironment

    // Relative to the repository root: the run task in build.gradle.kts starts
    // the program there.
    val file = File("local.properties")
    if (!file.isFile) return null
    val properties = Properties()
    file.reader().use { properties.load(it) }
    return properties.getProperty("anthropic.apiKey")?.takeIf { it.isNotBlank() }
}

private fun printList(title: String, terms: List<String>) {
    println()
    println("$title (${terms.size}):")
    for (term in terms) println("  $term")
}
