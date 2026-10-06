package io.github.aaroncchung.spoilerblocker.expansion

import io.github.aaroncchung.spoilerblocker.expansion.ExpansionResult.Failure
import io.github.aaroncchung.spoilerblocker.expansion.ExpansionResult.Success
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The tests talk to MockWebServer, a fake server on this PC that replays
 * replies written by hand below. Nothing here reaches the real API.
 */
class KeywordExpanderTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val fifthOfOctober = Clock.fixed(Instant.parse("2026-10-05T09:00:00Z"), ZoneOffset.UTC)

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun expander(apiKey: String = API_KEY, httpClient: OkHttpClient = client) =
        KeywordExpander(apiKey, httpClient, server.url("/").toString(), fifthOfOctober)

    private fun expand(
        description: String = "2026 Japanese Grand Prix",
        breadth: Breadth = Breadth.NARROW,
        expander: KeywordExpander = expander(),
    ): ExpansionResult = runBlocking { expander.expand(description, breadth) }

    /** Queues one reply for the fake server to give. */
    private fun reply(body: String, status: Int = 200) {
        server.enqueue(
            MockResponse.Builder()
                .code(status)
                .setHeader("content-type", "application/json")
                .body(body)
                .build(),
        )
    }

    /** The JSON body of the next request the fake server received. */
    private fun sentBody(): JsonObject = parse(server.takeRequest().body!!.utf8()).jsonObject

    private fun assertFailure(expected: FailureKind, result: ExpansionResult): Failure {
        assertTrue("Expected a failure but got $result", result is Failure)
        result as Failure
        assertEquals(result.message, expected, result.kind)
        assertFalse("A message must never contain the key", result.message.contains(API_KEY))
        return result
    }

    // ---- The request ----

    @Test
    fun `posts to the messages endpoint with the documented headers`() {
        reply(message(text(SUZUKA_TERMS)))

        expand()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/messages", request.target)
        assertEquals(API_KEY, request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertEquals("server-side-fallback-2026-07-01", request.headers["anthropic-beta"])
        assertEquals("application/json; charset=utf-8", request.headers["content-type"])
    }

    @Test
    fun `sends no header that describes the phone or the app`() {
        reply(message(text(SUZUKA_TERMS)))

        expand()

        val request = server.takeRequest()
        val ours = setOf("x-api-key", "anthropic-version", "anthropic-beta", "content-type")
        // OkHttp adds these to every request. Its User-Agent is only its own name and version.
        val fromOkHttp = setOf("content-length", "host", "connection", "accept-encoding", "user-agent")
        assertEquals(ours + fromOkHttp, request.headers.names().map { it.lowercase() }.toSet())
        assertTrue(request.headers["user-agent"]!!.startsWith("okhttp/"))
    }

    @Test
    fun `asks for the model, web search and an answer in JSON`() {
        reply(message(text(SUZUKA_TERMS)))

        expand()

        // The values here are spelled out on purpose and not taken from the
        // constants in KeywordExpander.kt. A typing mistake in a constant
        // then fails this test. Change both together.
        val body = sentBody()
        assertEquals(
            setOf("model", "max_tokens", "system", "thinking", "messages", "tools", "output_config", "fallbacks"),
            body.keys,
        )
        assertEquals(JsonPrimitive("claude-opus-5-5"), body["model"])
        assertEquals(JsonPrimitive(16000), body["max_tokens"])
        assertEquals(JsonPrimitive(SYSTEM_PROMPT), body["system"])
        assertEquals(parse("""{"type": "adaptive"}"""), body["thinking"])
        assertEquals(
            parse(
                """
                [{
                  "type": "web_search_20260209",
                  "name": "web_search",
                  "max_uses": 8,
                  "allowed_callers": ["direct"]
                }]
                """,
            ),
            body["tools"],
        )
        assertEquals(
            parse(
                """
                {
                  "effort": "medium",
                  "format": {
                    "type": "json_schema",
                    "schema": {
                      "type": "object",
                      "properties": {
                        "strong": {"type": "array", "items": {"type": "string"}},
                        "weak": {"type": "array", "items": {"type": "string"}},
                        "sources": {"type": "array", "items": {"type": "string"}}
                      },
                      "required": ["strong", "weak", "sources"],
                      "additionalProperties": false
                    }
                  }
                }
                """,
            ),
            body["output_config"],
        )
        assertEquals(JsonPrimitive("default"), body["fallbacks"])
    }

    @Test
    fun `the only message holds the date, the breadth and the description`() {
        reply(message(text(SUZUKA_TERMS)))

        expand(description = "  2026 Japanese Grand Prix ", breadth = Breadth.NARROW)

        val expected = """
            [{
              "role": "user",
              "content": "Today's date: 2026-10-05\nBreadth: narrow\nDescription: 2026 Japanese Grand Prix"
            }]
        """
        assertEquals(parse(expected), sentBody()["messages"])
    }

    @Test
    fun `a broad blocker is sent as broad`() {
        reply(message(text(SUZUKA_TERMS)))

        expand(description = "Formula 1", breadth = Breadth.BROAD)

        val content = sentBody()["messages"]!!.jsonArray[0].jsonObject["content"]
        assertEquals(JsonPrimitive("Today's date: 2026-10-05\nBreadth: broad\nDescription: Formula 1"), content)
    }

    @Test
    fun `the key travels in its header and nowhere else`() {
        reply(message(text(SUZUKA_TERMS)))

        expand()

        val request = server.takeRequest()
        assertFalse(request.body!!.utf8().contains(API_KEY))
        assertFalse(request.url.toString().contains(API_KEY))
        val headersWithKey = request.headers.filter { (_, value) -> value.contains(API_KEY) }.map { it.first }
        assertEquals(listOf("x-api-key"), headersWithKey)
    }

    // ---- Replies that succeed ----

    @Test
    fun `reads the lists that come after the web search blocks`() {
        reply(REPLY_WITH_WEB_SEARCH)

        val result = expand()

        val expected = ExpandedTerms(
            strong = listOf("Japanese Grand Prix", "Suzuka", "#JapaneseGP"),
            weak = listOf("Max", "podium", "P1"),
            sources = listOf("FORMULA 1", "Sky Sports F1"),
        )
        assertEquals(Success(expected), result)
    }

    @Test
    fun `joins an answer that arrives as several text blocks`() {
        // Text that cites a search result comes back in pieces, one block per piece.
        val firstHalf = """{"type": "text", "text": "{\"strong\": [\"Suzuka\"], \"weak\": [\"po"}"""
        val secondHalf = """
            {
              "type": "text",
              "text": "dium\"], \"sources\": []}",
              "citations": [{
                "type": "web_search_result_location",
                "url": "https://example.com/japanese-gp",
                "title": "Japanese Grand Prix",
                "encrypted_index": "Eo8BCioIAhgBIiQyYjQ0OWJmZi1lNm",
                "cited_text": "The race is held at Suzuka."
              }]
            }
        """
        reply(message("$WEB_SEARCH_BLOCKS, $firstHalf, $secondHalf"))

        val result = expand()

        assertEquals(Success(ExpandedTerms(listOf("Suzuka"), listOf("podium"), emptyList())), result)
    }

    @Test
    fun `a search that failed does not fail the expansion`() {
        // The API reports a failed search inside a normal reply, and the model carries on.
        val failedSearch = """
            {
              "type": "server_tool_use",
              "id": "srvtoolu_09",
              "name": "web_search",
              "input": {"query": "Japanese Grand Prix hashtags"}
            },
            {
              "type": "web_search_tool_result",
              "tool_use_id": "srvtoolu_09",
              "content": {"type": "web_search_tool_result_error", "error_code": "max_uses_exceeded"}
            }
        """
        reply(message("$failedSearch, ${text(SUZUKA_TERMS)}"))

        assertTrue(expand() is Success)
    }

    @Test
    fun `reads an answer that a fallback model wrote`() {
        val handOver = """
            {"type": "fallback", "from": {"model": "claude-opus-5-5"}, "to": {"model": "claude-opus-4-8"}}
        """
        reply(message("$handOver, ${text(SUZUKA_TERMS)}"))

        assertTrue(expand() is Success)
    }

    @Test
    fun `cleans up the lists before returning them`() {
        val messy = """{"strong": [" Suzuka ", "suzuka", ""], "weak": ["SUZUKA", "podium"], "sources": ["f1", "F1"]}"""
        reply(message(text(messy)))

        val result = expand()

        assertEquals(Success(ExpandedTerms(listOf("Suzuka"), listOf("podium"), listOf("f1"))), result)
    }

    // ---- A turn that the API pauses ----

    @Test
    fun `continues a paused turn by sending its content back unchanged`() {
        reply(message(PAUSED_CONTENT, stopReason = "pause_turn"))
        reply(message(text(SUZUKA_TERMS)))

        val result = expand()

        assertTrue(result is Success)
        val first = sentBody()
        val second = sentBody()
        val firstMessages = first["messages"]!!.jsonArray
        val secondMessages = second["messages"]!!.jsonArray
        assertEquals(1, firstMessages.size)
        assertEquals(2, secondMessages.size)
        assertEquals(firstMessages[0], secondMessages[0])
        assertEquals(parse("""{"role": "assistant", "content": [$PAUSED_CONTENT]}"""), secondMessages[1])
        // Everything else has to be the same as before, the tools above all.
        assertEquals(first - "messages", second - "messages")
    }

    @Test
    fun `gives up when the turn keeps pausing`() {
        repeat(MAX_CONTINUATIONS + 1) { reply(message(PAUSED_CONTENT, stopReason = "pause_turn")) }

        val result = expand()

        assertFailure(FailureKind.BAD_REPLY, result)
        assertEquals(MAX_CONTINUATIONS + 1, server.requestCount)
        // The last request was still a proper continuation of the same turn.
        val bodies = List(server.requestCount) { sentBody() }
        val lastMessages = bodies.last()["messages"]!!.jsonArray
        assertEquals(2, lastMessages.size)
        assertEquals(bodies.first()["messages"]!!.jsonArray[0], lastMessages[0])
        assertEquals(parse("""{"role": "assistant", "content": [$PAUSED_CONTENT]}"""), lastMessages[1])
        assertEquals(bodies.first() - "messages", bodies.last() - "messages")
    }

    // ---- Failures before anything is sent ----

    @Test
    fun `a blank key fails without a request`() {
        val result = expand(expander = expander(apiKey = "  "))

        assertFailure(FailureKind.MISSING_API_KEY, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a key with a line break in it fails without a request and is not quoted`() {
        val brokenKey = "sk-ant-first\nsecond"

        val result = expand(expander = expander(apiKey = brokenKey))

        val failure = assertFailure(FailureKind.AUTH, result)
        assertFalse(failure.message.contains("sk-ant-first"))
        assertFalse(failure.message.contains("second"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a blank description fails without a request`() {
        val result = expand(description = "   ")

        assertFailure(FailureKind.NOTHING_FOUND, result)
        assertEquals(0, server.requestCount)
    }

    // ---- Failures on the way ----

    @Test
    fun `no connection is a network failure`() {
        val expander = expander()
        server.close()

        val result = expand(expander = expander)

        assertFailure(FailureKind.NO_NETWORK, result)
    }

    @Test
    fun `a server that never answers is a timeout`() {
        val impatient = OkHttpClient.Builder().readTimeout(Duration.ofMillis(300)).build()
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())

        val result = expand(expander = expander(httpClient = impatient))

        assertFailure(FailureKind.TIMEOUT, result)
    }

    @Test
    fun `a dropped connection is not followed by a second request`() {
        // When a connection dies and the host has another address to try,
        // OkHttp by default sends the request again by itself. The real API
        // has two addresses, and every request is billed. A made-up host name
        // with two addresses stands in for it here.
        val twoAddresses = Dns { List(2) { server.socketAddress.address } }
        val client = defaultHttpClient().newBuilder().dns(twoAddresses).build()
        val expander = KeywordExpander(API_KEY, client, "http://two-addresses.test:${server.port}", fifthOfOctober)
        // The server reads the request, then closes the connection without answering.
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        reply(message(text(SUZUKA_TERMS)))

        val result = expand(expander = expander)

        assertFailure(FailureKind.NO_NETWORK, result)
        assertEquals(1, server.requestCount)
    }

    /**
     * The default client, changed for the two tests about pings. Pings exist
     * only in HTTP/2, which the real API speaks, so these tests speak it too.
     * The ping interval is 30 seconds in the app; here it is half a second,
     * to keep the tests quick.
     */
    private fun pingingClient(): OkHttpClient =
        defaultHttpClient().newBuilder()
            .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
            .pingInterval(Duration.ofMillis(500))
            .readTimeout(Duration.ofSeconds(5))
            .build()

    @Test
    fun `pings do not disturb a reply that is slow but on its way`() {
        MockWebServer().use { http2Server ->
            http2Server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
            http2Server.start()
            // The reply takes as long as four pings.
            http2Server.enqueue(
                MockResponse.Builder()
                    .headersDelay(2, TimeUnit.SECONDS)
                    .body(message(text(SUZUKA_TERMS)))
                    .build(),
            )
            val url = http2Server.url("/").toString()

            val result = expand(expander = KeywordExpander(API_KEY, pingingClient(), url, fifthOfOctober))

            assertTrue("Expected the lists but got $result", result is Success)
        }
    }

    @Test
    fun `pings notice a dead connection long before the timeout would`() {
        // A socket that takes the connection and then never reads or answers,
        // as when a phone leaves its Wi-Fi in the middle of the wait.
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { deadEnd ->
            val url = "http://${deadEnd.inetAddress.hostAddress}:${deadEnd.localPort}"

            val result = expand(expander = KeywordExpander(API_KEY, pingingClient(), url, fifthOfOctober))

            // Without pings this would be a TIMEOUT, after five seconds.
            assertFailure(FailureKind.NO_NETWORK, result)
        }
    }

    @Test
    fun `cancelling while waiting for the reply abandons the request`() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        // Run the call on another thread, so that this one is free to cancel it.
        val call = async(Dispatchers.Default) { expander().expand("2026 Japanese Grand Prix", Breadth.NARROW) }
        // Wait until the request has reached the server and is in flight.
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

        // If cancelling did nothing, this would wait for OkHttp's ten second
        // read timeout and withTimeout would fail the test.
        withTimeout(5_000) { call.cancelAndJoin() }

        assertTrue(call.isCancelled)
    }

    // ---- HTTP errors ----

    @Test
    fun `each HTTP error becomes its own kind of failure and carries the API's explanation`() {
        val expected = listOf(
            401 to FailureKind.AUTH,
            403 to FailureKind.AUTH,
            429 to FailureKind.RATE_LIMITED,
            529 to FailureKind.SERVER_ERROR,
            500 to FailureKind.SERVER_ERROR,
            504 to FailureKind.SERVER_ERROR,
            400 to FailureKind.HTTP_OTHER,
            402 to FailureKind.HTTP_OTHER,
            404 to FailureKind.HTTP_OTHER,
        )
        for ((status, kind) in expected) {
            reply(ERROR_BODY, status)

            val failure = assertFailure(kind, expand())

            assertEquals("HTTP $status: Something went wrong.", failure.message)
        }
    }

    @Test
    fun `an error page that is not JSON still gives a failure`() {
        reply("<html><body>502 Bad Gateway</body></html>", status = 502)

        val failure = assertFailure(FailureKind.SERVER_ERROR, expand())

        assertEquals("HTTP 502", failure.message)
    }

    // ---- Replies with HTTP 200 that are not an answer ----

    @Test
    fun `a refusal is reported with the API's explanation`() {
        val details = """
            {
              "type": "refusal",
              "category": "cyber",
              "explanation": "This request was declined because it could enable cyber harm."
            }
        """
        reply(message(content = "", stopReason = "refusal", stopDetails = details))

        val failure = assertFailure(FailureKind.REFUSED, expand())

        assertEquals("This request was declined because it could enable cyber harm.", failure.message)
    }

    @Test
    fun `a refusal without an explanation is still a refusal`() {
        val details = """{"type": "refusal", "category": null, "explanation": null}"""
        reply(message(content = "", stopReason = "refusal", stopDetails = details))

        assertFailure(FailureKind.REFUSED, expand())
    }

    @Test
    fun `a reply that is cut off or is not the three lists is a bad reply`() {
        val halfAnAnswer = """{"type": "text", "text": "{\"strong\": [\"Suzuka\", \"Japa"}"""
        val replies = listOf(
            // The model ran out of room part of the way through.
            message(halfAnAnswer, stopReason = "max_tokens"),
            message(halfAnAnswer, stopReason = "model_context_window_exceeded"),
            // Prose where the JSON should be.
            message(text("Here are some terms for the Japanese Grand Prix: Suzuka, podium.")),
            // JSON with one of the lists missing.
            message(text("""{"strong": ["Suzuka"], "weak": ["podium"]}""")),
            // No text after the search.
            message(WEB_SEARCH_BLOCKS),
            // A stop reason this code does not know. The lists are not trusted then.
            message(text(SUZUKA_TERMS), stopReason = "tool_use"),
            // Not a Messages API reply at all.
            "this is not JSON",
        )
        for (body in replies) {
            reply(body)

            assertFailure(FailureKind.BAD_REPLY, expand())
        }
    }

    @Test
    fun `three empty lists mean nothing was found`() {
        reply(message(text("""{"strong": [], "weak": [" "], "sources": []}""")))

        assertFailure(FailureKind.NOTHING_FOUND, expand())
    }
}

