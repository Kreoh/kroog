package ai.koog.prompt.executor.clients.bedrock

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.ImageInputException
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicVertexClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicVertexLLMClient
import ai.koog.prompt.executor.clients.bedrock.converse.BedrockConverseConverters
import ai.koog.prompt.executor.clients.bedrock.modelfamilies.anthropic.BedrockAnthropicClaudeSerialization
import ai.koog.prompt.executor.clients.bedrock.util.JsonDocumentConverters
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
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
import aws.sdk.kotlin.services.bedrockruntime.model.ImageSource
import aws.sdk.kotlin.services.bedrockruntime.model.ReasoningContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ReasoningTextBlock
import aws.sdk.kotlin.services.bedrockruntime.model.StopReason
import aws.sdk.kotlin.services.bedrockruntime.model.SystemContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ToolResultContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ToolResultStatus
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
    fun testForeignToolIdsRemainPairedAcrossClaudeWireFormats() = runTest {
        // These two foreign IDs collide under String.hashCode. A later native ID also reserves their first alias.
        val foreign = listOf("functions.lookup:Aa", "functions.lookup:BB")
        val reserved = "toolu_replay_${foreign.first().hashCode().toUInt().toString(16)}"
        val ids = foreign + reserved
        val calls = ids.mapIndexed { index, id -> MessagePart.Tool.Call(id, "lookup", """{"label":"label$index"}""") }
        for (failed in foreign) {
            val results = calls.reversed().map { MessagePart.Tool.Result(it.id, "lookup", "value for ${it.id}", isError = it.id == failed) }
            val prompt = Prompt(listOf(Message.Assistant(calls, ResponseMetaInfo.Empty), Message.User(results, RequestMetaInfo.Empty)), "foreign-ids")

            val vertex = CapturingAnthropicTransport()
            vertexClient(vertex).execute(prompt, anthropicModel)
            val invoke = BedrockAnthropicClaudeSerialization.createAnthropicRequest(prompt, bedrockModel, emptyList())
            val requests = listOf(vertex.requestBody(), bedrockJson.encodeToJsonElement(invoke).jsonObject)
            val converse = BedrockConverseConverters.createConverseRequest(prompt, bedrockModel, emptyList())
            val converseCalls = converse.messages.orEmpty().first().content.filterIsInstance<ContentBlock.ToolUse>().map { it.value.toolUseId }
            val converseOutputs = converse.messages.orEmpty().last().content.filterIsInstance<ContentBlock.ToolResult>().map { it.value }
            val converseResults = converseOutputs.map { it.toolUseId }
            assertEquals(results.map { it.output }, converseOutputs.map {
                assertIs<ToolResultContentBlock.Text>(it.content.single()).value
            })
            assertEquals(results.map { it.isError }, converseOutputs.map { it.status == ToolResultStatus.Error })
            assertEquals(3, converseCalls.distinct().size)
            assertEquals(reserved, converseCalls.last())
            assertTrue(converseCalls.all { it != null && it.matches(Regex("[a-zA-Z0-9_-]{1,64}")) })
            assertEquals(converseCalls.reversed(), converseResults)
            requests.forEach { request ->
                val turns = request.getValue("messages").jsonArray
                val callBlocks = turns.first().jsonObject.getValue("content").jsonArray.map { it.jsonObject }
                val resultBlocks = turns.last().jsonObject.getValue("content").jsonArray.map { it.jsonObject }
                assertEquals(converseCalls, callBlocks.map { it.getValue("id").jsonPrimitive.content })
                assertEquals(listOf("label0", "label1", "label2"), callBlocks.map {
                    it.getValue("input").jsonObject.getValue("label").jsonPrimitive.content
                })
                assertEquals(results.map { it.isError.toString() }, resultBlocks.map { it.getValue("is_error").jsonPrimitive.content })
                assertEquals(converseResults, resultBlocks.map { it.getValue("tool_use_id").jsonPrimitive.content })
                assertEquals(results.map { it.output }, resultBlocks.map {
                    it.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
                })
            }
            assertEquals(ids, (prompt.messages.first() as Message.Assistant).parts.filterIsInstance<MessagePart.Tool.Call>().map { it.id })
            assertEquals(ids.reversed(), (prompt.messages.last() as Message.User).parts.filterIsInstance<MessagePart.Tool.Result>().map { it.id })
        }
    }

    @Test
    fun testErrorStatusUsesKnownFamilyOrConfiguredOpaqueProfileFallback() {
        val id = "functions.lookup:0"
        val prompt = Prompt(listOf(
            Message.Assistant(MessagePart.Tool.Call(id, "lookup", "{}"), ResponseMetaInfo.Empty),
            Message.User(MessagePart.Tool.Result(id, "lookup", "failed", isError = true), RequestMetaInfo.Empty),
        ), "family")
        fun converted(modelId: String, fallback: BedrockModelFamilies? = null): Pair<String?, ToolResultStatus?> {
            val model = bedrockModel.copy(id = modelId)
            val request = BedrockConverseConverters.createConverseRequest(prompt, model, emptyList(), fallbackModelFamily = fallback)
            val streaming = BedrockConverseConverters.createConverseStreamRequest(prompt, model, emptyList(), fallbackModelFamily = fallback)
            assertEquals(request.messages, streaming.messages)
            val output = request.messages.orEmpty().last().content.filterIsInstance<ContentBlock.ToolResult>().single().value
            return output.toolUseId to output.status
        }
        val claude = converted("global.anthropic.claude-sonnet-5-5")
        assertTrue(claude.first != id)
        assertEquals(ToolResultStatus.Error, claude.second)
        assertEquals(claude, converted("opaque-profile", BedrockModelFamilies.AnthropicClaude))
        assertEquals(id to ToolResultStatus.Error, converted("amazon.nova-lite-v1:0"))
        assertEquals(id to null, converted("opaque-profile"))
        assertEquals(id to null, converted("openai.gpt-6-astra", BedrockModelFamilies.AnthropicClaude))
    }

    @Test
    fun testUnambiguousMissingToolIdIsRecoveredInRequestOnly() = runTest {
        fun pair(callId: String?, resultId: String?, resultTool: String = "lookup") = Prompt(listOf(
            Message.Assistant(MessagePart.Tool.Call(callId, "lookup", "{}"), ResponseMetaInfo.Empty),
            Message.User(MessagePart.Tool.Result(resultId, resultTool, "result"), RequestMetaInfo.Empty),
        ), "missing-ids")
        for ((callId, resultId) in listOf(null to null, "known" to null, null to "known")) {
            val prompt = pair(callId, resultId)
            val vertex = CapturingAnthropicTransport()
            vertexClient(vertex).execute(prompt, anthropicModel)
            val turns = vertex.requestBody().getValue("messages").jsonArray
            val call = turns.first().jsonObject.getValue("content").jsonArray.first().jsonObject
            val result = turns.last().jsonObject.getValue("content").jsonArray.first().jsonObject
            val id = call.getValue("id").jsonPrimitive.content
            assertEquals(callId ?: resultId ?: "toolu_replay_turn_0", id)
            assertEquals(id, result.getValue("tool_use_id").jsonPrimitive.content)
            assertEquals(callId, ((prompt.messages.first() as Message.Assistant).parts.single() as MessagePart.Tool.Call).id)
            assertEquals(resultId, ((prompt.messages.last() as Message.User).parts.single() as MessagePart.Tool.Result).id)
        }

        val original = pair(null, null)
        val assistant = original.messages.first() as Message.Assistant
        val user = original.messages.last() as Message.User
        for (ambiguous in listOf(
            pair(null, null, resultTool = "different"),
            original.copy(messages = listOf(assistant.copy(parts = assistant.parts + assistant.parts), user)),
            original.copy(messages = listOf(assistant, user, user)),
            original.copy(messages = listOf(assistant)),
        )) {
            assertFailsWith<IllegalArgumentException> {
                BedrockConverseConverters.createConverseRequest(ambiguous, bedrockModel, emptyList())
            }
        }
    }

    @Test
    fun testConverseHonoursClaudeIdLengthAndPreservesOtherModelsIds() {
        val longId = "a".repeat(65)
        val dotted = "native.tool:0"
        val calls = listOf(longId, dotted).map { MessagePart.Tool.Call(it, "lookup", "{}") }
        val prompt = Prompt(listOf(Message.Assistant(calls, ResponseMetaInfo.Empty)), "converse-ids")

        fun callIds(model: ai.koog.prompt.llm.LLModel) =
            BedrockConverseConverters.createConverseRequest(prompt, model, emptyList())
                .messages.orEmpty().single().content.filterIsInstance<ContentBlock.ToolUse>().map { it.value.toolUseId }

        assertTrue(callIds(bedrockModel).all { it != null && it.matches(Regex("[A-Za-z0-9_-]{1,64}")) })
        assertEquals(dotted, callIds(BedrockModels.OpenAIGpt6Astra).last())
    }

    @Test
    fun testVertexParallelCallsAndResultMessageBoundariesInConverse() = runTest {
        val transport = CapturingAnthropicTransport(response = """
            {
              "id":"vertex-turn","type":"message","role":"assistant","model":"fixture",
              "stop_reason":"tool_use","usage":{"input_tokens":5,"output_tokens":12},
              "content":[
                {"type":"tool_use","id":"call-left","name":"lookup","input":{"label":"left"}},
                {"type":"tool_use","id":"call-right","name":"lookup","input":{"label":"right"}}
              ]
            }
        """.trimIndent())
        val source = userPrompt("parallel-replay")
        val calls = vertexClient(transport).execute(source, anthropicModel)
        val results = listOf(
            MessagePart.Tool.Result(id = "call-left", tool = "lookup", output = "A71"),
            MessagePart.Tool.Result(id = "call-right", tool = "lookup", output = "B28"),
        )

        fun converse(resultTurns: List<Message.User>) = BedrockConverseConverters.createConverseRequest(
            Prompt(messages = source.messages + calls + resultTurns, id = source.id),
            bedrockModel,
            emptyList(),
        ).messages.orEmpty()

        val grouped = converse(listOf(Message.User(parts = results, metaInfo = RequestMetaInfo.Empty)))
        assertEquals(listOf(ConversationRole.User, ConversationRole.Assistant, ConversationRole.User), grouped.map { it.role })
        assertEquals(listOf("call-left", "call-right"), grouped[1].content.filterIsInstance<ContentBlock.ToolUse>().map { it.value.toolUseId })
        assertEquals(listOf("call-left" to "A71", "call-right" to "B28"), grouped[2].content.filterIsInstance<ContentBlock.ToolResult>().map {
            val result = it.value
            result.toolUseId to assertIs<ToolResultContentBlock.Text>(result.content.single()).value
        })

        val split = converse(results.map { Message.User(part = it, metaInfo = RequestMetaInfo.Empty) })
        assertEquals(grouped, split)
        val reversed = results.reversed().map { Message.User(part = it, metaInfo = RequestMetaInfo.Empty) }
        val groupedReversed = converse(listOf(Message.User(parts = results.reversed(), metaInfo = RequestMetaInfo.Empty)))
        assertEquals(groupedReversed, converse(reversed))

        fun system(text: String) = Message.System(text, RequestMetaInfo.Empty)
        val interleaved = BedrockConverseConverters.createConverseRequest(
            Prompt(messages = source.messages + calls + system("first") + reversed.first() +
                system("second") + reversed.last(), id = source.id),
            bedrockModel,
            emptyList(),
        )
        assertEquals(groupedReversed, interleaved.messages)
        assertEquals(listOf("first", "second"), interleaved.system.orEmpty().filterIsInstance<SystemContentBlock.Text>().map { it.value })

        val nextCalls = calls.copy(parts = calls.parts.map {
            val call = assertIs<MessagePart.Tool.Call>(it)
            call.copy(id = "next-${call.id}")
        })
        val nextResults = results.map { it.copy(id = "next-${it.id}") }
        fun twoBatches(first: List<Message.User>, second: List<Message.User>) =
            BedrockConverseConverters.createConverseRequest(
                Prompt(messages = source.messages + calls + first + nextCalls + second, id = source.id),
                bedrockModel,
                emptyList(),
            ).messages.orEmpty()

        val groupedBatches = twoBatches(
            listOf(Message.User(parts = results.reversed(), metaInfo = RequestMetaInfo.Empty)),
            listOf(Message.User(parts = nextResults, metaInfo = RequestMetaInfo.Empty)),
        )
        assertEquals(5, groupedBatches.size)
        assertEquals(groupedBatches, twoBatches(reversed, nextResults.map { Message.User(part = it, metaInfo = RequestMetaInfo.Empty) }))
    }

    @Test
    fun testClaudeConverseValidatesRequestImagesIncludingNestedToolResults() {
        val image = MessagePart.Attachment(AttachmentSource.Image(AttachmentContent.Binary.Bytes(byteArrayOf(1)), "png"))
        for (limit in listOf(100, 600)) {
            val model = bedrockModel.copy(contextLength = if (limit == 100) 200_000L else 1_000_000L)
            val original = Message.User(List(limit) { image }, RequestMetaInfo.Empty)
            val result = Message.User(MessagePart.Tool.Result("call", "snapshot", listOf(image)), RequestMetaInfo.Empty)
            BedrockConverseConverters.createConverseRequest(Prompt(listOf(original), "boundary"), model, emptyList())
            assertFailsWith<ImageInputException> {
                BedrockConverseConverters.createConverseStreamRequest(Prompt(listOf(original, result), "over-limit"), model, emptyList())
            }
        }
        val large = MessagePart.Attachment(AttachmentSource.Image(AttachmentContent.Binary.Bytes(ByteArray(3_932_161)), "png"))
        assertFailsWith<ImageInputException> {
            BedrockConverseConverters.createConverseRequest(Prompt(listOf(Message.User(large, RequestMetaInfo.Empty)), "large"), bedrockModel, emptyList())
        }
    }

    @Test
    fun testToolResultImageKeepsItsBytesOnBedrockClaude() {
        val bytes = byteArrayOf(11, 12, 13)
        val image = MessagePart.Attachment(AttachmentSource.Image(AttachmentContent.Binary.Bytes(bytes), "png"))
        val call = Message.Assistant(MessagePart.Tool.Call("call", "lookup", "{}"), ResponseMetaInfo.Empty)
        val result = Message.User(MessagePart.Tool.Result("call", "lookup", listOf(image)), RequestMetaInfo.Empty)
        val request = BedrockConverseConverters.createConverseRequest(Prompt(listOf(call, result), "tool-image"), bedrockModel, emptyList())
        val sent = request.messages.orEmpty().last().content.filterIsInstance<ContentBlock.ToolResult>().single().value
        assertEquals("call", sent.toolUseId)
        val png = assertIs<ToolResultContentBlock.Image>(sent.content.single()).value
        assertContentEquals(bytes, assertIs<ImageSource.Bytes>(png.source).value)
    }

    @Test
    fun testConverseDistinguishesFailedAndSuccessfulToolResultsWithIdenticalOutput() {
        val ids = listOf("first", "second")
        fun statuses(failedId: String): List<ToolResultStatus?> {
            val prompt = Prompt(
                id = "tool-result-status",
                messages = listOf(
                    Message.Assistant(
                        parts = ids.map { MessagePart.Tool.Call(it, "lookup", "{}") },
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                    Message.User(
                        parts = listOf(
                            MessagePart.Tool.Result("first", "lookup", "receipt", isError = failedId == "first"),
                            MessagePart.Tool.Result("second", "lookup", "receipt", isError = failedId == "second"),
                        ),
                        metaInfo = RequestMetaInfo.Empty,
                    ),
                ),
            )
            val model = bedrockModel.copy(id = "arn:aws:bedrock:us-west-2:123456789012:application-inference-profile/opaque")
            val request = BedrockConverseConverters.createConverseRequest(
                prompt, model, emptyList(), fallbackModelFamily = BedrockModelFamilies.AnthropicClaude,
            )
            val results = request.messages.orEmpty().last().content.filterIsInstance<ContentBlock.ToolResult>()
                .associate { it.value.toolUseId to it.value }
            assertEquals(results.getValue("first").content, results.getValue("second").content)
            return ids.map { results.getValue(it).status }
        }
        assertEquals(listOf(null, ToolResultStatus.Error), statuses("second"))
        assertEquals(listOf(ToolResultStatus.Error, null), statuses("first"))
    }

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
private val bedrockModel = BedrockModels.AnthropicClaudeFable5
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
