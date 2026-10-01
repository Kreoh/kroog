package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.azure.AzureOpenAIClientSettings
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAITokenUsageTest {
    @Test
    fun testPartialUsageInvalidatesStaleTotalsAndRetainsUnchangedTotals() = runTest {
        val cases = listOf(
            Triple("""{"completion_tokens":5,"total_tokens":105}""", """{"completion_tokens":10}""", null),
            Triple("""{"prompt_tokens":5,"total_tokens":105}""", """{"prompt_tokens":10}""", null),
            Triple("""{"prompt_tokens":5,"total_tokens":105}""", """{"prompt_tokens":0}""", null),
            Triple("""{"completion_tokens":5,"total_tokens":105}""", """{"completion_tokens":5}""", 105),
            Triple("""{"completion_tokens":5,"total_tokens":105}""", """{"completion_tokens":10,"total_tokens":110}""", 110),
        )
        cases.forEach { (initial, update, expectedTotal) ->
            val chunks = listOf(initial, update).map { usage ->
                """{"id":"id","object":"chat.completion.chunk","created":1,"model":"gpt-4o","choices":[],"usage":$usage}"""
            } + "[DONE]"
            val client = OpenAILLMClient(OpenAIClientSettings(), UsageTransport("", chunks))
            val prompt = Prompt.build("partial-usage", params = OpenAIChatParams()) { user("hello") }
            val meta = client.executeStreaming(prompt, OpenAIModels.Chat.GPT4o).toList()
                .filterIsInstance<StreamFrame.End>().single().metaInfo!!
            assertEquals(expectedTotal, meta.totalTokensCount, "Initial: $initial; update: $update")
        }
    }

    @Test
    fun testStreamingCacheWriteUpdatesPreserveZeroAndMissingValues() = runTest {
        for ((update, expected) in listOf(
            "{}" to 20,
            "{\"cache_write_tokens\":null}" to 20,
            "{\"cache_write_tokens\":0}" to 0,
            "{\"cache_write_tokens\":30}" to 30,
        )) {
            val usages = listOf(
                """{"prompt_tokens":100,"completion_tokens":40,"prompt_tokens_details":{"cached_tokens":10,"cache_write_tokens":20}}""",
                """{"prompt_tokens_details":$update}""",
            )
            val chunks = usages.map { usage ->
                """{"id":"id","object":"chat.completion.chunk","created":1,"model":"gpt-4o","choices":[],"usage":$usage}"""
            } + "[DONE]"
            val client = OpenAILLMClient(OpenAIClientSettings(), UsageTransport("", chunks))
            val prompt = Prompt.build("cache-write-update", params = OpenAIChatParams()) { user("hello") }
            val meta = client.executeStreaming(prompt, OpenAIModels.Chat.GPT4o).toList()
                .filterIsInstance<StreamFrame.End>().single().metaInfo!!
            assertEquals(expected, meta.cacheWriteTokensCount)
            assertEquals(10, meta.cacheReadTokensCount)
            assertEquals(100, meta.inputTokensCount)
            assertEquals(40, meta.outputTokensCount)
            assertEquals(140, meta.totalTokensCount)
        }
    }

    @Test
    fun testChatUsageKeepsInclusiveTotalsAndOptionalDetails() = runTest {
        val settings = listOf(
            OpenAIClientSettings(),
            AzureOpenAIClientSettings("https://resource.openai.azure.com", "chat-deployment", "v1"),
        )
        val counts = listOf(null, 0, 20)
        val cases = settings.flatMap { config -> counts.flatMap { read -> counts.map { write -> Triple(config, read, write) } } }
        cases.forEach { (config, breakdown, cacheWrite) ->
            val promptDetails = listOfNotNull(
                breakdown?.let { "\"cached_tokens\":$it" },
                cacheWrite?.let { "\"cache_write_tokens\":$it" },
            ).joinToString(",")
            val details = if (promptDetails.isEmpty()) {
                ""
            } else {
                ""","prompt_tokens_details":{$promptDetails},"completion_tokens_details":{"reasoning_tokens":${breakdown ?: "null"}}"""
            }
            val usage = """{"prompt_tokens":100,"completion_tokens":40,"total_tokens":140$details}"""
            val response = """{"id":"id","object":"chat.completion","created":1,"model":"gpt-4o","choices":[{"index":0,"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}],"usage":$usage}"""
            val chunks = listOf(
                """{"id":"id","object":"chat.completion.chunk","created":1,"model":"gpt-4o","choices":[],"usage":$usage}""",
                """{"id":"id","object":"chat.completion.chunk","created":1,"model":"gpt-4o","choices":[],"usage":{"completion_tokens":40}}""",
                """{"id":"id","object":"chat.completion.chunk","created":1,"model":"gpt-4o","choices":[],"usage":null}""",
                "[DONE]",
            )
            val client = OpenAILLMClient(config, UsageTransport(response, chunks))
            val prompt = Prompt.build("usage", params = OpenAIChatParams()) { user("hello") }
            val metas = listOf(
                client.execute(prompt, OpenAIModels.Chat.GPT4o).metaInfo,
                client.executeStreaming(prompt, OpenAIModels.Chat.GPT4o).toList().filterIsInstance<StreamFrame.End>().single().metaInfo!!
            )
            metas.forEach { meta ->
                assertEquals(100, meta.inputTokensCount)
                assertEquals(40, meta.outputTokensCount)
                assertEquals(140, meta.totalTokensCount)
                assertEquals(breakdown, meta.cacheReadTokensCount)
                assertEquals(breakdown, meta.reasoningTokensCount)
                assertEquals(cacheWrite, meta.cacheWriteTokensCount)
            }
        }
    }
}

private class UsageTransport(
    private val postResponse: String,
    private val streamEvents: List<String> = emptyList(),
) : KoogHttpClient {
    override val clientName: String = "openai-usage-fixture"

    override suspend fun <R : Any> get(
        path: String,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = error("GET is not expected")

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = responseType.java.cast(postResponse)

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> = flow {
        streamEvents.forEach { raw ->
            if (dataFilter(raw)) {
                processStreamingChunk(decodeStreamingResponse(raw))?.let { emit(it) }
            }
        }
    }

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> = error("lines is not expected")

    override fun close(): Unit = Unit
}
