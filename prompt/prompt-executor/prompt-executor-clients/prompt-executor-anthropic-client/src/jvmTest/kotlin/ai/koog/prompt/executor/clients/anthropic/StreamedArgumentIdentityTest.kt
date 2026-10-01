package ai.koog.prompt.executor.clients.anthropic

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
    fun testAnthropicAndVertexArgumentsCarryResolvedIdentity() = runTest {
        val model = AnthropicModels.Fable_5
        val events = listOf(
            """{"type": "content_block_start", "index": 0, "content_block": {"type": "tool_use", "id": "first", "name": "lookup", "input": {}}}""",
            """{"type": "content_block_start", "index": 1, "content_block": {"type": "tool_use", "id": "second", "name": "lookup", "input": {}}}""",
            """{"type": "content_block_delta", "index": 0, "delta": {"type": "input_json_delta", "partial_json": "{\"value\":\""}}""",
            """{"type": "content_block_delta", "index": 1, "delta": {"type": "input_json_delta", "partial_json": "{\"value\":\""}}""",
            """{"type": "content_block_delta", "index": 0, "delta": {"type": "input_json_delta", "partial_json": "a"}}""",
            """{"type": "content_block_delta", "index": 1, "delta": {"type": "input_json_delta", "partial_json": "b"}}""",
            """{"type": "content_block_delta", "index": 0, "delta": {"type": "input_json_delta", "partial_json": "a"}}""",
            """{"type": "content_block_delta", "index": 0, "delta": {"type": "input_json_delta", "partial_json": "\"}"}}""",
            """{"type": "content_block_delta", "index": 1, "delta": {"type": "input_json_delta", "partial_json": "\"}"}}""",
            """{"type": "content_block_stop", "index": 0}""",
            """{"type": "content_block_stop", "index": 1}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}""",
            """{"type":"message_stop"}""",
        )
        val clients = listOf(
            AnthropicLLMClient(settings = AnthropicClientSettings(modelVersionsMap = mapOf(model to model.id)), httpClient = IdentityFixtureHttp(events)),
            AnthropicVertexLLMClient(settings = AnthropicVertexClientSettings("project", "us-east1", mapOf(model to "claude-fable-5@20260701")), httpClient = IdentityFixtureHttp(events)),
        )
        clients.forEach { client ->
            val frames = client.executeStreaming(Prompt.build("identity") { user("Use both tools") }, model).toList()

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
