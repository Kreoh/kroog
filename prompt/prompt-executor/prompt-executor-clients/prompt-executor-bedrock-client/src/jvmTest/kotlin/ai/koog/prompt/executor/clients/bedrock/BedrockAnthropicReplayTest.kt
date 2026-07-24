package ai.koog.prompt.executor.clients.bedrock

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicVertexClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicVertexLLMClient
import ai.koog.prompt.executor.clients.bedrock.converse.BedrockConverseConverters
import ai.koog.prompt.executor.clients.bedrock.modelfamilies.anthropic.BedrockAnthropicClaudeSerialization
import ai.koog.prompt.executor.clients.bedrock.util.JsonDocumentConverters
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.ClientManagedPresentationReplayException
import ai.koog.prompt.message.ExecutionOrigin
import ai.koog.prompt.message.ManagedExecutionSessionReference
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseOutput
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ReasoningContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ReasoningTextBlock
import aws.sdk.kotlin.services.bedrockruntime.model.StopReason
import aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import aws.sdk.kotlin.services.bedrockruntime.model.Message as BedrockMessage

class BedrockAnthropicReplayTest {
    @Test
    fun testClientManagedPresentationUsesOnlyCustomToolTranscriptForInvokeModelAndConverse() {
        val prompt = managedPrompt()

        val invokeRequest = BedrockAnthropicClaudeSerialization.createAnthropicRequest(prompt, emptyList())
        val invokeJson = bedrockJson.encodeToJsonElement(invokeRequest).toString()
        assertTrue(invokeJson.contains("managed_execution"))
        assertFalse(invokeJson.contains("presentation-only-output"))
        assertFalse(invokeJson.contains("sandbox-secret"))

        val converseRequest = BedrockConverseConverters.createConverseRequest(
            prompt,
            bedrockModel,
            emptyList(),
        )
        val rendered = converseRequest.toString()
        assertTrue(rendered.contains("managed_execution"))
        assertFalse(rendered.contains("presentation-only-output"))
        assertFalse(rendered.contains("sandbox-secret"))
    }

    @Test
    fun testMalformedManagedTranscriptFailsBeforeInvokeModelAndConverseMapping() {
        val prompt = managedPrompt(resultTool = "wrong_tool")

        assertFailsWith<ClientManagedPresentationReplayException> {
            BedrockAnthropicClaudeSerialization.createAnthropicRequest(prompt, emptyList())
        }
        assertFailsWith<ClientManagedPresentationReplayException> {
            BedrockConverseConverters.createConverseRequest(prompt, bedrockModel, emptyList())
        }
    }

    @Test
    fun testBedrockInvokeModelOutputReplaysThroughVertexAnthropic() = runTest {
        val bedrockOutput = BedrockAnthropicClaudeSerialization.parseAnthropicResponse(mixedResponse())
        val transport = CapturingAnthropicTransport()
        val vertex = vertexClient(transport)

        vertex.execute(Prompt(messages = listOf(bedrockOutput), id = "bedrock-to-vertex"), anthropicModel)

        assertWireOrder(transport.requestBody())
    }

    @Test
    fun testVertexAnthropicOutputReplaysThroughBedrockInvokeModelAndConverse() = runTest {
        val transport = CapturingAnthropicTransport(response = mixedResponse())
        val vertexOutput = vertexClient(transport).execute(userPrompt("vertex-output"), anthropicModel)

        val invokeRequest = BedrockAnthropicClaudeSerialization.createAnthropicRequest(
            Prompt(messages = listOf(vertexOutput), id = "vertex-to-invoke"),
            emptyList(),
        )
        val invokeJson = bedrockJson.encodeToJsonElement(invokeRequest).jsonObject
        assertWireOrder(invokeJson)

        val converseRequest = BedrockConverseConverters.createConverseRequest(
            Prompt(messages = listOf(vertexOutput), id = "vertex-to-converse"),
            bedrockModel,
            emptyList(),
        )
        val blocks = converseRequest.messages.orEmpty().single().content
        val signed = assertIs<ReasoningContentBlock.ReasoningText>(
            assertIs<ContentBlock.ReasoningContent>(blocks[0]).value
        ).value
        assertEquals("private thought", signed.text)
        assertEquals("signed-value", signed.signature)
        assertEquals("visible", assertIs<ContentBlock.Text>(blocks[1]).value)
        val redacted = assertIs<ReasoningContentBlock.RedactedContent>(
            assertIs<ContentBlock.ReasoningContent>(blocks[2]).value
        ).value
        assertContentEquals(opaqueBytes, redacted)
        assertEquals("call-1", assertIs<ContentBlock.ToolUse>(blocks[3]).value.toolUseId)
    }

