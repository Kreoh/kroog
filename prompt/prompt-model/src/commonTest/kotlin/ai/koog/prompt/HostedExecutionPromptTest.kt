package ai.koog.prompt

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.StreamFrameMergeIdentity
import ai.koog.prompt.streaming.buildStreamFrameFlow
import ai.koog.prompt.streaming.mergeIdentity
import ai.koog.prompt.streaming.toMessageResponse
import ai.koog.prompt.streaming.toStreamFrames
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

internal class HostedExecutionPromptTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun testPromptContentVariantsRoundTripThroughSerializationAndStreaming() {
        val citation = MessagePart.GeneratedFileCitation(providerFileId = "file-partial")
        val generatedFile = MessagePart.GeneratedFile(
            providerFileId = "file-complete",
            containerId = "container-1",
            filename = "chart.csv",
            mediaType = "text/csv",
            sizeBytes = 42,
            producingExecutionId = "execution-1",
            providerItemId = "file-item-1",
        )
        val response = Message.Assistant(
            parts = listOf(
                MessagePart.Text(
                    text = "See the generated file",
                    providerItemId = "text-item-1",
                    generatedFileCitations = listOf(citation),
                ),
                MessagePart.Reasoning(
                    content = listOf("visible reasoning"),
                    providerItemId = "reasoning-item-1",
                    replay = listOf(
                        MessagePart.ReasoningReplay.Signed("signed reasoning", "signature-raw"),
                        MessagePart.ReasoningReplay.OpaqueRedacted("opaque:\u0000:payload"),
                    ),
                ),
                MessagePart.Tool.Call(
                    id = "call-1",
                    tool = "lookup",
                    args = "{}",
                    providerItemId = "function-item-1",
                ),
                MessagePart.HostedExecution.Request(
                    code = "print('hello')",
                    executionId = "execution-1",
                    providerItemId = "execution-request-item",
                ),
                MessagePart.HostedExecution.Progress(
                    message = "running",
                    sequence = 1,
                    executionId = "execution-1",
                    providerItemId = "execution-progress-item",
                ),
                MessagePart.HostedExecution.CumulativeOutput(
                    output = "hello\n",
                    sequence = 2,
                    executionId = "execution-1",
                    providerItemId = "execution-output-item",
                ),
                MessagePart.HostedExecution.Result(
                    output = "hello\n",
                    exitCode = 0,
                    generatedFiles = listOf(generatedFile),
                    executionId = "execution-1",
                    providerItemId = "execution-result-item",
                ),
                MessagePart.HostedExecution.Error(
                    message = "provider error",
                    code = "execution_failed",
                    executionId = "execution-2",
                    providerItemId = "execution-error-item",
                ),
                generatedFile,
            ),
            metaInfo = ResponseMetaInfo.Empty,
            id = "ui-message-1",
        )

        val serialized = json.encodeToString(Message.serializer(), response)
        val decoded = json.decodeFromString(Message.serializer(), serialized)
        val streamed = response.toStreamFrames().toMessageResponse()

