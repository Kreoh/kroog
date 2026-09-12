package ai.koog.prompt.executor.clients.google

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.streaming.StreamFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleTokenUsageTest {
    @Test
    fun testInclusiveInputAndThinkingOutputAcrossTransports() = runTest {
        val cases = listOf(
            "{}" to listOf(null, null, null, null, null),
            """{"promptTokenCount":100,"candidatesTokenCount":10,"totalTokenCount":110}""" to listOf(100, 10, 110, null, null),
            """{"promptTokenCount":100,"candidatesTokenCount":10,"thoughtsTokenCount":20,"cachedContentTokenCount":40,"totalTokenCount":130}""" to listOf(100, 30, 130, 40, 20),
            """{"promptTokenCount":0,"candidatesTokenCount":0,"thoughtsTokenCount":0,"cachedContentTokenCount":0,"totalTokenCount":0}""" to listOf(0, 0, 0, 0, 0),
            """{"promptTokenCount":100,"candidatesTokenCount":10,"toolUsePromptTokenCount":30,"thoughtsTokenCount":20,"totalTokenCount":160}""" to listOf(130, 30, 160, null, 20),
            """{"promptTokenCount":100,"candidatesTokenCount":10,"totalTokenCount":999}""" to listOf(100, 10, null, null, null),
            """{"thoughtsTokenCount":20,"cachedContentTokenCount":40}""" to listOf(null, null, null, 40, 20),
        )
        cases.forEach { (usage, expected) ->
            val response = """{"candidates":[{"content":{"role":"model","parts":[{"text":"answer"}]},"finishReason":"STOP"}],"usageMetadata":$usage}"""
            val engine = MockEngine {
                respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val client = GoogleLLMClient(apiKey = "test", httpClientFactory = KtorKoogHttpClient.Factory(HttpClient(engine)))
            val prompt = Prompt.build("usage") { user("hello") }
            val model = GoogleModels.Gemini2_5Pro
            val streamingClient = GoogleLLMClient(httpClient = googleStreamingTransport(model.id, listOf(response, response)))
            val metas = listOf(
                client.execute(prompt, model).metaInfo,
                streamingClient.executeStreaming(prompt, model).toList().filterIsInstance<StreamFrame.End>().single().metaInfo!!
            )
            metas.forEach { meta ->
                assertEquals(
                    expected,
                    listOf(
                        meta.inputTokensCount,
                        meta.outputTokensCount,
                        meta.totalTokensCount,
                        meta.cacheReadTokensCount,
                        meta.reasoningTokensCount
                    )
                )
            }
        }
    }

    @Test
    fun testPartialSnapshotsRetainInputAndReplaceCumulativeOutput() = runTest {
        val chunks = listOf(
            """{"candidates":[],"usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":40,"candidatesTokenCount":0,"totalTokenCount":100}}""",
            """{"candidates":[],"usageMetadata":{"candidatesTokenCount":10,"thoughtsTokenCount":20}}""",
            """{"candidates":[],"usageMetadata":{"candidatesTokenCount":10,"thoughtsTokenCount":20}}""",
            """{"candidates":[],"usageMetadata":{"candidatesTokenCount":15}}""",
            """{"candidates":[{"finishReason":"STOP"}]}""",
        )
        val client = GoogleLLMClient(httpClient = googleStreamingTransport(GoogleModels.Gemini2_5Pro.id, chunks))
        val meta = client.executeStreaming(Prompt.build("usage") { user("hello") }, GoogleModels.Gemini2_5Pro)
            .toList().filterIsInstance<StreamFrame.End>().single().metaInfo!!
        assertEquals(100, meta.inputTokensCount)
        assertEquals(35, meta.outputTokensCount)
        assertEquals(135, meta.totalTokensCount)
        assertEquals(40, meta.cacheReadTokensCount)
        assertEquals(20, meta.reasoningTokensCount)
    }
}
