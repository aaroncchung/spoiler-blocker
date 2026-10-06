package io.github.aaroncchung.spoilerblocker.expansion

import io.github.aaroncchung.spoilerblocker.expansion.ExpansionResult.Failure
import io.github.aaroncchung.spoilerblocker.expansion.ExpansionResult.Success
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync

// ---------------------------------------------------------------------------
// What is asked of the Claude API. Everything worth tuning is in this block.
// The request and reply shapes follow Anthropic's documentation for the
// Messages API, the web search tool and structured outputs, as read on
// 2026-10-05: https://platform.claude.com/docs/en/api/messages/create
// ---------------------------------------------------------------------------

// KeywordExpanderTest spells out the strings below a second time, so that a
// typing mistake here fails a test. Change both together.

/** The model that writes the lists. */
private const val MODEL = "claude-opus-5-5"

/**
 * The most the model may write in one reply, counted in tokens (word pieces).
 * It has to cover the model's private thinking and its search queries as well
 * as the lists, which are the small part.
 */
private const val MAX_TOKENS = 16_000

/** The anthropic-version header. It names the version of the API, not of the model. */
private const val API_VERSION = "2023-06-01"

/** The anthropic-beta header. It switches on "fallbacks", explained in requestBody(). */
private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

/**
 * How hard the model works: low, medium, high, xhigh or max. More effort means
 * more thinking and more searching, so a slower and dearer call.
 */
private const val EFFORT = "medium"

/** Anthropic's web search tool. The searches run on Anthropic's servers. */
private const val WEB_SEARCH_TOOL = "web_search_20260209"

/** The most searches one call may make. Each search is billed separately. */
internal const val MAX_WEB_SEARCHES = 8

/** How many times an unfinished reply is continued before giving up. */
internal const val MAX_CONTINUATIONS = 2

// The reply is not streamed: nothing comes back until the model has finished
// searching and writing, which can take a minute or more. So the read timeout
// is in effect the time allowed for one whole reply. The other two are short,
// so that a phone with no usable connection fails quickly.
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)
private val WRITE_TIMEOUT: Duration = Duration.ofSeconds(15)
private val READ_TIMEOUT: Duration = Duration.ofMinutes(4)

// Four minutes is a long time to wait on a connection that has died, which
// happens when a phone leaves its Wi-Fi. So while it waits, the client asks
// the server this often whether it is still there (an HTTP/2 "ping"). If no
// answer has come by the time the next ping is due, the call fails. A reply
// that is only slow is not affected: the server answers pings while the model
// is still working.
private val PING_INTERVAL: Duration = Duration.ofSeconds(30)

/**
 * The shape the model's final answer must have. The API guarantees a reply
 * that fits a schema sent this way ("structured outputs"), so the answer can
 * be read by code. JSON Schema has no supported way to limit how long a list
 * is, which is why the limits are in the prompt and in cleanUp().
 */
private val TERMS_SCHEMA = Json.parseToJsonElement(
    """
    {
      "type": "object",
      "properties": {
        "strong":  {"type": "array", "items": {"type": "string"}},
        "weak":    {"type": "array", "items": {"type": "string"}},
        "sources": {"type": "array", "items": {"type": "string"}}
      },
      "required": ["strong", "weak", "sources"],
      "additionalProperties": false
    }
    """,
)

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * Turns a typed description into term lists with one call to the Claude API.
 *
 * Besides the key and the fixed prompt, three things leave the phone and
 * nothing else: the description, the breadth and today's date (decision 8 in
 * docs/ARCHITECTURE.md). The date is there so that "this year's race" means
 * the right year.
 *
 * Make one and keep it. Each one has its own network connections.
 *
 * @param apiKey the owner's Anthropic API key. It goes into one request
 *   header. It is never logged and never put into a [Failure] message.
 * @param httpClient leave this out in the app. The default is set up for
 *   this one slow, billed call: long enough timeouts, and no sending a
 *   request twice. A client passed in keeps its own settings.
 * @param baseUrl where the API is. Tests point it at a fake server on the PC.
 * @param clock where today's date comes from. Tests pass a fixed date.
 */