    @Test
    fun testBedrockConverseOutputReplaysThroughVertexAnthropicWithoutOpaqueText() = runTest {
        val response = BedrockConverseConverters.convertConverseResponse(
            ConverseResponse {
                output = ConverseOutput.Message(
                    BedrockMessage {
                        role = ConversationRole.Assistant
                        content = listOf(
                            ContentBlock.ReasoningContent(
                                ReasoningContentBlock.ReasoningText(
                                    ReasoningTextBlock {
                                        text = "private thought"
                                        signature = "signed-value"
                                    }
                                )
                            ),
                            ContentBlock.Text("visible"),
                            ContentBlock.ReasoningContent(ReasoningContentBlock.RedactedContent(opaqueBytes)),
                            ContentBlock.ToolUse(
                                ToolUseBlock {
                                    toolUseId = "call-1"
                                    name = "lookup"
                                    input = JsonDocumentConverters.convertToDocument(
                                        Json.parseToJsonElement("""{"query":"koog"}""")
                                    )
                                }
                            ),
                        )
                    }
                )
                stopReason = StopReason.ToolUse
            },
            KoogClock.System,
        )
        val opaqueReasoning = assertIs<MessagePart.Reasoning>(response.parts[2])
        assertEquals(emptyList(), opaqueReasoning.content)

        val transport = CapturingAnthropicTransport()
        vertexClient(transport).execute(Prompt(messages = listOf(response), id = "converse-to-vertex"), anthropicModel)
        assertWireOrder(transport.requestBody())
    }

    @Test
    fun testMalformedBedrockReasoningFailsExplicitly() {
        val malformed = mixedResponse().replace(
            "{\"type\":\"redacted_thinking\",\"data\":\"$opaqueData\"}",
            "{\"type\":\"redacted_thinking\",\"data\":false}",
        )

        val failure = assertFailsWith<LLMClientException> {
            BedrockAnthropicClaudeSerialization.parseAnthropicResponse(malformed)
        }
        assertTrue(failure.message.orEmpty().contains("'data' must be a string"))
    }

    @Test
    fun testBedrockInvokeModelStreamAcceptsDocumentedThinkingStartWithoutSignature() = runTest {
        val frames = BedrockAnthropicClaudeSerialization.transformAnthropicStreamChunks(
            flowOf(
                """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"private thought"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed-value"}}""",
                """{"type":"content_block_stop","index":0}""",
            ),
        ).toList()

        assertEquals(StreamFrame.ReasoningDelta("private thought", index = 0), frames.first())
        val complete = assertIs<StreamFrame.ReasoningComplete>(frames.last())
        assertEquals("signed-value", complete.encrypted)
        assertEquals(
            MessagePart.ReasoningReplay.Signed("private thought", "signed-value"),
            complete.replay.single(),
        )
    }

