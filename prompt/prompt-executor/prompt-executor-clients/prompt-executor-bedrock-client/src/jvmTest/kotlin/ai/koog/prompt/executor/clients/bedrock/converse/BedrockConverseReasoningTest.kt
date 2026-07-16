package ai.koog.prompt.executor.clients.bedrock.converse

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.bedrock.util.JsonDocumentConverters
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockDelta
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockDeltaEvent
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockStart
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockStartEvent
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockStopEvent
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseOutput
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamMetadataEvent
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamOutput
import aws.sdk.kotlin.services.bedrockruntime.model.MessageStopEvent
import aws.sdk.kotlin.services.bedrockruntime.model.ReasoningContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ReasoningContentBlockDelta
import aws.sdk.kotlin.services.bedrockruntime.model.StopReason
import aws.sdk.kotlin.services.bedrockruntime.model.TokenUsage
import aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlockDelta
import aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlockStart
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import aws.sdk.kotlin.services.bedrockruntime.model.Message as BedrockMessage

class BedrockConverseReasoningTest {
    @Test
    fun `adaptive thinking creates exact request document and preserves unrelated properties`() {
        val params = BedrockConverseParams(
            additionalProperties = mapOf(
                "vendor" to buildJsonObject { put("enabled", true) },
            ),
        ).withThinking(
            BedrockThinkingConfig.Adaptive(
                effort = BedrockReasoningEffort.HIGH,
                display = BedrockThinkingDisplay.SUMMARIZED,
            ),
        )

        val request = BedrockConverseConverters.createConverseRequest(prompt(params), thinkingModel, emptyList())
        val requestFields = JsonDocumentConverters.convertToJsonElement(request.additionalModelRequestFields)

        assertEquals(
            """{"vendor":{"enabled":true},"thinking":{"type":"adaptive","display":"summarized"},"output_config":{"effort":"high"}}""",
            requestFields.toString(),
        )
        assertEquals(params, params.copy())
        assertEquals(params.hashCode(), params.copy().hashCode())
        assertEquals(params.thinking, params.copy(topP = 0.8).thinking)
        assertEquals(null, params.withThinking(null).thinking)
        assertTrue(params.toString().contains("thinking=Adaptive(effort=HIGH, display=SUMMARIZED)"))
    }

    @Test
    fun `typed thinking rejects reserved additional property collisions`() {
        listOf("thinking", "output_config").forEach { reservedKey ->
            val params = BedrockConverseParams(
                additionalProperties = mapOf(reservedKey to JsonPrimitive(true)),
            ).withThinking(adaptiveThinking)

            val error = assertFailsWith<IllegalArgumentException> {
                BedrockConverseConverters.createConverseRequest(prompt(params), thinkingModel, emptyList())
            }

            assertTrue(error.message.orEmpty().contains("'$reservedKey'"))
        }
    }

    @Test
    fun `typed thinking requires model thinking capability`() {
        val model = thinkingModel.copy(capabilities = listOf(LLMCapability.Completion))
        val error = assertFailsWith<IllegalArgumentException> {
            BedrockConverseConverters.createConverseRequest(
                prompt(BedrockConverseParams().withThinking(adaptiveThinking)),
                model,
                emptyList(),
            )
        }

        assertTrue(error.message.orEmpty().contains("doesn't support thinking"))
    }

    @Test
    fun `reasoning text is emitted immediately with its content block index`() = runTest {
        val frames = transform(reasoningText("thinking", index = 2))

        assertEquals(
            listOf(StreamFrame.ReasoningDelta(text = "thinking", index = 2)),
            frames,
        )
    }

    @Test
    fun `late signature is attached to reasoning completion at content block stop`() = runTest {
        val frames = transform(
            reasoningText("thinking", index = 2),
            reasoningSignature("signature", index = 2),
            contentBlockStop(index = 2),
        )

        assertEquals(
            listOf(
                StreamFrame.ReasoningDelta(text = "thinking", index = 2),
                StreamFrame.ReasoningComplete(
                    id = null,
                    content = listOf("thinking"),
                    encrypted = "signature",
                    index = 2,
                ),
            ),
            frames,
        )
    }

    @Test
    fun `signature before text is retained on reasoning completion`() = runTest {
        val frames = transform(
            reasoningSignature("signature", index = 2),
            reasoningText("thinking", index = 2),
            contentBlockStop(index = 2),
        )

        assertEquals("signature", assertIs<StreamFrame.ReasoningComplete>(frames.last()).encrypted)
        assertEquals(listOf("thinking"), assertIs<StreamFrame.ReasoningComplete>(frames.last()).content)
    }

    @Test
    fun `signature-only reasoning completes and replays with empty text`() = runTest {
        val frames = transform(
            reasoningSignature("signature-only", index = 2),
            contentBlockStop(index = 2),
        )
        val message = frames.toMessageResponse()
        val request = BedrockConverseConverters.createConverseRequest(
            Prompt(messages = listOf(message), id = "replay"),
            thinkingModel,
            emptyList(),
        )
        val replay = assertIs<ReasoningContentBlock.ReasoningText>(
            assertIs<ContentBlock.ReasoningContent>(request.messages.orEmpty().single().content.single()).value
        ).value

        assertEquals("", replay.text)
        assertEquals("signature-only", replay.signature)
    }