// ---- Replies written by hand, in the shapes Anthropic documents ----

/** Not a real key. */
private const val API_KEY = "sk-ant-test-0123456789"

private const val SUZUKA_TERMS = """{"strong": ["Suzuka"], "weak": ["podium"], "sources": ["FORMULA 1"]}"""

private fun parse(json: String) = Json.parseToJsonElement(json)

/** A successful reply. [content] is the blocks that go inside its "content" list. */
private fun message(content: String, stopReason: String = "end_turn", stopDetails: String = "null") = """
    {
      "id": "msg_01XFUDYJgAACzvnptvVoYEL",
      "type": "message",
      "role": "assistant",
      "model": "claude-opus-5-5",
      "content": [$content],
      "stop_reason": "$stopReason",
      "stop_sequence": null,
      "stop_details": $stopDetails,
      "usage": {"input_tokens": 6039, "output_tokens": 931, "server_tool_use": {"web_search_requests": 1}}
    }
"""

/** A text block. JsonPrimitive adds the quotes and escapes the quotes inside. */
private fun text(text: String) = """{"type": "text", "text": ${JsonPrimitive(text)}}"""

/** An error reply, as sent with an HTTP status of 400 or above. */
private const val ERROR_BODY = """
    {
      "type": "error",
      "error": {"type": "invalid_request_error", "message": "Something went wrong."},
      "request_id": "req_011CSHoEeqs5C35K2UUqR7Fy"
    }
"""

