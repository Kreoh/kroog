package ai.koog.prompt.executor.clients.anthropic

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.streaming.toMessageResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnthropicReasoningReplayTest {
    @Test
    fun testNonStreamSignedAndRedactedReasoningRoundTrip() = runTest {
        val transport = ReasoningFixtureHttpClient(postResponse = mixedResponse())
        val client = AnthropicLLMClient(
            settings = AnthropicClientSettings(modelVersionsMap = mapOf(reasoningModel to reasoningModel.id)),
            httpClient = transport,
        )

        val response = client.execute(userPrompt("direct-output"), reasoningModel)
        val signed = assertIs<MessagePart.Reasoning>(response.parts[0])
        assertEquals(listOf("private thought"), signed.content)
        assertEquals("signed-value", signed.encrypted)
        assertEquals(
            MessagePart.ReasoningReplay.Signed("private thought", "signed-value"),
            signed.replay.single(),
        )
        assertEquals("visible", assertIs<MessagePart.Text>(response.parts[1]).text)
        val redacted = assertIs<MessagePart.Reasoning>(response.parts[2])
        assertEquals(emptyList(), redacted.content)
        assertEquals(
            MessagePart.ReasoningReplay.OpaqueRedacted(opaqueData),
            redacted.replay.single(),
        )

        val replayJson = Json.parseToJsonElement(
            client.createAnthropicRequest(
                Prompt(messages = listOf(response), id = "direct-replay"),
                emptyList(),
                reasoningModel,
                false,
            )
        ).jsonObject
        assertReasoningWireOrder(replayJson.getValue("messages").jsonArray.single().jsonObject)
    }

    @Test
    fun testMalformedReasoningBlocksFailExplicitly() = runTest {
        val malformedResponse = mixedResponse().replace(
            "{\"type\":\"redacted_thinking\",\"data\":\"$opaqueData\"}",
            "{\"type\":\"redacted_thinking\"}",
        )
        val nonStreamClient = AnthropicLLMClient(
            settings = AnthropicClientSettings(modelVersionsMap = mapOf(reasoningModel to reasoningModel.id)),
            httpClient = ReasoningFixtureHttpClient(postResponse = malformedResponse),
        )
        val nonStreamFailure = assertFailsWith<LLMClientException> {
            nonStreamClient.execute(userPrompt("malformed-response"), reasoningModel)
        }
        assertTrue(nonStreamFailure.message.orEmpty().contains("'data' must be a string"))

        val streamClient = AnthropicLLMClient(
            settings = AnthropicClientSettings(modelVersionsMap = mapOf(reasoningModel to reasoningModel.id)),
            httpClient = ReasoningFixtureHttpClient(
                streamEvents = listOf(
                    """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"unfinished"}}""",
                    """{"type":"content_block_stop","index":0}""",
                ),
            ),
        )
        val streamFailure = assertFailsWith<LLMClientException> {
            streamClient.executeStreaming(userPrompt("malformed-stream"), reasoningModel).collect {}
        }
        assertTrue(streamFailure.message.orEmpty().contains("signature is missing"))

        val emptySignatureClient = AnthropicLLMClient(
            settings = AnthropicClientSettings(modelVersionsMap = mapOf(reasoningModel to reasoningModel.id)),
            httpClient = ReasoningFixtureHttpClient(
                streamEvents = listOf(
                    """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":""}}""",
                ),
            ),
        )
        val emptySignatureFailure = assertFailsWith<LLMClientException> {
            emptySignatureClient.executeStreaming(userPrompt("empty-signature"), reasoningModel).collect {}
        }
        assertTrue(emptySignatureFailure.message.orEmpty().contains("'signature' must not be empty"))
    }

    @Test
    fun testDirectStreamAcceptsThinkingStartWithoutSignature() = runTest {
        val client = AnthropicLLMClient(
            settings = AnthropicClientSettings(modelVersionsMap = mapOf(reasoningModel to reasoningModel.id)),
            httpClient = ReasoningFixtureHttpClient(streamEvents = mixedStream()),
        )

        val response = client.executeStreaming(userPrompt("direct-stream"), reasoningModel).toList().toMessageResponse()
        val signed = assertIs<MessagePart.ReasoningReplay.Signed>(
            assertIs<MessagePart.Reasoning>(response.parts.first()).replay.single()
        )
        assertEquals("private thought", signed.text)
        assertEquals("signed-value", signed.signature)
    }
}

