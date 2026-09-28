package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.ktor.KtorKoogHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAILiveTranscriptionClientTest {
    @Test
    fun testCreatesTranscriptionSessionWithOneAuthenticatedMultipartRequest() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/v1/realtime/calls", request.url.encodedPath)
            assertEquals(listOf("Bearer project-key"), request.headers.getAll(HttpHeaders.Authorization))
            assertEquals("multipart/form-data", request.body.contentType?.withoutParameters().toString())
            assertEquals("application/sdp", request.headers[HttpHeaders.Accept])
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("name=sdp"))
            assertTrue(body.contains(OFFER))
            assertTrue(body.contains("name=session"))
            assertTrue(
                body.contains(
                    """{"type":"transcription","audio":{"input":{"transcription":{"model":"gpt-live-transcribe"},"turn_detection":null}}}"""
                )
            )
            respond(ANSWER, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/sdp"))
        }
        withClient(engine) { client -> assertEquals(ANSWER, client.createSession(OFFER)) }
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun testPreservesContextLanguagesAndEveryDelaySetting() = runTest {
        for (delay in OpenAITranscriptionDelay.entries) {
            val engine = MockEngine { request ->
                assertTrue(
                    request.body.toByteArray().decodeToString().contains(
                        """{"type":"transcription","audio":{"input":{"transcription":{"model":"gpt-live-transcribe-snapshot","prompt":"Names and numbers","keywords":["Kroog","AC-42"],"languages":["en","ru"],"delay":"${delay.name.lowercase()}"},"turn_detection":null}}}"""
                    )
                )
                respond(ANSWER)
            }
            withClient(engine) { client ->
                client.createSession(
                    OFFER,
                    OpenAILiveTranscriptionOptions(
                        model = "gpt-live-transcribe-snapshot",
                        prompt = "Names and numbers",
                        keywords = listOf("Kroog", "AC-42"),
                        languages = listOf("en", "ru"),
                        delay = delay,
                    ),
                )
            }
        }
    }

    @Test
    fun testRejectsBlankInputsBeforeMakingRequests() = runTest {
        val engine = MockEngine { error("No request expected") }
        withClient(engine) { client ->
            assertFailsWith<IllegalArgumentException> { client.createSession(" ") }
            assertFailsWith<IllegalArgumentException> {
                client.createSession(OFFER, OpenAILiveTranscriptionOptions(model = ""))
            }
        }
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun testRejectsInvalidSdpAnswer() = runTest {
        for (answer in listOf("", "{}", "<html>error</html>")) {
            val engine = MockEngine { respond(answer) }
            withClient(engine) { client ->
                assertFailsWith<IllegalStateException> { client.createSession(OFFER) }
            }
        }
    }

    @Test
    fun testPreservesHttpFailureWithoutRetrying() = runTest {
        val engine = MockEngine {
            respond(
                """{"error":{"message":"quota exceeded"}}""",
                HttpStatusCode.TooManyRequests,
                headersOf("x-request-id", "request-123"),
            )
        }
        withClient(engine) { client ->
            val error = assertFailsWith<KoogHttpClientException> { client.createSession(OFFER) }
            assertEquals("request-123", error.requestId)
            assertTrue(error.errorBody.orEmpty().contains("quota exceeded"))
        }
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun testCancellationPropagatesWithoutRetrying() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            throw CancellationException("cancelled setup")
        }
        withClient(engine) { client ->
            assertFailsWith<CancellationException> { client.createSession(OFFER) }
        }
        assertEquals(1, requests)
    }

    @Test
    fun testSupportsConfiguredEndpointPaths() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/proxy/calls" -> respond(ANSWER)
                else -> error("Unexpected path")
            }
        }
        val base = HttpClient(engine)
        val transport = KtorKoogHttpClient.Factory(baseClient = base).create(
            clientName = "transcription-test",
            baseUrl = "https://example.com/",
        )
        try {
            val client = OpenAILiveTranscriptionClient(transport, "proxy/calls")
            assertEquals(ANSWER, client.createSession(OFFER))
        } finally {
            transport.close()
            base.close()
        }
    }

    private suspend fun withClient(engine: MockEngine, block: suspend (OpenAILiveTranscriptionClient) -> Unit) {
        val base = HttpClient(engine)
        val transport = KtorKoogHttpClient.Factory(baseClient = base).create(
            clientName = "transcription-test",
            baseUrl = "https://example.com/",
            headers = mapOf(HttpHeaders.Authorization to "Bearer project-key"),
        )
        try {
            block(OpenAILiveTranscriptionClient(transport))
        } finally {
            transport.close()
            base.close()
        }
    }
}

private const val OFFER = "v=0\r\no=browser-offer\r\n"
private const val ANSWER = "v=0\r\no=provider-answer\r\n"
