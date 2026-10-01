package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamedArgumentIdentityTest {
    @Test
    fun testInterleavedArgumentsCarryResolvedIdentity() = runTest {
        val events = listOf(
            """{"id": "stream", "object": "chat.completion.chunk", "created": 0, "model": "test", "choices": [{"index": 0, "delta": {"tool_calls": [{"index": 0, "function": {"arguments": "{\"value\":\"", "name": "lookup"}, "id": "first"}]}, "finish_reason": null}]}""",
            """{"id": "stream", "object": "chat.completion.chunk", "created": 0, "model": "test", "choices": [{"index": 0, "delta": {"tool_calls": [{"index": 1, "function": {"arguments": "{\"value\":\"", "name": "lookup"}, "id": "second"}]}, "finish_reason": null}]}""",
            """{"id": "stream", "object": "chat.completion.chunk", "created": 0, "model": "test", "choices": [{"index": 0, "delta": {"tool_calls": [{"index": 0, "function": {"arguments": "a"}}]}, "finish_reason": null}]}""",
            """{"id": "stream", "object": "chat.completion.chunk", "created": 0, "model": "test", "choices": [{"index": 0, "delta": {"tool_calls": [{"index": 1, "function": {"arguments": "b"}}]}, "finish_reason": null}]}""",
            """{"id": "stream", "object": "chat.completion.chunk", "created": 0, "model": "test", "choices": [{"index": 0, "delta": {"tool_calls": [{"index": 0, "function": {"arguments": "a"}}]}, "finish_reason": null}]}""",
            """{"id": "stream", "object": "chat.completion.chunk", "created": 0, "model": "test", "choices": [{"index": 0, "delta": {"tool_calls": [{"index": 0, "function": {"arguments": "\"}"}}]}, "finish_reason": null}]}""",
            """{"id": "stream", "object": "chat.completion.chunk", "created": 0, "model": "test", "choices": [{"index": 0, "delta": {"tool_calls": [{"index": 1, "function": {"arguments": "\"}"}}]}, "finish_reason": null}]}""",
        )
        val client = OpenAILLMClient(httpClient = IdentityFixtureHttp(events))
        val frames = client.executeStreaming(Prompt.build("identity") { user("Use both tools") }, OpenAIModels.Chat.GPT4o).toList()

        val deltas = frames.filterIsInstance<StreamFrame.ToolCallDelta>()
        assertTrue(deltas.all { it.id != null && it.name == "lookup" && it.index != null })
        val complete = frames.filterIsInstance<StreamFrame.ToolCallComplete>()
        assertEquals(listOf("first", "second"), complete.map { it.id })
        complete.forEach { call ->
            assertEquals(call.content, deltas.filter { it.id == call.id }.mapNotNull { it.content }.joinToString(""))
        }
        assertEquals("{\"value\":\"aa\"}", complete[0].content)
        assertEquals("{\"value\":\"b\"}", complete[1].content)
    }
}

private class IdentityFixtureHttp(private val events: List<String>) : KoogHttpClient {
    override val clientName: String = "identity-fixture"
    override suspend fun <R : Any> get(path: String, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = error("GET not expected")
    override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: KClass<T>, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = error("POST not expected")
    override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): Flow<O> = flow {
        events.forEach { raw ->
            if (dataFilter(raw)) processStreamingChunk(decodeStreamingResponse(raw))?.let { emit(it) }
        }
    }
    override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: KClass<T>, parameters: Map<String, String>, headers: Map<String, String>): Flow<String> = error("lines not expected")
    override fun close() = Unit
}
