package ai.koog.http.client.test

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.KoogHttpMultipartPart
import ai.koog.http.client.get
import ai.koog.http.client.lines
import ai.koog.http.client.post
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Abstract test suite for KoogHttpClient implementations.
 * Provides common test scenarios that can be reused across different HTTP client implementations.
 *
 * Subclasses must implement [createClient] to provide the specific client implementation to test.
 */
abstract class BaseKoogHttpClientTest {
    @Serializable
    data class TestRequest(val request: String)

    @Serializable
    data class TestResponse(val response: String)

    protected lateinit var mockServer: MockWebServer

    @BeforeEach
    fun setUp() {
        mockServer = MockWebServer()
    }

    @AfterEach
    fun tearDown() {
        mockServer.stop()
    }

    /**
     * Creates a client instance to be tested.
     * This method will be called for each test case.
     */
    protected abstract fun createClient(): KoogHttpClient

    @Suppress("FunctionName")
    open fun `test return success string response on post`(): Unit = runTest {
        val responseBody = "RESPONSE_OK"

        mockServer.start(
            postEndpoints = listOf(
                MockWebServer.PostEndpointConfig(
                    path = "/echo",
                    responseBody = responseBody,
                    statusCode = HttpStatusCode.OK,
                    contentType = ContentType.Text.Plain
                )
            )
        )

        val client = createClient()

        val result: String = client.post(
            path = mockServer.url("/echo"),
            requestBody = "PAYLOAD"
        )

        assertEquals(responseBody, result)
    }

    @Suppress("FunctionName")
    open fun `test post request headers override inferred string content type`(): Unit = runTest {
        val responseBody = "RESPONSE_OK"

        mockServer.start(
            postEndpoints = listOf(
                MockWebServer.PostEndpointConfig(
                    path = "/echo",
                    responseBody = responseBody,
                    statusCode = HttpStatusCode.OK,
                    contentType = ContentType.Text.Plain,
                    expectedHeaders = mapOf(HttpHeaders.ContentType to ContentType.Application.Json.toString())
                )
            )
        )

        val client = createClient()

        val result: String = client.post(
            path = mockServer.url("/echo"),
            requestBody = "{}",
            headers = mapOf(HttpHeaders.ContentType to ContentType.Application.Json.toString())
        )

        assertEquals(responseBody, result)
    }

    @Suppress("FunctionName")
    open fun `test return success string response on get`(): Unit = runTest {
        val responseBody = "RESPONSE_OK"

        mockServer.start(
            getEndpoints = listOf(
                MockWebServer.GetEndpointConfig(
                    path = "/echo",
                    responseBody = responseBody,
                    statusCode = HttpStatusCode.OK,
                    contentType = ContentType.Text.Plain
                )
            )
        )

        val client = createClient()

        val result: String = client.get(
            path = mockServer.url("/echo")
        )

        assertEquals(responseBody, result)
    }

    @Suppress("FunctionName")
    open fun `test post JSON request and get JSON response`(): Unit = runTest {
        val responseBody = """{"response":"Okay"}"""

        mockServer.start(
            postEndpoints = listOf(
                MockWebServer.PostEndpointConfig(
                    path = "/echo",
                    responseBody = responseBody,
                    statusCode = HttpStatusCode.OK,
                    contentType = ContentType.Application.Json
                )
            )
        )

        val client = createClient()

        val result: TestResponse = client.post(
            path = mockServer.url("/echo"),
            requestBody = TestRequest("How are you?"),
            requestBodyType = TestRequest::class,
            responseType = TestResponse::class
        )

        assertEquals("Okay", result.response)
    }

    @Suppress("FunctionName")
    open fun `test handle on non-success status`(): Unit = runTest {
        mockServer.start(
            postEndpoints = listOf(
                MockWebServer.PostEndpointConfig(
                    path = "/fail",
                    responseBody = "Bad things",
                    statusCode = HttpStatusCode.BadRequest,
                    contentType = ContentType.Text.Plain
                )
            )
        )

        val client = createClient()

        try {
            client.post<String, String>(
                path = mockServer.url("/fail"),
                requestBody = "PAYLOAD",
            )
            fail("Expected an exception for non-success status")
        } catch (e: KoogHttpClientException) {
            assertEquals(e.clientName, "TestClient")
            assertEquals(e.statusCode, 400)
        }
    }