    @Test
    fun testBedrockInvokeModelStreamRejectsMalformedCompletedThinking() = runTest {
        val emptySignatureFailure = assertFailsWith<LLMClientException> {
            BedrockAnthropicClaudeSerialization.transformAnthropicStreamChunks(
                flowOf(
                    """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":""}}""",
                ),
            ).toList()
        }
        assertTrue(emptySignatureFailure.message.orEmpty().contains("'signature' must not be empty"))

        val missingSignatureFailure = assertFailsWith<LLMClientException> {
            BedrockAnthropicClaudeSerialization.transformAnthropicStreamChunks(
                flowOf(
                    """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                    """{"type":"content_block_stop","index":0}""",
                ),
            ).toList()
        }
        assertTrue(missingSignatureFailure.message.orEmpty().contains("signature is missing"))
    }
}

private fun managedPrompt(resultTool: String = "managed_execution"): Prompt = Prompt(
    messages = listOf(
        Message.Assistant(
            parts = listOf(
                MessagePart.Tool.Call(
                    id = "call-managed",
                    tool = resultTool,
                    args = """{"executionId":"execution-1","code":"print(1)"}""",
                ),
                MessagePart.HostedExecution.Result(
                    output = "presentation-only-output",
                    executionId = "execution-1",
                    origin = ExecutionOrigin.CLIENT_MANAGED,
                    managedSession = ManagedExecutionSessionReference.VertexAgentEngine(
                        project = "project-1",
                        location = "us-central1",
                        reasoningEngineResource = "reasoningEngines/engine-1",
                        sandboxResourceName = "sandbox-secret",
                    ),
                    toolCallId = "call-managed",
                ),
            ),
            metaInfo = ResponseMetaInfo.Empty,
        ),
        Message.User(
            part = MessagePart.Tool.Result(
                id = "call-managed",
                tool = resultTool,
                output = "ordinary-result",
            ),
            metaInfo = RequestMetaInfo.Empty,
        ),
    ),
    id = "managed-replay",
)

private fun assertWireOrder(request: kotlinx.serialization.json.JsonObject) {
    val content = request.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray
    assertEquals(
        listOf("thinking", "text", "redacted_thinking", "tool_use"),
        content.map { it.jsonObject.getValue("type").jsonPrimitive.content },
    )
    assertEquals("private thought", content[0].jsonObject.getValue("thinking").jsonPrimitive.content)
    assertEquals("signed-value", content[0].jsonObject.getValue("signature").jsonPrimitive.content)
    assertEquals(opaqueData, content[2].jsonObject.getValue("data").jsonPrimitive.content)
    assertEquals("call-1", content[3].jsonObject.getValue("id").jsonPrimitive.content)
}

private fun vertexClient(transport: KoogHttpClient): AnthropicVertexLLMClient = AnthropicVertexLLMClient(
    settings = AnthropicVertexClientSettings(
        projectId = "fixture-project",
        location = "europe-west1",
        modelVersionsMap = mapOf(anthropicModel to "claude-reasoning@fixture"),
    ),
    httpClient = transport,
)

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
      "model":"${anthropicModel.id}",
      "stop_reason":"tool_use",
      "usage":{"input_tokens":4,"output_tokens":5}
    }
    """.trimIndent()

private val anthropicModel = AnthropicModels.Fable_5
private val bedrockModel = LLModel(
    provider = LLMProvider.Bedrock,
    id = "fixture.bedrock.anthropic",
    capabilities = listOf(LLMCapability.Completion, LLMCapability.Thinking, LLMCapability.Tools),
    contextLength = 200_000,
)
private val opaqueBytes = byteArrayOf(0, 1, 2, -1)
private val opaqueData = Base64.encode(opaqueBytes)
private val bedrockJson = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    explicitNulls = false
}

private class CapturingAnthropicTransport(
    private val response: String =
        """{"id":"reply","type":"message","role":"assistant","content":[{"type":"text","text":"ok"}],"model":"fixture","stop_reason":"end_turn"}""",
) : KoogHttpClient {
    override val clientName: String = "bedrock-anthropic-replay-fixture"
    private var request: String? = null

    fun requestBody(): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(requireNotNull(request)).jsonObject

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
    ): R {
        request = requestBody as String
        return response as R
    }

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> = emptyFlow()

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> = error("lines is not expected")

    override fun close(): Unit = Unit
}