    @Test
    fun `reasoning completes before tool frames and round-trips exactly`() = runTest {
        val frames = transform(
            reasoningText("thinking", index = 0),
            reasoningSignature("signature", index = 0),
            contentBlockStop(index = 0),
            toolStart(id = "tool-1", name = "lookup", index = 1),
            toolDelta("{\"query\":\"koog\"}", index = 1),
            contentBlockStop(index = 1),
        )

        assertEquals(
            listOf(
                StreamFrame.ReasoningDelta(text = "thinking", index = 0),
                StreamFrame.ReasoningComplete(null, listOf("thinking"), encrypted = "signature", index = 0),
                StreamFrame.ToolCallDelta("tool-1", "lookup", null, index = 1),
                StreamFrame.ToolCallDelta(null, null, "{\"query\":\"koog\"}", index = 1),
                StreamFrame.ToolCallComplete("tool-1", "lookup", "{\"query\":\"koog\"}", index = 1),
            ),
            frames,
        )

        val request = BedrockConverseConverters.createConverseRequest(
            Prompt(messages = listOf(frames.toMessageResponse()), id = "round-trip"),
            thinkingModel,
            emptyList(),
        )
        val replayBlocks = request.messages.orEmpty().single().content
        val reasoning = assertIs<ReasoningContentBlock.ReasoningText>(
            assertIs<ContentBlock.ReasoningContent>(replayBlocks[0]).value
        ).value
        val tool = assertIs<ContentBlock.ToolUse>(replayBlocks[1]).value

        assertEquals("thinking", reasoning.text)
        assertEquals("signature", reasoning.signature)
        assertEquals("tool-1", tool.toolUseId)
        assertEquals("lookup", tool.name)
        assertEquals(
            Json.parseToJsonElement("""{"query":"koog"}""").jsonObject,
            JsonDocumentConverters.convertToJsonElement(tool.input),
        )
    }

    @Test
    fun `redacted reasoning produces a clear client exception`() = runTest {
        val streamError = assertFailsWith<LLMClientException> {
            transform(
                reasoningDelta(ReasoningContentBlockDelta.RedactedContent(byteArrayOf(1)), index = 0),
            )
        }
        assertTrue(streamError.message.orEmpty().contains("Redacted reasoning content"))

        val responseError = assertFailsWith<LLMClientException> {
            BedrockConverseConverters.convertConverseResponse(
                ConverseResponse {
                    output = ConverseOutput.Message(
                        BedrockMessage {
                            role = ConversationRole.Assistant
                            content = listOf(
                                ContentBlock.ReasoningContent(
                                    ReasoningContentBlock.RedactedContent(byteArrayOf(1))
                                )
                            )
                        }
                    )
                    stopReason = StopReason.EndTurn
                },
                clock = ai.koog.utils.time.KoogClock.System,
            )
        }
        assertTrue(responseError.message.orEmpty().contains("Redacted reasoning content"))
    }

    @Test
    fun `reasoning streaming preserves usage metadata`() = runTest {
        val frames = transform(
            reasoningText("thinking", index = 0),
            contentBlockStop(index = 0),
            ConverseStreamOutput.MessageStop(MessageStopEvent { stopReason = StopReason.EndTurn }),
            ConverseStreamOutput.Metadata(
                ConverseStreamMetadataEvent {
                    usage = TokenUsage {
                        inputTokens = 7
                        outputTokens = 3
                        totalTokens = 10
                    }
                }
            ),
        )
        val end = assertIs<StreamFrame.End>(frames.last())

        assertEquals("end_turn", end.finishReason)
        assertEquals(7, end.metaInfo.inputTokensCount)
        assertEquals(3, end.metaInfo.outputTokensCount)
        assertEquals(10, end.metaInfo.totalTokensCount)
    }

    private fun prompt(params: BedrockConverseParams): Prompt = Prompt.build("test", params) {
        user("hello")
    }

    private suspend fun transform(vararg chunks: ConverseStreamOutput): List<StreamFrame> =
        BedrockConverseConverters.transformConverseStreamChunks(flowOf(*chunks)).toList()

    private fun reasoningText(text: String, index: Int): ConverseStreamOutput =
        reasoningDelta(ReasoningContentBlockDelta.Text(text), index)

    private fun reasoningSignature(signature: String, index: Int): ConverseStreamOutput =
        reasoningDelta(ReasoningContentBlockDelta.Signature(signature), index)

    private fun reasoningDelta(delta: ReasoningContentBlockDelta, index: Int): ConverseStreamOutput =
        ConverseStreamOutput.ContentBlockDelta(
            ContentBlockDeltaEvent {
                this.delta = ContentBlockDelta.ReasoningContent(delta)
                contentBlockIndex = index
            }
        )

    private fun contentBlockStop(index: Int): ConverseStreamOutput =
        ConverseStreamOutput.ContentBlockStop(
            ContentBlockStopEvent { contentBlockIndex = index }
        )

    private fun toolStart(id: String, name: String, index: Int): ConverseStreamOutput =
        ConverseStreamOutput.ContentBlockStart(
            ContentBlockStartEvent {
                start = ContentBlockStart.ToolUse(
                    ToolUseBlockStart {
                        toolUseId = id
                        this.name = name
                    }
                )
                contentBlockIndex = index
            }
        )

    private fun toolDelta(input: String, index: Int): ConverseStreamOutput =
        ConverseStreamOutput.ContentBlockDelta(
            ContentBlockDeltaEvent {
                delta = ContentBlockDelta.ToolUse(ToolUseBlockDelta { this.input = input })
                contentBlockIndex = index
            }
        )

    private companion object {
        val adaptiveThinking = BedrockThinkingConfig.Adaptive(
            effort = BedrockReasoningEffort.HIGH,
            display = BedrockThinkingDisplay.SUMMARIZED,
        )
        val thinkingModel = LLModel(
            provider = LLMProvider.Bedrock,
            id = "test.bedrock.reasoning",
            capabilities = listOf(LLMCapability.Completion, LLMCapability.Thinking, LLMCapability.Tools),
            contextLength = 200_000,
        )
    }
}