    @Suppress("FunctionName")
    open fun `test get SSE flow and collect events`(): Unit = runTest {
        val events = listOf("event1", "event2", "event3")

        mockServer.start(
            sseEndpoints = listOf(
                MockWebServer.SSEEndpointConfig(
                    path = "/stream",
                    events = events
                )
            )
        )

        val client = createClient()

        val flow = client.sse(
            path = mockServer.url("/stream"),
            requestBody = "{}",
            requestBodyType = String::class,
            dataFilter = { it != "[DONE]" },
            decodeStreamingResponse = { it },
            processStreamingChunk = { it }
        )

        val collected = flow.toList()

        assertEquals(events.size, collected.size)
        assertEquals(events, collected)
    }

    @Suppress("FunctionName")
    open fun `test filter SSE events`(): Unit = runTest {
        val events = listOf("event1", "[DONE]", "event2", "[DONE]", "event3")

        mockServer.start(
            sseEndpoints = listOf(
                MockWebServer.SSEEndpointConfig(
                    path = "/stream",
                    events = events
                )
            )
        )

        val client = createClient()

        val flow = client.sse(
            path = mockServer.url("/stream"),
            requestBody = "{}",
            requestBodyType = String::class,
            dataFilter = { it != "[DONE]" },
            decodeStreamingResponse = { it },
            processStreamingChunk = { it }
        )

        val collected = flow.toList()

        // Only non-[DONE] events should be collected
        assertEquals(3, collected.size)
        assertEquals(listOf("event1", "event2", "event3"), collected)
    }

    @Suppress("FunctionName")
    open fun `test return success string response on get with parameters`(): Unit = runTest {
        val responseBody = "RESPONSE_OK_WITH_PARAMS"
        val expectedParameters = mapOf("param1" to "value1", "param2" to "value2")

        mockServer.start(
            getEndpoints = listOf(
                MockWebServer.GetEndpointConfig(
                    path = "/echo",
                    responseBody = responseBody,
                    statusCode = HttpStatusCode.OK,
                    contentType = ContentType.Text.Plain,
                    expectedParameters = expectedParameters
                )
            )
        )

        val client = createClient()

        val result: String = client.get(
            path = mockServer.url("/echo"),
            parameters = expectedParameters
        )

        assertEquals(responseBody, result)
    }

    @Suppress("FunctionName")
    open fun `test lines emits non-blank lines`(): Unit = runTest {
        val lines = listOf("""{"i":1}""", """{"i":2}""", """{"i":3}""")

        mockServer.start(
            linesEndpoints = listOf(
                MockWebServer.LinesEndpointConfig(
                    path = "/stream",
                    lines = lines
                )
            )
        )

        val client = createClient()

        val collected = client.lines(
            path = mockServer.url("/stream"),
            requestBody = "{}"
        ).toList()

        assertEquals(lines, collected)
    }

    @Suppress("FunctionName")
    open fun `test lines request headers override inferred string content type`(): Unit = runTest {
        val lines = listOf("""{"i":1}""")

        mockServer.start(
            linesEndpoints = listOf(
                MockWebServer.LinesEndpointConfig(
                    path = "/stream",
                    lines = lines,
                    expectedHeaders = mapOf(HttpHeaders.ContentType to ContentType.Application.Json.toString())
                )
            )
        )

        val client = createClient()

        val collected = client.lines(
            path = mockServer.url("/stream"),
            requestBody = "{}",
            headers = mapOf(HttpHeaders.ContentType to ContentType.Application.Json.toString())
        ).toList()

        assertEquals(lines, collected)
    }

