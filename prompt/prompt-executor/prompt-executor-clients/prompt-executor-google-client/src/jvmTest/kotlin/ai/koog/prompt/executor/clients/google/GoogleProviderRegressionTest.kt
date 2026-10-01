package ai.koog.prompt.executor.clients.google

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.google.models.GoogleResponse
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.toMessageResponse
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoogleProviderRegressionTest {
    private val noArgsResponse = """
        {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"noArgsTool"}}]},"finishReason":"STOP"}]}
    """.trimIndent()
    private val blockedResponse = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
    private val testPrompt = prompt("provider-regression") { user("Hello") }

    @Test
    fun testMissingArgumentsBecomeEmptyObject() = runTest {
        withClient(noArgsResponse) { client ->
            val call = assertIs<MessagePart.Tool.Call>(client.execute(testPrompt, GoogleModels.Gemini2_5Pro).parts.single())
            assertEquals("noArgsTool", call.tool)
            assertEquals("{}", call.args)
        }
    }

    @Test
    fun testStreamingMissingArgumentsBecomeEmptyObject() = runTest {
        withClient(noArgsResponse, streaming = true) { client ->
            val response = client.executeStreaming(testPrompt, GoogleModels.Gemini2_5Pro).toList().toMessageResponse()
            val call = assertIs<MessagePart.Tool.Call>(response.parts.single())
            assertEquals("noArgsTool", call.tool)
            assertEquals("{}", call.args)
        }
    }

    @Test
    fun testMissingCandidatesDecodePromptFeedback() {
        val response = Json.decodeFromString<GoogleResponse>(blockedResponse)
        assertTrue(response.candidates.isEmpty())
        assertEquals("SAFETY", response.promptFeedback?.blockReason)
    }

    @Test
    fun testBlockedPromptReportsReason() = runTest {
        withClient(blockedResponse) { client ->
            val error = assertFailsWith<LLMClientException> { client.execute(testPrompt, GoogleModels.Gemini2_5Pro) }
            assertTrue(error.message.orEmpty().contains("prompt was blocked (reason: SAFETY)"))
        }
    }

    @Test
    fun testStreamingBlockedPromptReportsReason() = runTest {
        withClient(blockedResponse, streaming = true) { client ->
            val error = assertFailsWith<LLMClientException> {
                client.executeStreaming(testPrompt, GoogleModels.Gemini2_5Pro).toList()
            }
            assertTrue(error.message.orEmpty().contains("prompt was blocked (reason: SAFETY)"))
        }
    }

    @Test
    fun testEmptyCandidatesWithoutFeedbackReportProviderError() = runTest {
        withClient("""{"candidates":[]}""") { client ->
            val error = assertFailsWith<LLMClientException> { client.execute(testPrompt, GoogleModels.Gemini2_5Pro) }
            assertTrue(error.message.orEmpty().contains("Google API returned no candidates"))
            assertFalse(error.message.orEmpty().contains("blocked"))
        }
    }

    @Test
    fun testStreamingUsageOnlyChunkPreservesResponse() = runTest {
        withClient(noArgsResponse, streaming = true, suffix = """{"usageMetadata":{"promptTokenCount":7}}""") { client ->
            val response = client.executeStreaming(testPrompt, GoogleModels.Gemini2_5Pro).toList().toMessageResponse()
            assertEquals("noArgsTool", assertIs<MessagePart.Tool.Call>(response.parts.single()).tool)
            assertEquals(7, response.metaInfo.inputTokensCount)
        }
    }

    @Test
    fun testCredentialsUseHeadersForOrdinaryAndStreamingUrls() = runTest {
        val response = """{"candidates":[{"content":{"parts":[{"text":"Hello"}]},"finishReason":"STOP"}]}"""
        for (streaming in listOf(false, true)) {
            withClient(response, streaming, checkCredentials = true) { client ->
                if (streaming) {
                    client.executeStreaming(testPrompt, GoogleModels.Gemini2_5Pro).toList()
                } else {
                    client.execute(testPrompt, GoogleModels.Gemini2_5Pro)
                }
            }
        }
    }

    private suspend fun withClient(
        payload: String,
        streaming: Boolean = false,
        suffix: String? = null,
        checkCredentials: Boolean = false,
        action: suspend (GoogleLLMClient) -> Unit,
    ) {
        val requests = ConcurrentLinkedQueue<Pair<String?, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.add(exchange.requestHeaders.getFirst("x-goog-api-key") to exchange.requestURI.toString())
            exchange.requestBody.use { it.readBytes() }
            val body = if (streaming) {
                listOfNotNull(payload, suffix).joinToString("") { "data: $it\n\n" }
            } else {
                payload
            }
            exchange.responseHeaders.add("Content-Type", if (streaming) "text/event-stream" else "application/json")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = HttpClient(CIO)
        val client = GoogleLLMClient(
            apiKey = "test-api-key",
            settings = GoogleClientSettings(baseUrl = "http://127.0.0.1:${server.address.port}"),
            httpClientFactory = KtorKoogHttpClient.Factory(base),
        )
        try {
            action(client)
            val (credential, uri) = requests.single()
            if (checkCredentials) {
                assertEquals("test-api-key", credential)
                assertFalse(uri.contains("key="))
                assertFalse(uri.contains("test-api-key"))
                assertEquals(
                    "/v1beta/models/gemini-2.5-pro:" +
                        if (streaming) "streamGenerateContent?alt=sse" else "generateContent",
                    uri,
                )
            }
        } finally {
            client.close()
            base.close()
            server.stop(0)
        }
    }
}
