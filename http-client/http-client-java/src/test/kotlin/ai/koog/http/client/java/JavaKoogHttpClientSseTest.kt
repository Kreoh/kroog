package ai.koog.http.client.java

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.test.MockWebServer
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JavaKoogHttpClientSseTest {
    @Test
    fun testSseFramesOnlyDispatchCompleteDataAndPreserveWhitespace() = runTest {
        val response = "\uFEFF: heartbeat\r\nevent: image_generation.completed\r\nid: 7\r\nretry: 100\r\n" +
            "future: ignored\r\nraw text is not data\r\ndata:first\r\ndata:  second  \r\n\r\n" +
            "event: ignored\n\ndata:\n\ndata\n\ndata: café\r\rdata: incomplete\n"
        withResponse(response) { client ->
            assertEquals(listOf("first\n second  ", "", "", "café"), client.stream().toList())
        }
    }

    @Test
    fun testSseFiltersCompleteEventsAndOmitsNullProcessedChunks() = runTest {
        withResponse("data: first\ndata: second\n\ndata: skip\n\ndata: keep\n\n") { client ->
            val filtered = mutableListOf<String?>()
            val result = client.sse(
                path = "/stream",
                requestBody = "{}",
                requestBodyType = String::class,
                dataFilter = { data ->
                    filtered.add(data)
                    data != "skip"
                },
                decodeStreamingResponse = { it.uppercase() },
                processStreamingChunk = { it.takeUnless { it == "FIRST\nSECOND" } },
            ).toList()
            assertEquals(listOf<String?>("first\nsecond", "skip", "keep"), filtered)
            assertEquals(listOf("KEEP"), result)
        }
    }

    @Test
    fun testSseDoesNotDropEventsWithRendezvousBackpressure() = runTest {
        val expected = (0..199).map(Int::toString)
        withResponse(expected.joinToString("") { "data: $it\n\n" }) { client ->
            assertEquals(expected, client.stream().buffer(0).toList())
        }
    }

    @Test
    fun testSseHttpFailureRetainsProviderDiagnostics() = runTest {
        val body = """{"error":{"message":"quota","code":"quota"}}"""
        withResponse(body, HttpStatusCode.TooManyRequests) { client ->
            val error = assertFailsWith<KoogHttpClientException> { client.stream().toList() }
            assertEquals(429, error.statusCode)
            assertEquals(body, error.errorBody)
            assertEquals("req_sse", error.requestId)
            assertEquals(listOf("req_sse"), error.responseHeaders["x-request-id"])
        }
    }

    @Test
    fun testSseDecodeFailureTerminatesCollection() = runTest {
        withResponse("data: bad\n\ndata: later\n\n") { client ->
            val cause = IllegalArgumentException("bad event")
            var calls = 0
            val error = assertFailsWith<KoogHttpClientException> {
                client.sse(
                    path = "/stream",
                    requestBody = "{}",
                    requestBodyType = String::class,
                    decodeStreamingResponse = {
                        calls++
                        throw cause
                    },
                    processStreamingChunk = { value: String -> value },
                ).toList()
            }
            assertEquals(1, calls)
            assertSame(cause, error.cause)
        }
    }

    @Test
    fun testCancellationFromAnySseCallbackReachesTheCollector() = runTest {
        withContext(Dispatchers.Default) {
            for (stage in listOf("filter", "decoder", "processor")) {
                withResponse("data: first\n\n") { client ->
                    val cancellation = CancellationException("$stage cancelled")
                    val callbackEntered = CompletableDeferred<Unit>()
                    fun cancelFromCallback() {
                        callbackEntered.complete(Unit)
                        throw cancellation
                    }
                    val collection = async(Dispatchers.IO) {
                        assertFailsWith<CancellationException> {
                            client.sse(
                                path = "/stream",
                                requestBody = "{}",
                                requestBodyType = String::class,
                                dataFilter = {
                                    if (stage == "filter") cancelFromCallback()
                                    true
                                },
                                decodeStreamingResponse = {
                                    if (stage == "decoder") cancelFromCallback()
                                    it
                                },
                                processStreamingChunk = {
                                    if (stage == "processor") cancelFromCallback()
                                    it
                                },
                            ).toList()
                        }
                    }
                    try {
                        withTimeout(5_000) { callbackEntered.await() }
                        val error = withTimeout(2_000) { collection.await() }
                        assertEquals(cancellation.message, error.message)
                    } finally {
                        collection.cancelAndJoin()
                    }
                }
            }
        }
    }

    @Test
    fun testCancellationWhileWaitingForHeadersFinishesBeforeServerResponds() = runTest {
        withContext(Dispatchers.Default) {
            val requested = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val server = MockWebServer()
            server.start(
                rawEndpoints = listOf(
                    MockWebServer.RawEndpointConfig(
                        path = "/stream",
                        method = HttpMethod.Post,
                        responseBody = "data: late\n\n".encodeToByteArray(),
                        contentType = ContentType.Text.EventStream,
                        onRequest = {
                            requested.complete(Unit)
                            check(release.await(10, TimeUnit.SECONDS))
                        },
                    )
                )
            )
            val client = JavaKoogHttpClient.Factory().create(clientName = "java-sse", baseUrl = server.url(""))
            val collection = launch(Dispatchers.IO) { client.stream().toList() }
            try {
                withTimeout(5_000) { requested.await() }
                withTimeout(2_000) { collection.cancelAndJoin() }
                assertTrue(collection.isCancelled)
                assertEquals(1L, release.count)
            } finally {
                release.countDown()
                collection.cancelAndJoin()
                client.close()
                server.stop()
            }
        }
    }

    @Test
    fun testCancellationWhileWaitingForBodyFinishesBeforeServerSendsMore() = runTest {
        withStalledBody { client, release ->
            val preview = CompletableDeferred<Unit>()
            val collection = launch(Dispatchers.IO) { client.stream().collect { preview.complete(Unit) } }
            try {
                withTimeout(5_000) { preview.await() }
                withTimeout(2_000) { collection.cancelAndJoin() }
                assertTrue(collection.isCancelled)
                assertEquals(1L, release.count)
            } finally {
                release.countDown()
                collection.cancelAndJoin()
            }
        }
    }

    @Test
    fun testTakingFirstEventClosesAnIdleStream() = runTest {
        withStalledBody { client, release ->
            val eventDecoded = CompletableDeferred<Unit>()
            val first = async(Dispatchers.IO) {
                client.sse(
                    path = "/stream",
                    requestBody = "{}",
                    requestBodyType = String::class,
                    decodeStreamingResponse = {
                        eventDecoded.complete(Unit)
                        it
                    },
                    processStreamingChunk = { it },
                ).first()
            }
            try {
                withTimeout(5_000) { eventDecoded.await() }
                assertEquals("preview", withTimeout(2_000) { first.await() })
                assertEquals(1L, release.count)
            } finally {
                release.countDown()
                first.cancelAndJoin()
            }
        }
    }

    @Test
    fun testDownstreamFailureRemainsTheConsumersException() = runTest {
        withResponse("data: preview\n\ndata: completed\n\n") { client ->
            val cause = IllegalStateException("consumer failed")
            val error = assertFailsWith<IllegalStateException> {
                client.stream().collect { throw cause }
            }
            // Coroutine stack-trace recovery may copy exceptions across the channel boundary.
            assertEquals(cause.message, error.message)
        }
    }

    private suspend fun withResponse(
        response: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        block: suspend (KoogHttpClient) -> Unit,
    ) {
        val server = MockWebServer()
        server.start(
            rawEndpoints = listOf(
                MockWebServer.RawEndpointConfig(
                    path = "/stream",
                    method = HttpMethod.Post,
                    responseBody = response.encodeToByteArray(),
                    statusCode = status,
                    contentType = ContentType.Text.EventStream,
                    responseHeaders = mapOf("x-request-id" to "req_sse"),
                )
            )
        )
        val client = JavaKoogHttpClient.Factory().create(clientName = "java-sse", baseUrl = server.url(""))
        try {
            block(client)
        } finally {
            client.close()
            server.stop()
        }
    }

    private suspend fun withStalledBody(block: suspend kotlinx.coroutines.CoroutineScope.(KoogHttpClient, CountDownLatch) -> Unit) {
        withContext(Dispatchers.Default) {
            val release = CountDownLatch(1)
            val server = MockWebServer()
            server.start(
                linesEndpoints = listOf(
                    MockWebServer.LinesEndpointConfig(
                        path = "/stream",
                        lines = listOf("data: preview", "", "data: late", ""),
                        contentType = ContentType.Text.EventStream,
                        lineDelayMillis = 0,
                        onLineWritten = { index -> if (index == 1) check(release.await(10, TimeUnit.SECONDS)) },
                    )
                )
            )
            val client = JavaKoogHttpClient.Factory().create(clientName = "java-sse", baseUrl = server.url(""))
            try {
                block(client, release)
            } finally {
                release.countDown()
                client.close()
                server.stop()
            }
        }
    }

    private fun KoogHttpClient.stream(): Flow<String> = sse(
        path = "/stream",
        requestBody = "{}",
        requestBodyType = String::class,
        decodeStreamingResponse = { it },
        processStreamingChunk = { it },
    )
}