    @Suppress("FunctionName")
    open fun `test lines skips blank lines`(): Unit = runTest {
        val lines = listOf("""{"i":1}""", "", "   ", """{"i":2}""")

        mockServer.start(
            linesEndpoints = listOf(
                MockWebServer.LinesEndpointConfig(
                    path = "/stream",
                    lines = lines
                )
            )
        )

        val client = createClient()

        val collected = client.lines(
            path = mockServer.url("/stream"),
            requestBody = "{}"
        ).toList()

        assertEquals(listOf("""{"i":1}""", """{"i":2}"""), collected)
    }

    @Suppress("FunctionName")
    open fun `test lines emits nothing for empty body`(): Unit = runTest {
        mockServer.start(
            linesEndpoints = listOf(
                MockWebServer.LinesEndpointConfig(
                    path = "/stream",
                    lines = emptyList()
                )
            )
        )

        val client = createClient()

        val collected = client.lines(
            path = mockServer.url("/stream"),
            requestBody = "{}"
        ).toList()

        assertTrue(collected.isEmpty())
    }

    @Suppress("FunctionName")
    open fun `test lines surfaces non-2xx as KoogHttpClientException`(): Unit = runTest {
        mockServer.start(
            linesEndpoints = listOf(
                MockWebServer.LinesEndpointConfig(
                    path = "/stream",
                    lines = listOf("ignored"),
                    statusCode = HttpStatusCode.BadRequest,
                    contentType = ContentType.Text.Plain
                )
            )
        )

        val client = createClient()

        val failure = assertThrows<KoogHttpClientException> {
            client.lines(
                path = mockServer.url("/stream"),
                requestBody = "{}"
            ).toList()
        }
        assertEquals(client.clientName, failure.clientName)
        assertEquals(400, failure.statusCode)
    }