class KeywordExpander(
    apiKey: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    baseUrl: String = "https://api.anthropic.com",
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val apiKey = apiKey.trim()
    private val messagesUrl = baseUrl.trimEnd('/') + "/v1/messages"

    // The API adds fields over time. Ignore the ones this code does not read.
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Asks the model for terms. Expect a wait of a minute or more.
     *
     * Safe to call from the main thread: the work moves to a background
     * thread by itself. Cancelling the coroutine while it waits for the reply
     * abandons the request.
     */
    suspend fun expand(description: String, breadth: Breadth): ExpansionResult {
        if (apiKey.isEmpty()) {
            return Failure(FailureKind.MISSING_API_KEY, "No API key has been set.")
        }
        // A key is printable ASCII with no spaces. OkHttp throws for anything
        // else in a header, and its error message would quote the key, so
        // that case is caught here instead.
        if (apiKey.any { it !in '!'..'~' }) {
            return Failure(
                FailureKind.AUTH,
                "The API key contains a space or another character that keys never have.",
            )
        }
        if (description.isBlank()) {
            return Failure(FailureKind.NOTHING_FOUND, "The description is empty.")
        }

        return withContext(Dispatchers.IO) {
            try {
                requestTerms(description.trim(), breadth)
            } catch (e: InterruptedIOException) {
                // OkHttp's timeouts all arrive as this type or a subclass of it.
                Failure(FailureKind.TIMEOUT, "The Claude API did not answer in time.")
            } catch (e: IOException) {
                val detail = e.message ?: e.javaClass.simpleName
                Failure(FailureKind.NO_NETWORK, "Could not reach the Claude API: $detail")
            }
        }
    }

    private suspend fun requestTerms(description: String, breadth: Breadth): ExpansionResult {
        // Built once and reused, so that a continued turn repeats the first
        // request word for word. The API checks that it does.
        val question = userMessage(description, breadth, LocalDate.now(clock))
        var pausedTurn: JsonArray? = null

        // "return" inside repeat's braces leaves requestTerms, not just the braces.
        repeat(MAX_CONTINUATIONS + 1) {
            val reply = post(requestBody(question, pausedTurn))
            if (reply.status != 200) return httpFailure(reply)

            val message = try {
                json.decodeFromString<ApiMessage>(reply.body)
            } catch (e: IllegalArgumentException) {
                return Failure(FailureKind.BAD_REPLY, "The reply was not a Messages API response.")
            }
            if (message.stopReason != "pause_turn") return readFinishedTurn(message)

            // The searches run in a loop on Anthropic's servers. When a turn
            // needs many steps the API hands it back unfinished ("paused").
            // Sending its content back unchanged makes the API carry on.
            pausedTurn = message.content
        }
        return Failure(
            FailureKind.BAD_REPLY,
            "The model was still searching after $MAX_CONTINUATIONS continuations.",
        )
    }

    /** The JSON body of the request. This is everything that is sent, apart from the headers. */
    private fun requestBody(question: String, pausedTurn: JsonArray?): JsonObject = buildJsonObject {
        put("model", MODEL)
        put("max_tokens", MAX_TOKENS)
        put("system", SYSTEM_PROMPT)
        // The model decides for itself how much to think before it answers.
        putJsonObject("thinking") { put("type", "adaptive") }
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", question)
            }
            if (pausedTurn != null) {
                addJsonObject {
                    put("role", "assistant")
                    put("content", pausedTurn)
                }
            }
        }
        putJsonArray("tools") {
            addJsonObject {
                put("type", WEB_SEARCH_TOOL)
                put("name", "web_search")
                put("max_uses", MAX_WEB_SEARCHES)
                // Left to itself, this version of the tool searches from
                // inside a program that the model writes to filter the
                // results, and the reply then has extra blocks describing
                // that program. "direct" turns the filtering off, so the
                // reply has the plain shape the tests are written against.
                // The price is that the model reads the results unfiltered,
                // which uses more tokens.
                putJsonArray("allowed_callers") { add("direct") }
            }
        }
        putJsonObject("output_config") {
            put("effort", EFFORT)
            putJsonObject("format") {
                put("type", "json_schema")
                put("schema", TERMS_SCHEMA)
            }
        }
        // This model has safety filters that now and then decline a harmless
        // request, for example a drama about hackers. With this set, the API
        // retries such a request on another Claude model instead of failing.
        put("fallbacks", "default")
    }

    private class HttpReply(val status: Int, val body: String)

    private suspend fun post(body: JsonObject): HttpReply {
        val request = Request.Builder()
            .url(messagesUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("anthropic-beta", FALLBACK_BETA)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // executeAsync waits for the reply without holding a thread. If this
        // coroutine is cancelled during that wait, it abandons the request.
        // Once the reply has started to arrive, reading it is not interrupted;
        // by then the whole of it is normally ready, so the reading is quick.
        // use {} closes the connection afterwards.
        return httpClient.newCall(request).executeAsync().use { response ->
            HttpReply(response.code, response.body.string())
        }
    }

    private fun httpFailure(reply: HttpReply): Failure {
        val kind = when (reply.status) {
            401, 403 -> FailureKind.AUTH
            429 -> FailureKind.RATE_LIMITED
            // 529 is the API's own code for "overloaded".
            in 500..599 -> FailureKind.SERVER_ERROR
            else -> FailureKind.HTTP_OTHER
        }
        // The API explains an error as {"error": {"type": ..., "message": ...}}.
        // Something between the phone and the API may answer with a web page
        // instead, so the explanation is optional.
        val explanation = try {
            json.decodeFromString<ApiErrorBody>(reply.body).error?.message
        } catch (e: IllegalArgumentException) {
            null
        }
        val message = if (explanation == null) "HTTP ${reply.status}" else "HTTP ${reply.status}: $explanation"
        return Failure(kind, message)
    }

    /** A reply with HTTP 200 can still be a refusal or half an answer. "stop_reason" says which. */
    private fun readFinishedTurn(message: ApiMessage): ExpansionResult = when (message.stopReason) {
        "end_turn" -> readTerms(message.content)

        "refusal" -> Failure(
            FailureKind.REFUSED,
            message.stopDetails?.explanation ?: "The model declined this description.",
        )

        "max_tokens", "model_context_window_exceeded" -> Failure(
            FailureKind.BAD_REPLY,
            "The reply was cut off before the lists were complete (${message.stopReason}).",
        )

        else -> Failure(
            FailureKind.BAD_REPLY,
            "The reply ended in an unexpected way (${message.stopReason}).",
        )
    }

    private fun readTerms(content: JsonArray): ExpansionResult {
        val reply = try {
            json.decodeFromString<TermsReply>(finalText(content))
        } catch (e: IllegalArgumentException) {
            return Failure(FailureKind.BAD_REPLY, "The reply was not the three lists that were asked for.")
        }

        val terms = cleanUp(ExpandedTerms(reply.strong, reply.weak, reply.sources))
        if (terms.strong.isEmpty() && terms.weak.isEmpty() && terms.sources.isEmpty()) {
            return Failure(FailureKind.NOTHING_FOUND, "The model returned no terms for this description.")
        }
        return Success(terms)
    }

    /**
     * The model's answer: the text that comes after its last search.
     *
     * A reply is a list of blocks. Blocks for thinking, for each search and
     * for each set of search results come first, and the answer is the text
     * at the end. The API may split that text over several blocks, so they are
     * joined. Text before a search is the model saying what it is about to
     * do. It is thrown away when the next block that is not text turns up.
     */
    private fun finalText(content: JsonArray): String {
        val answer = StringBuilder()
        for (block in content) {
            val fields = block as? JsonObject ?: continue
            if (fields.string("type") == "text") {
                answer.append(fields.string("text").orEmpty())
            } else {
                answer.clear()
            }
        }
        return answer.toString()
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun defaultHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT)
        .writeTimeout(WRITE_TIMEOUT)
        .readTimeout(READ_TIMEOUT)
        .pingInterval(PING_INTERVAL)
        // Left on, OkHttp sends a request again by itself when the connection
        // dies during the wait and the host has another address to try. The
        // API has two. Each request is billed and can run for minutes, and
        // the budget is one request per blocker, so a dropped connection is
        // reported and the owner decides whether to try again.
        .retryOnConnectionFailure(false)
        .build()

// The parts of the API's replies that this code reads. Each class matches the
// JSON field for field; @SerialName gives the JSON name where Kotlin's naming
// style differs.

/** A successful reply. [content] is kept as raw JSON so it can be sent back unchanged. */
@Serializable
private class ApiMessage(
    val content: JsonArray = JsonArray(emptyList()),
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_details") val stopDetails: StopDetails? = null,
)

/** Present only when the model declined. */
@Serializable
private class StopDetails(val explanation: String? = null)

/** The body of an HTTP error. */
@Serializable
private class ApiErrorBody(val error: ApiError? = null)

@Serializable
private class ApiError(val message: String? = null)

/** The model's final answer, as laid down by [TERMS_SCHEMA]. */
@Serializable
private class TermsReply(
    val strong: List<String>,
    val weak: List<String>,
    val sources: List<String>,
)