class VertexAnthropicReplayTest {
    @Test
    fun testStreamingSignedAndRedactedReasoningReplaysInProviderOrder() = runTest {
        val transport = ReasoningFixtureHttpClient(streamEvents = mixedStream())
        val client = AnthropicVertexLLMClient(
            settings = AnthropicVertexClientSettings(
                projectId = "fixture-project",
                location = "europe-west1",
                modelVersionsMap = mapOf(reasoningModel to "claude-reasoning@fixture"),
            ),
            httpClient = transport,
        )

        val response = client.executeStreaming(userPrompt("vertex-output"), reasoningModel).toList().toMessageResponse()
        assertEquals(4, response.parts.size)
        assertIs<MessagePart.ReasoningReplay.Signed>(
            assertIs<MessagePart.Reasoning>(response.parts[0]).replay.single()
        )
        assertEquals("visible", assertIs<MessagePart.Text>(response.parts[1]).text)
        assertEquals(
            opaqueData,
            assertIs<MessagePart.ReasoningReplay.OpaqueRedacted>(
                assertIs<MessagePart.Reasoning>(response.parts[2]).replay.single()
            ).data,
        )
        assertEquals("lookup", assertIs<MessagePart.Tool.Call>(response.parts[3]).tool)

        val replayJson = Json.parseToJsonElement(
            client.createAnthropicRequest(
                Prompt(messages = listOf(response), id = "vertex-replay"),
                emptyList(),
                reasoningModel,
                false,
            )
        ).jsonObject
        assertReasoningWireOrder(replayJson.getValue("messages").jsonArray.single().jsonObject)
    }
}

private fun assertReasoningWireOrder(assistantMessage: JsonObject) {
    val content = assistantMessage.getValue("content").jsonArray
    assertEquals(listOf("thinking", "text", "redacted_thinking", "tool_use"), content.map(::contentType))
    assertEquals("private thought", content[0].jsonObject.getValue("thinking").jsonPrimitive.content)
    assertEquals("signed-value", content[0].jsonObject.getValue("signature").jsonPrimitive.content)
    assertEquals(opaqueData, content[2].jsonObject.getValue("data").jsonPrimitive.content)
    assertEquals("call-1", content[3].jsonObject.getValue("id").jsonPrimitive.content)
}

private fun contentType(element: kotlinx.serialization.json.JsonElement): String =
    element.jsonObject.getValue("type").jsonPrimitive.content

private fun userPrompt(id: String): Prompt = Prompt(
    messages = listOf(Message.User("hello", RequestMetaInfo.Empty)),
    id = id,
)

private fun mixedResponse(): String =
    """
    {
      "id":"message-reasoning",
      "type":"message",
      "role":"assistant",
      "content":[
        {"type":"thinking","thinking":"private thought","signature":"signed-value"},
        {"type":"text","text":"visible"},
        {"type":"redacted_thinking","data":"$opaqueData"},
        {"type":"tool_use","id":"call-1","name":"lookup","input":{"query":"koog"}}
      ],
      "model":"${reasoningModel.id}",
      "stop_reason":"tool_use",
      "usage":{"input_tokens":4,"output_tokens":5}
    }
    """.trimIndent()

private fun mixedStream(): List<String> = listOf(
    """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
    """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"private thought"}}""",
    """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed-value"}}""",
    """{"type":"content_block_stop","index":0}""",
    """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":"visible"}}""",
    """{"type":"content_block_stop","index":1}""",
    """{"type":"content_block_start","index":2,"content_block":{"type":"redacted_thinking","data":"$opaqueData"}}""",
    """{"type":"content_block_stop","index":2}""",
    """{"type":"content_block_start","index":3,"content_block":{"type":"tool_use","id":"call-1","name":"lookup","input":{}}}""",
    """{"type":"content_block_delta","index":3,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"koog\"}"}}""",
    """{"type":"content_block_stop","index":3}""",
    """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}""",
)

private val reasoningModel = AnthropicModels.Fable_5
private const val opaqueData = "AAEC/w=="

private class ReasoningFixtureHttpClient(
    private val postResponse: String = mixedResponse(),
    private val streamEvents: List<String> = emptyList(),
) : KoogHttpClient {
    override val clientName: String = "anthropic-reasoning-fixture"

    override suspend fun <R : Any> get(
        path: String,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = error("GET is not expected")

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = postResponse as R

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