        assertEquals(response, decoded)
        assertEquals(response, streamed)
        val decodedReasoning = assertIs<MessagePart.Reasoning>((decoded as Message.Assistant).parts[1])
        assertEquals("opaque:\u0000:payload", (decodedReasoning.replay[1] as MessagePart.ReasoningReplay.OpaqueRedacted).data)
        assertNull((response.parts[3] as MessagePart.HostedExecution.Request).containerId)
        assertEquals(citation, (streamed.parts[0] as MessagePart.Text).generatedFileCitations.single())
    }

    @Test
    fun testPromptFunctionResultRoundTripsWithSeparateCallAndProviderIdentities() {
        val request = Message.User(
            part = MessagePart.Tool.Result(
                id = "call-1",
                tool = "lookup",
                output = "result",
                providerItemId = "result-item-1",
            ),
            metaInfo = RequestMetaInfo.Empty,
            id = "ui-result-1",
        )

        val decoded = json.decodeFromString(Message.serializer(), json.encodeToString(Message.serializer(), request))
        val result = assertIs<MessagePart.Tool.Result>((decoded as Message.User).parts.single())

        assertEquals("ui-result-1", decoded.id)
        assertEquals("call-1", result.callId)
        assertEquals("result-item-1", result.providerItemId)
    }

    @Test
    fun testPromptThreeIdentitiesSurviveCopySerializationAndStreamMerge() {
        val call = MessagePart.Tool.Call(
            id = "call-identity",
            tool = "lookup",
            args = "{}",
            providerItemId = "provider-item-identity",
        )
        val original = Message.Assistant(call, ResponseMetaInfo.Empty, id = "ui-item-identity")
        val copied = original.copy(parts = listOf(call.copy(args = "{\"q\":1}")))
        val decoded = json.decodeFromString(Message.serializer(), json.encodeToString(Message.serializer(), copied))
        val merged = (decoded as Message.Assistant).toStreamFrames().toMessageResponse()
        val mergedCall = assertIs<MessagePart.Tool.Call>(merged.parts.single())

        assertEquals("ui-item-identity", merged.id)
        assertEquals("call-identity", mergedCall.callId)
        assertEquals("provider-item-identity", mergedCall.providerItemId)
    }

    @Test
    fun testPromptStreamMergePrefersProviderIdentityAndKeepsInternalFallbackEphemeral() {
        val providerFrame = StreamFrame.ToolCallDelta(
            id = "same-call",
            name = "first",
            content = "{}",
            index = 7,
            providerItemId = "provider-item-7",
        )
        val internalFrame = StreamFrame.TextComplete(text = "fallback", index = 8)

        assertEquals(
            StreamFrameMergeIdentity.ProviderItem("provider-item-7"),
            providerFrame.mergeIdentity(),
        )
        assertEquals(
            StreamFrameMergeIdentity.Internal("text-index", "8"),
            internalFrame.mergeIdentity(),
        )
        assertNull(listOf(internalFrame).toMessageResponse().parts.single().let { it as MessagePart.Text }.providerItemId)
    }

    @Test
    fun testHostedExecutionStreamAccumulatorSeparatesEqualCallIdsByProviderItem() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(
                id = "shared-call",
                name = "first",
                args = "{\"a\":",
                index = 0,
                providerItemId = "provider-first",
            )
            emitToolCallDelta(
                id = "shared-call",
                name = "second",
                args = "{\"b\":",
                index = 1,
                providerItemId = "provider-second",
            )
            emitToolCallDelta(
                id = "shared-call",
                args = "1}",
                index = 0,
                providerItemId = "provider-first",
            )
            emitToolCallDelta(
                id = "shared-call",
                args = "2}",
                index = 1,
                providerItemId = "provider-second",
            )
            emitEnd()
        }.toList()

        val calls = frames.filterIsInstance<StreamFrame.ToolCallComplete>()
        assertEquals(listOf("provider-first", "provider-second"), calls.map { it.providerItemId })
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), calls.map { it.content })
        assertEquals(listOf("shared-call", "shared-call"), calls.map { it.id })
    }

    @Test
    fun testStreamAccumulatorPromotesUniqueCallIdFallbackWhenProviderIdentityArrivesLate() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(id = "call-late", name = "lookup", args = "{\"q\":")
            emitToolCallDelta(id = "call-late", args = "1}", providerItemId = "provider-late")
            emitEnd()
        }.toList()

        assertEquals(
            listOf(
                StreamFrame.ToolCallDelta("call-late", "lookup", "{\"q\":"),
                StreamFrame.ToolCallDelta("call-late", null, "1}", providerItemId = "provider-late"),
                StreamFrame.ToolCallComplete(
                    id = "call-late",
                    name = "lookup",
                    content = "{\"q\":1}",
                    providerItemId = "provider-late",
                ),
                StreamFrame.End(),
            ),
            frames,
        )
    }

    @Test
    fun testStreamAccumulatorAttachesLosslessReasoningReplay() = runTest {
        val signed = MessagePart.ReasoningReplay.Signed("private thought", "raw-signature")
        val redacted = MessagePart.ReasoningReplay.OpaqueRedacted("opaque:\u0000:payload")

        val frames = buildStreamFrameFlow {
            emitReasoningDelta(id = "reasoning-local", text = "summary", providerItemId = "reasoning-provider")
            attachReasoningReplay(signed, id = "reasoning-local", providerItemId = "reasoning-provider")
            attachReasoningReplay(redacted, id = "reasoning-local", providerItemId = "reasoning-provider")
            emitEnd()
        }.toList()

        assertEquals(
            listOf(
                StreamFrame.ReasoningDelta(
                    id = "reasoning-local",
                    text = "summary",
                    providerItemId = "reasoning-provider",
                ),
                StreamFrame.ReasoningComplete(
                    id = "reasoning-local",
                    content = listOf("summary"),
                    providerItemId = "reasoning-provider",
                    replay = listOf(signed, redacted),
                ),
                StreamFrame.End(),
            ),
            frames,
        )
    }

    @Test
    fun testStreamAccumulatorEmitsHostedExecutionAndGeneratedFilesInOrder() = runTest {
        val execution = MessagePart.HostedExecution.CumulativeOutput(
            output = "complete output",
            sequence = 2,
            executionId = "execution-1",
            providerItemId = "execution-provider",
        )
        val file = MessagePart.GeneratedFile(
            providerFileId = "file-1",
            filename = "result.csv",
            producingExecutionId = "execution-1",
            providerItemId = "file-provider",
        )

        val frames = buildStreamFrameFlow {
            emitTextDelta("before", providerItemId = "text-provider")
            emitHostedExecutionUpdate(execution, index = 1)
            emitGeneratedFile(file, index = 2)
            emitEnd()
        }.toList()

        assertEquals(
            listOf(
                StreamFrame.TextDelta("before", providerItemId = "text-provider"),
                StreamFrame.TextComplete("before", providerItemId = "text-provider"),
                StreamFrame.HostedExecutionUpdate(execution, index = 1),
                StreamFrame.GeneratedFileComplete(file, index = 2),
                StreamFrame.End(),
            ),
            frames,
        )
    }

    @Test
    fun testLegacySerialisedModelsDecodeWhenEveryNewDefaultedFieldIsOmitted() {
        val legacyMessages = listOf<Message>(
            Message.Assistant(
                parts = listOf(
                    MessagePart.Text("legacy text"),
                    MessagePart.Reasoning(content = listOf("legacy reasoning"), id = "reasoning-1"),
                    MessagePart.Tool.Call(id = "call-1", tool = "lookup", args = "{}"),
                    MessagePart.CodeExecution(
                        id = "execution-1",
                        code = "print(1)",
                        containerId = "container-1",
                    ),
                ),
                metaInfo = ResponseMetaInfo.Empty,
            ),
            Message.User(
                part = MessagePart.Tool.Result(id = "call-1", tool = "lookup", output = "legacy result"),
                metaInfo = RequestMetaInfo.Empty,
            ),
        )
        val legacyFrames = listOf<StreamFrame>(
            StreamFrame.TextDelta("legacy"),
            StreamFrame.TextComplete("legacy"),
            StreamFrame.ReasoningDelta(id = "reasoning-1", text = "legacy reasoning"),
            StreamFrame.ReasoningComplete(id = "reasoning-1", content = listOf("legacy reasoning")),
            StreamFrame.ToolCallDelta("call-1", "lookup", "{}"),
            StreamFrame.ToolCallComplete("call-1", "lookup", "{}"),
            StreamFrame.CodeExecutionStart("execution-1", "container-1"),
            StreamFrame.CodeExecutionCodeDelta("execution-1", "container-1", "print(1)"),
            StreamFrame.CodeExecutionOutput(
                "execution-1",
                "container-1",
                MessagePart.CodeExecution.Output.Logs("1"),
            ),
            StreamFrame.CodeExecutionFailure(
                "execution-1",
                "container-1",
                MessagePart.CodeExecution.Failure.FAILED,
            ),
            StreamFrame.CodeExecutionComplete("execution-1", "print(1)", "container-1", emptyList()),
            StreamFrame.End(),
        )
        val legacyJson = Json { encodeDefaults = false }
        val messagesPayload = legacyJson.encodeToString(ListSerializer(Message.serializer()), legacyMessages)
        val framesPayload = legacyJson.encodeToString(ListSerializer(StreamFrame.serializer()), legacyFrames)

        listOf(messagesPayload, framesPayload).forEach { payload ->
            assertFalse(payload.contains("providerItemId"))
            assertFalse(payload.contains("generatedFileCitations"))
            assertFalse(payload.contains("replay"))
            assertFalse(payload.contains("messageId"))
        }
        assertContains(messagesPayload, "legacy text")
        assertContains(framesPayload, "legacy reasoning")
        assertEquals(legacyMessages, legacyJson.decodeFromString(ListSerializer(Message.serializer()), messagesPayload))
        assertEquals(legacyFrames, legacyJson.decodeFromString(ListSerializer(StreamFrame.serializer()), framesPayload))
    }
}