    @Suppress("FunctionName")
    open fun `test lines propagates cancellation`(): Unit = runTest {
        // Given: server that emits up to 1000 lines
        val totalLines = 1_000
        val lines = List(totalLines) { """{"i":$it}""" }
        val writtenLines = AtomicInteger(0)
        val streamClosed = CompletableDeferred<Unit>()

        mockServer.start(
            linesEndpoints = listOf(
                MockWebServer.LinesEndpointConfig(
                    path = "/stream",
                    lines = lines,
                    lineDelayMillis = 20,
                    onLineWritten = { writtenLines.incrementAndGet() },
                    onStreamClosed = { streamClosed.complete(Unit) }
                )
            )
        )

        // And: client
        val client = createClient()

        // When: client collects only the first 3 lines, causing upstream cancellation
        val collected = client.lines(
            path = mockServer.url("/stream"),
            requestBody = "{}"
        ).take(3).toList()

        // Then: client received correct lines
        assertEquals(3, collected.size)
        assertEquals(lines.take(3), collected)

        // And: server observes stream closure before writing all lines
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(2_000) {
                streamClosed.await()
            }
        }
        assertTrue(writtenLines.get() < totalLines)
    }

    @Suppress("FunctionName")
    open fun `test return success string response on post with parameters`(): Unit = runTest {
        val responseBody = "RESPONSE_OK_WITH_PARAMS"
        val expectedParameters = mapOf("filter" to "active", "sort" to "desc")

        mockServer.start(
            postEndpoints = listOf(
                MockWebServer.PostEndpointConfig(
                    path = "/echo",
                    responseBody = responseBody,
                    statusCode = HttpStatusCode.OK,
                    contentType = ContentType.Text.Plain,
                    expectedParameters = expectedParameters
                )
            )
        )

        val client = createClient()

        val result: String = client.post(
            path = mockServer.url("/echo"),
            requestBody = "PAYLOAD",
            parameters = expectedParameters
        )

        assertEquals(responseBody, result)
    }

    @Suppress("FunctionName")
    open fun `test raw bytes preserve zeros status headers and request id`(): Unit = runTest {
        val bytes = byteArrayOf(0, 1, 0, -1)
        mockServer.start(
            rawEndpoints = listOf(
                MockWebServer.RawEndpointConfig(
                    path = "/binary",
                    method = io.ktor.http.HttpMethod.Get,
                    responseBody = bytes,
                    responseHeaders = mapOf("x-request-id" to "req-123", "x-provider" to "fixture"),
                )
            )
        )

        val response = createClient().getBytes(mockServer.url("/binary"))

        assertContentEquals(bytes, response.body)
        assertEquals(200, response.statusCode)
        assertEquals("req-123", response.requestId)
        assertEquals("fixture", response.headers["x-provider"]?.single())
    }

    @Suppress("FunctionName")
    open fun `test multipart preserves text filename media type and binary zeros`(): Unit = runTest {
        val bytes = byteArrayOf(0, 65, 0, -1)
        var textPart: Pair<String?, String>? = null
        var filePart: List<Any?>? = null
        mockServer.start(
            multipartEndpoints = listOf(
                MockWebServer.MultipartEndpointConfig(
                    path = "/upload",
                    responseBody = "{}".encodeToByteArray(),
                    onTextPart = { name, value -> textPart = name to value },
                    onFilePart = { name, filename, mediaType, content ->
                        filePart = listOf(name, filename, mediaType, content)
                    },
                )
            )
        )

        createClient().postMultipart(
            mockServer.url("/upload"),
            listOf(
                KoogHttpMultipartPart.Text("purpose", "assistants"),
                KoogHttpMultipartPart.File("file", "zero.bin", "application/octet-stream", bytes),
            ),
        )

        assertEquals("purpose" to "assistants", textPart)
        assertEquals("file", filePart?.get(0))
        assertEquals("zero.bin", filePart?.get(1))
        assertEquals("application/octet-stream", filePart?.get(2))
        assertContentEquals(bytes, filePart?.get(3) as ByteArray)
    }

    @Suppress("FunctionName")
    open fun `test delete and structured failures preserve response metadata`(): Unit = runTest {
        mockServer.start(
            rawEndpoints = listOf(
                MockWebServer.RawEndpointConfig(
                    path = "/deleted",
                    method = io.ktor.http.HttpMethod.Delete,
                    statusCode = HttpStatusCode.NoContent,
                ),
                MockWebServer.RawEndpointConfig(
                    path = "/missing",
                    method = io.ktor.http.HttpMethod.Get,
                    responseBody = "{\"error\":{\"message\":\"missing\",\"code\":\"not_found\"}}".encodeToByteArray(),
                    statusCode = HttpStatusCode.NotFound,
                    contentType = ContentType.Application.Json,
                    responseHeaders = mapOf("apim-request-id" to "azure-404"),
                ),
                MockWebServer.RawEndpointConfig(
                    path = "/conflict",
                    method = io.ktor.http.HttpMethod.Get,
                    responseBody = "{\"error\":{\"message\":\"busy\",\"code\":\"conflict\"}}".encodeToByteArray(),
                    statusCode = HttpStatusCode.Conflict,
                    contentType = ContentType.Application.Json,
                ),
            )
        )
        val client = createClient()

        val deleted = client.delete(mockServer.url("/deleted"))
        assertEquals(204, deleted.statusCode)
        assertContentEquals(byteArrayOf(), deleted.body)

        val missing = assertFailsWith<KoogHttpClientException> { client.getBytes(mockServer.url("/missing")) }
        assertEquals(404, missing.statusCode)
        assertEquals("azure-404", missing.requestId)
        assertTrue(missing.errorBody.orEmpty().contains("not_found"))

        val conflict = assertFailsWith<KoogHttpClientException> { client.getBytes(mockServer.url("/conflict")) }
        assertEquals(409, conflict.statusCode)
        assertTrue(conflict.errorBody.orEmpty().contains("conflict"))
    }

    @Suppress("FunctionName")
    open fun `test raw request propagates cancellation`(): Unit = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        mockServer.start(
            rawEndpoints = listOf(
                MockWebServer.RawEndpointConfig(
                    path = "/slow",
                    method = io.ktor.http.HttpMethod.Get,
                    responseDelayMillis = 10_000,
                    onRequest = { requestStarted.complete(Unit) },
                )
            )
        )
        val request = async { createClient().getBytes(mockServer.url("/slow")) }

        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(2_000) {
                requestStarted.await()
            }
        }
        assertTrue(request.isActive)
        request.cancel()

        assertFailsWith<CancellationException> { request.await() }
    }
}