/** The model thinks, searches once and gets one result back. */
private const val WEB_SEARCH_BLOCKS = """
    {"type": "thinking", "thinking": "", "signature": "EqQBCkYIBxgCKkBtZXNzYWdl"},
    {
      "type": "server_tool_use",
      "id": "srvtoolu_01WYG3ziw53XMcoyKL4XcZmE",
      "name": "web_search",
      "input": {"query": "2026 Japanese Grand Prix entry list"}
    },
    {
      "type": "web_search_tool_result",
      "tool_use_id": "srvtoolu_01WYG3ziw53XMcoyKL4XcZmE",
      "content": [{
        "type": "web_search_result",
        "url": "https://example.com/japanese-gp",
        "title": "Japanese Grand Prix",
        "encrypted_content": "EqgfCioIARgBIiQ3YTAwMjY1Mi1mZjM5LTQ1NGUtODgxNC1kNjNjNTk1ZWI3Y",
        "page_age": "March 29, 2026"
      }]
    }
"""

/** A whole reply: the model says what it will do, searches, then answers. */
private val REPLY_WITH_WEB_SEARCH = message(
    """
    {"type": "text", "text": "I'll check this season's entry list."},
    $WEB_SEARCH_BLOCKS,
    {
      "type": "text",
      "text": "{\"strong\": [\"Japanese Grand Prix\", \"Suzuka\", \"#JapaneseGP\"], \"weak\": [\"Max\", \"podium\", \"P1\"], \"sources\": [\"FORMULA 1\", \"Sky Sports F1\"]}"
    }
    """,
)

/** A paused turn ends with a search that has not been run yet. */
private const val PAUSED_CONTENT = """
    {"type": "thinking", "thinking": "", "signature": "EqQBCkYIBxgCKkBtZXNzYWdl"},
    {
      "type": "server_tool_use",
      "id": "srvtoolu_01A2B3C4D5E6F7G8H9",
      "name": "web_search",
      "input": {"query": "Suzuka 2026 race weekend"}
    }
"""
