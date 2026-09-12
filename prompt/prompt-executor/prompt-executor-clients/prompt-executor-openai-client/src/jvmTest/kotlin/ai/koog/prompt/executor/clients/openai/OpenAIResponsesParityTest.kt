package ai.koog.prompt.executor.clients.openai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.openai.models.Item
import ai.koog.prompt.executor.clients.openai.models.OpenAIAnnotations
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.executor.clients.openai.models.OpenAIInputStatus
import ai.koog.prompt.executor.clients.openai.models.OpenAIResponsesAPIResponse
import ai.koog.prompt.executor.clients.openai.models.OpenAIStreamEvent
import ai.koog.prompt.executor.clients.openai.models.OpenAITextConfig
import ai.koog.prompt.executor.clients.openai.models.OutputContent
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAIResponsesParityTest {
    @Test
    fun testUsageSurvivesCompleteAndIncompleteResponses() = runTest {
        listOf(null, 0, 12).forEach { breakdown ->
            val usage = OpenAIResponsesAPIResponse.Usage(
                inputTokens = 100,
                outputTokens = 40,
                totalTokens = 140,
                inputTokensDetails = breakdown?.let { OpenAIResponsesAPIResponse.Usage.InputTokensDetails(it) },
                outputTokensDetails = breakdown?.let { OpenAIResponsesAPIResponse.Usage.OutputTokensDetails(it) },
            )
            val output = response(usage = usage)
            val transport = ScriptedResponsesTransport(
                postResponses = ArrayDeque(listOf(output)),
                streamAttempts = ArrayDeque(
                    listOf(
                        listOf(OpenAIStreamEvent.ResponseCompleted(output, 1)),
                        listOf(OpenAIStreamEvent.ResponseIncomplete(output, 1)),
                    )
                ),
            )
            val client = OpenAILLMClient(OpenAIClientSettings(), transport)
            val prompt = Prompt.build("usage", params = OpenAIResponsesParams()) { user("Hello") }
            val metadata = listOf(
                client.execute(prompt, OpenAIModels.Chat.GPT4o).metaInfo,
                client.executeStreaming(prompt, OpenAIModels.Chat.GPT4o).toList().filterIsInstance<StreamFrame.End>().single().metaInfo!!,
                client.executeStreaming(prompt, OpenAIModels.Chat.GPT4o).toList().filterIsInstance<StreamFrame.End>().single().metaInfo!!,
            )
            metadata.forEach { meta ->
                assertEquals(100, meta.inputTokensCount)
                assertEquals(40, meta.outputTokensCount)
                assertEquals(140, meta.totalTokensCount)
                assertEquals(breakdown, meta.cacheReadTokensCount)
                assertEquals(breakdown, meta.reasoningTokensCount)
                assertEquals(null, meta.cacheWriteTokensCount)
            }
        }
    }

    @Test
    fun testAstraDefaultResponsesToolCallsAndStreamingAgree() = runTest {
        val toolCall = Item.FunctionToolCall(
            arguments = "{}",
            callId = "call_lookup",
            name = "lookup",
            id = "function_provider",
            status = OpenAIInputStatus.COMPLETED,
        )
        val output = response(listOf(toolCall))
        val transport = ScriptedResponsesTransport(
            postResponses = ArrayDeque(listOf(output)),
            streamAttempts = ArrayDeque(
                listOf(
                    listOf(
                        OpenAIStreamEvent.ResponseOutputItemDone(item = toolCall, outputIndex = 0, sequenceNumber = 1),
                        OpenAIStreamEvent.ResponseCompleted(response = output, sequenceNumber = 2),
                    )
                )
            ),
        )
        val client = OpenAILLMClient(OpenAIClientSettings(), transport)
        val prompt = Prompt.build("astra-tools", params = LLMParams(temperature = 0.4)) { user("Look this up") }
        val tools = listOf(ToolDescriptor("lookup", "Look up the answer"))
        val nonStream = client.execute(prompt, OpenAIModels.Chat.GPT6Astra, tools)
        val streamed = client.executeStreaming(prompt, OpenAIModels.Chat.GPT6Astra, tools).toList().toMessageResponse()
        assertEquals(nonStream.parts, streamed.parts)
        val call = assertIs<MessagePart.Tool.Call>(nonStream.parts.single())
        assertEquals("lookup", call.tool)
        assertEquals("call_lookup", call.id)
        assertEquals(listOf("v1/responses", "v1/responses"), transport.paths)
        transport.requests.forEach { payload ->
            val request = Json.parseToJsonElement(payload).jsonObject
            assertEquals("gpt-6-astra", request.getValue("model").jsonPrimitive.content)
            assertTrue("temperature" !in request)
            val tool = request.getValue("tools").jsonArray.single().jsonObject
            assertEquals("function", tool.getValue("type").jsonPrimitive.content)
            assertEquals("lookup", tool.getValue("name").jsonPrimitive.content)
        }
    }

    @Test
    fun testAstraReplaysToolResultsAndEncryptedReasoning() = runTest {
        val transport = ScriptedResponsesTransport(postResponses = ArrayDeque(listOf(response())))
        val client = OpenAILLMClient(OpenAIClientSettings(), transport)
        val prompt = Prompt(
            messages = listOf(
                Message.Assistant(
                    parts = listOf(
                        MessagePart.Reasoning(content = emptyList(), encrypted = "opaque", providerItemId = "reason_1"),
                        MessagePart.Tool.Call("call_1", "lookup", "{}", providerItemId = "function_1"),
                    ),
                    metaInfo = ResponseMetaInfo.Empty,
                ),
                Message.User(
                    parts = listOf(MessagePart.Tool.Result("call_1", "lookup", "found")),
                    metaInfo = RequestMetaInfo.Empty,
                ),
            ),
            id = "astra-tool-result",
            params = OpenAIResponsesParams(stateless = true, include = listOf(OpenAIInclude.OUTPUT_TEXT_LOGPROBS)),
        )
        val result = client.execute(prompt, OpenAIModels.Chat.GPT6Astra)
        assertEquals("ok", assertIs<MessagePart.Text>(result.parts.single()).text)
        val request = Json.parseToJsonElement(transport.requests.single()).jsonObject
        assertEquals(
            listOf("reasoning.encrypted_content"),
            request.getValue("include").jsonArray.map {
                it.jsonPrimitive.content
            }
        )
        val input = request.getValue("input").jsonArray.map { it.jsonObject }
        assertEquals("opaque", input[0].getValue("encrypted_content").jsonPrimitive.content)
        assertEquals("function_call", input[1].getValue("type").jsonPrimitive.content)
        assertEquals("function_call_output", input[2].getValue("type").jsonPrimitive.content)
        assertEquals("call_1", input[2].getValue("call_id").jsonPrimitive.content)
        assertEquals("found", input[2].getValue("output").jsonPrimitive.content)
    }

    @Test
    fun testStatelessRequestReplaysCompleteTypedHistoryWithProviderIdentities() = runTest {
        val transport = ScriptedResponsesTransport(postResponses = ArrayDeque(listOf(response())))
        val client = OpenAILLMClient(OpenAIClientSettings(), transport)
        val prompt = Prompt(
            messages = listOf(
                Message.Assistant(
                    parts = listOf(
                        MessagePart.Text("prior text", providerItemId = "msg_provider"),
                        MessagePart.Reasoning(
                            content = emptyList(),
                            encrypted = "opaque+bytes==",
                            providerItemId = "reason_provider",
                        ),
                        MessagePart.Tool.Call(
                            id = "call_function",
                            tool = "lookup",
                            args = "{}",
                            providerItemId = "function_provider",
                        ),
                        MessagePart.CodeExecution(
                            id = "execution_local",
                            code = "print(1)",
                            containerId = "container_prior",
                            providerItemId = "code_provider",
                        ),
                        MessagePart.HostedExecution.Result(
                            output = "done",
                            generatedFiles = listOf(
                                MessagePart.GeneratedFile(
                                    providerFileId = "file_nested",
                                    containerId = "container_prior",
                                    filename = "nested.csv",
                                    providerItemId = "nested_file_provider",
                                )
                            ),
                            executionId = "hosted_local",
                            containerId = "container_prior",
                            providerItemId = "hosted_provider",
                        ),
                        MessagePart.GeneratedFile(
                            providerFileId = "file_generated",
                            containerId = "container_prior",
                            filename = "result.csv",
                            providerItemId = "file_provider",
                        ),
                    ),
                    metaInfo = ResponseMetaInfo.Empty,
                ),
                Message.User(
                    parts = listOf(
                        MessagePart.Tool.Result(
                            id = "call_function",
                            tool = "lookup",
                            output = "result",
                            providerItemId = "result_provider",
                        ),
                        MessagePart.Text("next question"),
                    ),
                    metaInfo = RequestMetaInfo.Empty,
                ),
            ),
            id = "stateless-replay",
            params = OpenAIResponsesParams(
                store = false,
                include = listOf(OpenAIInclude.OUTPUT_TEXT_LOGPROBS),
                codeInterpreter = OpenAICodeInterpreterConfig(containerId = "container_active"),
                stateless = true,
            ),
        )

        client.execute(prompt, OpenAIModels.Chat.GPT5_5Pro)

        val request = Json.parseToJsonElement(transport.requests.single()).jsonObject
        assertEquals(false, request.getValue("store").jsonPrimitive.content.toBoolean())
        assertTrue("previous_response_id" !in request)
        assertEquals(
            listOf("message.output_text.logprobs", "reasoning.encrypted_content"),
            request.getValue("include").jsonArray.map { it.jsonPrimitive.content },
        )
        val input = request.getValue("input").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(
                "msg_provider",
                "reason_provider",
                "function_provider",
                "code_provider",
                "hosted_provider",
                "nested_file_provider",
                "file_provider",
                "result_provider",
                null,
            ),
            input.map { it["id"]?.jsonPrimitive?.content },
        )
        assertEquals("call_function", input[2].getValue("call_id").jsonPrimitive.content)
        assertEquals("call_function", input[7].getValue("call_id").jsonPrimitive.content)
        assertEquals("opaque+bytes==", input[1].getValue("encrypted_content").jsonPrimitive.content)
        assertEquals(
            "container_active",
            request.getValue("tools").jsonArray.single().jsonObject
                .getValue("container").jsonPrimitive.content
        )
    }

    @Test
    fun testEncryptedOnlyStreamingAndNonStreamingConvergeLosslessly() = runTest {
        val encrypted = "AAECAwQF+/=="
        val response = response(
            listOf(
                Item.Reasoning(
                    id = "reason_provider",
                    summary = emptyList(),
                    content = null,
                    encryptedContent = encrypted,
                    status = OpenAIInputStatus.COMPLETED,
                ),
                Item.OutputMessage(
                    content = listOf(
                        OutputContent.Text(
                            text = "file ready",
                            annotations = listOf(
                                OpenAIAnnotations.ContainerFileCitation(
                                    containerId = "container_1",
                                    fileId = "file_1",
                                    filename = "result.csv",
                                    startIndex = 0,
                                    endIndex = 10,
                                )
                            ),
                        )
                    ),
                    id = "message_provider",
                    status = OpenAIInputStatus.COMPLETED,
                ),
                Item.CodeInterpreterToolCall(
                    code = "print('ready')",
                    containerId = "container_1",
                    id = "code_provider",
                    outputs = listOf(Item.CodeInterpreterToolCall.Output.Logs("ready\n")),
                    status = OpenAIInputStatus.COMPLETED,
                ),
            )
        )
        val nonStream = OpenAILLMClient(
            OpenAIClientSettings(),
            ScriptedResponsesTransport(postResponses = ArrayDeque(listOf(response))),
        ).execute(prompt(), OpenAIModels.Chat.GPT4o)

        val events = listOf(
            OpenAIStreamEvent.ResponseOutputItemDone(
                item = response.output[0],
                outputIndex = 0,
                sequenceNumber = 1,
            ),
            OpenAIStreamEvent.ResponseOutputTextAnnotationAdded(
                itemId = "message_provider",
                outputIndex = 1,
                contentIndex = 0,
                annotationIndex = 0,
                annotation = Json.parseToJsonElement(
                    """{"type":"container_file_citation","container_id":"container_1","file_id":"file_1","filename":"result.csv","start_index":0,"end_index":10}"""
                ).jsonObject,
                sequenceNumber = 2,
            ),
            OpenAIStreamEvent.ResponseOutputTextDone(
                itemId = "message_provider",
                outputIndex = 1,
                contentIndex = 0,
                text = "file ready",
                sequenceNumber = 3,
            ),
            OpenAIStreamEvent.ResponseOutputItemDone(
                item = response.output[2],
                outputIndex = 2,
                sequenceNumber = 4,
            ),
            OpenAIStreamEvent.ResponseCompleted(response, sequenceNumber = 5),
        )
        val streamed = OpenAILLMClient(
            OpenAIClientSettings(),
            ScriptedResponsesTransport(streamAttempts = ArrayDeque(listOf(events))),
        ).executeStreaming(prompt(), OpenAIModels.Chat.GPT4o).toList().toMessageResponse()

        assertEquals(nonStream.parts, streamed.parts)
        val reasoning = assertIs<MessagePart.Reasoning>(streamed.parts[0])
        assertEquals(encrypted, reasoning.encrypted)
        assertEquals("reason_provider", reasoning.providerItemId)
        val text = assertIs<MessagePart.Text>(streamed.parts[1])
        assertEquals("message_provider", text.providerItemId)
        assertEquals("file_1", text.generatedFileCitations.single().providerFileId)
        assertIs<MessagePart.CodeExecution>(streamed.parts[2])
        assertIs<MessagePart.HostedExecution.Request>(streamed.parts[3])
        assertIs<MessagePart.HostedExecution.Result>(streamed.parts[4])
        assertEquals(5, streamed.parts.size)
    }

    @Test
    fun testCodeInterpreterResponseRoundTripsAsOneReplayItem() = runTest {
        val providerItem = Item.CodeInterpreterToolCall(
            code = "print('round trip')",
            containerId = "container_round_trip",
            id = "code_round_trip",
            outputs = listOf(Item.CodeInterpreterToolCall.Output.Logs("round trip\n")),
            status = OpenAIInputStatus.COMPLETED,
        )
        val transport = ScriptedResponsesTransport(
            postResponses = ArrayDeque(
                listOf(
                    response(listOf(providerItem)),
                    response(),
                )
            ),
        )
        val client = OpenAILLMClient(OpenAIClientSettings(), transport)
        val firstResponse = client.execute(prompt(), OpenAIModels.Chat.GPT4o)

        assertEquals(3, firstResponse.parts.size)
        val canonicalPart = assertIs<MessagePart.CodeExecution>(firstResponse.parts.first())
        assertIs<MessagePart.HostedExecution.Request>(firstResponse.parts[1])
        assertIs<MessagePart.HostedExecution.Result>(firstResponse.parts[2])
        assertEquals("code_round_trip", canonicalPart.providerItemId)

        client.execute(
            Prompt(
                messages = listOf(
                    firstResponse,
                    Message.User("continue", RequestMetaInfo.Empty),
                ),
                id = "responses-round-trip",
                params = OpenAIResponsesParams(store = false, stateless = true),
            ),
            OpenAIModels.Chat.GPT4o,
        )

        val replayInput = Json.parseToJsonElement(transport.requests[1]).jsonObject
            .getValue("input").jsonArray.map { it.jsonObject }
        val replayedCodeItems = replayInput.filter {
            it["type"]?.jsonPrimitive?.content == "code_interpreter_call"
        }
        assertEquals(1, replayedCodeItems.size)
        assertEquals("code_round_trip", replayedCodeItems.single().getValue("id").jsonPrimitive.content)
        assertEquals(
            "print('round trip')",
            replayedCodeItems.single().getValue("code").jsonPrimitive.content,
        )
        assertEquals(
            "round trip\n",
            replayedCodeItems.single().getValue("outputs").jsonArray.single().jsonObject
                .getValue("logs").jsonPrimitive.content,
        )
    }

    @Test
    fun testLegacyAndHostedViewsWithOneProviderIdCanonicaliseToOneReplayItem() = runTest {
        val providerItemId = "code_shared"
        val executionId = "execution:code_shared"
        val transport = ScriptedResponsesTransport(postResponses = ArrayDeque(listOf(response())))
        val client = OpenAILLMClient(OpenAIClientSettings(), transport)

        client.execute(
            Prompt(
                messages = listOf(
                    Message.Assistant(
                        parts = listOf(
                            MessagePart.CodeExecution(
                                id = executionId,
                                code = "print('canonical')",
                                containerId = "container_shared",
                                outputs = listOf(MessagePart.CodeExecution.Output.Logs("canonical\n")),
                                providerItemId = providerItemId,
                            ),
                            MessagePart.HostedExecution.Request(
                                code = "print('canonical')",
                                executionId = executionId,
                                containerId = "container_shared",
                                providerItemId = providerItemId,
                            ),
                            MessagePart.HostedExecution.Result(
                                output = "canonical\n",
                                executionId = executionId,
                                containerId = "container_shared",
                                providerItemId = providerItemId,
                            ),
                        ),
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                    Message.User("continue", RequestMetaInfo.Empty),
                ),
                id = "canonical-code-replay",
                params = OpenAIResponsesParams(store = false, stateless = true),
            ),
            OpenAIModels.Chat.GPT4o,
        )

        val replayedCodeItems = Json.parseToJsonElement(transport.requests.single()).jsonObject
            .getValue("input").jsonArray.map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "code_interpreter_call" }
        assertEquals(1, replayedCodeItems.size)
        assertEquals(providerItemId, replayedCodeItems.single().getValue("id").jsonPrimitive.content)
    }

    @Test
    fun testStaleContainerRetriesOnceBeforeFirstProviderFrame() = runTest {
        val stale = KoogHttpClientException(
            clientName = "fixture",
            statusCode = 404,
            errorBody = staleContainerError(),
        )
        val transport = ScriptedResponsesTransport(
            postResponses = ArrayDeque(listOf(stale, response())),
        )
        val client = OpenAILLMClient(OpenAIClientSettings(), transport)

        val result = client.execute(
            prompt(
                OpenAIResponsesParams(
                    store = false,
                    stateless = true,
                    codeInterpreter = OpenAICodeInterpreterConfig(
                        fileIds = listOf("file_1"),
                        containerId = "stale_container",
                    ),
                )
            ),
            OpenAIModels.Chat.GPT4o,
        )

        assertEquals(2, transport.requests.size)
        val retryTool = Json.parseToJsonElement(transport.requests[1]).jsonObject
            .getValue("tools").jsonArray.single().jsonObject
        assertEquals(
            listOf("file_1"),
            retryTool.getValue("container").jsonObject.getValue("file_ids").jsonArray.map {
                it.jsonPrimitive.content
            },
        )
        assertEquals(
            "stale_container_recovered",
            assertIs<MessagePart.HostedExecution.Progress>(result.parts.first()).message,
        )
        assertEquals(
            "stale-container-recovery:stale_container",
            assertIs<MessagePart.HostedExecution.Progress>(result.parts.first()).executionId,
        )
    }

    @Test
    fun testStaleContainerNeverRetriesAfterFirstProviderFrame() = runTest {
        val failure = KoogHttpClientException(
            clientName = "fixture",
            statusCode = 404,
            errorBody = staleContainerError(),
        )
        val firstFrameThenFailure = listOf<Any>(
            OpenAIStreamEvent.ResponseOutputTextDelta(
                itemId = "message_provider",
                outputIndex = 0,
                contentIndex = 0,
                delta = "partial",
                sequenceNumber = 1,
            ),
            failure,
        )
        val transport = ScriptedResponsesTransport(
            streamAttempts = ArrayDeque(listOf(firstFrameThenFailure)),
        )
        val client = OpenAILLMClient(OpenAIClientSettings(), transport)

        assertFailsWith<LLMClientException> {
            client.executeStreaming(
                prompt(
                    OpenAIResponsesParams(
                        store = false,
                        stateless = true,
                        codeInterpreter = OpenAICodeInterpreterConfig(containerId = "stale_container"),
                    )
                ),
                OpenAIModels.Chat.GPT4o,
            ).toList()
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun testUnrelatedNotFoundDoesNotReconstructContainer() = runTest {
        val unrelated = KoogHttpClientException(
            clientName = "fixture",
            statusCode = 404,
            errorBody =
            """{"error":{"message":"route for stale_container was not found","type":"invalid_request_error","param":"route","code":"container_not_found"}}""",
        )
        val transport = ScriptedResponsesTransport(
            postResponses = ArrayDeque(listOf(unrelated)),
        )
        val client = OpenAILLMClient(OpenAIClientSettings(), transport)

        assertFailsWith<KoogHttpClientException> {
            client.execute(
                prompt(
                    OpenAIResponsesParams(
                        store = false,
                        stateless = true,
                        codeInterpreter = OpenAICodeInterpreterConfig(
                            fileIds = listOf("file_1"),
                            containerId = "stale_container",
                        ),
                    )
                ),
                OpenAIModels.Chat.GPT4o,
            )
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun testStreamingStaleContainerRetriesOnceBeforeFirstProviderFrame() = runTest {
        val stale = KoogHttpClientException(
            clientName = "fixture",
            statusCode = 404,
            errorBody = staleContainerError(),
        )
        val completed = response()
        val transport = ScriptedResponsesTransport(
            streamAttempts = ArrayDeque(
                listOf(
                    listOf(stale),
                    listOf(
                        OpenAIStreamEvent.ResponseOutputTextDone(
                            itemId = "message_provider",
                            outputIndex = 0,
                            contentIndex = 0,
                            text = "ok",
                            sequenceNumber = 1,
                        ),
                        OpenAIStreamEvent.ResponseCompleted(completed, sequenceNumber = 2),
                    ),
                )
            ),
        )
        val client = OpenAILLMClient(OpenAIClientSettings(), transport)

        val frames = client.executeStreaming(
            prompt(
                OpenAIResponsesParams(
                    store = false,
                    stateless = true,
                    codeInterpreter = OpenAICodeInterpreterConfig(containerId = "stale_container"),
                )
            ),
            OpenAIModels.Chat.GPT4o,
        ).toList()

        assertEquals(2, transport.requests.size)
        assertEquals(
            "stale_container_recovered",
            assertIs<StreamFrame.HostedExecutionUpdate>(frames.first()).let {
                assertIs<MessagePart.HostedExecution.Progress>(it.update).message
            },
        )
        assertEquals(
            "stale-container-recovery:stale_container",
            assertIs<StreamFrame.HostedExecutionUpdate>(frames.first()).let {
                assertIs<MessagePart.HostedExecution.Progress>(it.update).executionId
            },
        )
        assertIs<StreamFrame.End>(frames.last())
    }

    @Test
    fun testRecoveredNonStreamingResponseCanBeReplayed() = runTest {
        val transport = ScriptedResponsesTransport(
            postResponses = ArrayDeque(
                listOf(
                    KoogHttpClientException("fixture", 404, staleContainerError()),
                    response(),
                    response(),
                )
            ),
        )
        val client = OpenAILLMClient(OpenAIClientSettings(), transport)
        val recovered = client.execute(
            prompt(
                OpenAIResponsesParams(
                    store = false,
                    stateless = true,
                    codeInterpreter = OpenAICodeInterpreterConfig(containerId = "stale_container"),
                )
            ),
            OpenAIModels.Chat.GPT4o,
        )

        assertLocalRecoveryProgressAvailable(recovered)
        client.execute(replayPrompt(recovered), OpenAIModels.Chat.GPT4o)

        assertEquals(3, transport.requests.size)
        assertLocalRecoveryProgressOmittedFromReplay(transport.requests.last())
    }

    @Test
    fun testRecoveredStreamingResponseCanBeReplayed() = runTest {
        val completed = response()
        val transport = ScriptedResponsesTransport(
            postResponses = ArrayDeque(listOf(response())),
            streamAttempts = ArrayDeque(
                listOf(
                    listOf(KoogHttpClientException("fixture", 404, staleContainerError())),
                    listOf(
                        OpenAIStreamEvent.ResponseOutputTextDone(
                            itemId = "message_provider",
                            outputIndex = 0,
                            contentIndex = 0,
                            text = "ok",
                            sequenceNumber = 1,
                        ),
                        OpenAIStreamEvent.ResponseCompleted(completed, sequenceNumber = 2),
                    ),
                )
            ),
        )
        val client = OpenAILLMClient(OpenAIClientSettings(), transport)
        val recovered = client.executeStreaming(
            prompt(
                OpenAIResponsesParams(
                    store = false,
                    stateless = true,
                    codeInterpreter = OpenAICodeInterpreterConfig(containerId = "stale_container"),
                )
            ),
            OpenAIModels.Chat.GPT4o,
        ).toList().toMessageResponse()

        assertLocalRecoveryProgressAvailable(recovered)
        client.execute(replayPrompt(recovered), OpenAIModels.Chat.GPT4o)

        assertEquals(3, transport.requests.size)
        assertLocalRecoveryProgressOmittedFromReplay(transport.requests.last())
    }

    @Test
    fun testUnavailableContainerReprojectsHistoryBeforeRetry() = runTest {
        for (streaming in listOf(false, true)) {
            for (expired in listOf(false, true)) {
                val failure = if (expired) expiredContainerError() else KoogHttpClientException("fixture", 404, staleContainerError())
                val transport = ScriptedResponsesTransport(
                    postResponses = ArrayDeque(listOf(failure, response())),
                    streamAttempts = ArrayDeque(listOf(listOf(failure), listOf(OpenAIStreamEvent.ResponseCompleted(response(), 1)))),
                )
                val client = OpenAILLMClient(OpenAIClientSettings(), transport)
                var recoveries = 0
                val params = OpenAIResponsesParams(
                    stateless = true,
                    codeInterpreter = OpenAICodeInterpreterConfig(listOf("file_1"), "stale_container"),
                ).withContainerRecovery { retryPrompt, unavailable ->
                    recoveries++
                    assertEquals(1, transport.requests.size)
                    assertEquals("stale_container", unavailable.containerId)
                    assertEquals(
                        if (expired) OpenAIContainerUnavailableReason.Expired else OpenAIContainerUnavailableReason.Missing,
                        unavailable.reason,
                    )
                    val retryParams = assertIs<OpenAIResponsesParams>(retryPrompt.params)
                    assertEquals(OpenAICodeInterpreterConfig(listOf("file_1")), retryParams.codeInterpreter)
                    retryPrompt.withMessages { messages ->
                        messages.map { message ->
                            if (message is Message.Assistant) Message.Assistant("Historical code: print(1). Old workspace unavailable.", message.metaInfo) else message
                        }
                    }.copy(params = OpenAIResponsesParams())
                }
                val original = Prompt.build("history", params = params) {
                    user("Earlier question")
                    message(
                        Message.Assistant(
                            parts = listOf(
                                MessagePart.CodeExecution(
                                    id = "old_execution",
                                    providerItemId = "code_provider",
                                    code = "print(1)",
                                    containerId = "stale_container",
                                )
                            ),
                            metaInfo = ResponseMetaInfo.Empty,
                        )
                    )
                    user("Continue")
                }
                if (streaming) {
                    client.executeStreaming(original, OpenAIModels.Chat.GPT4o).toList()
                } else {
                    client.execute(original, OpenAIModels.Chat.GPT4o)
                }
                assertEquals(1, recoveries)
                assertEquals(2, transport.requests.size)
                assertTrue(transport.requests.first().contains("code_interpreter_call"))
                val retry = Json.parseToJsonElement(transport.requests.last()).jsonObject
                assertTrue(retry.getValue("input").toString().contains("Historical code:"))
                assertTrue(!retry.getValue("input").toString().contains("code_interpreter_call"))
                val container = retry.getValue("tools").jsonArray.single().jsonObject.getValue("container").jsonObject
                assertEquals("auto", container.getValue("type").jsonPrimitive.content)
                assertEquals("file_1", container.getValue("file_ids").jsonArray.single().jsonPrimitive.content)
                assertIs<MessagePart.CodeExecution>(assertIs<Message.Assistant>(original.messages[1]).parts.single())
                assertEquals("stale_container", params.codeInterpreter?.containerId)
            }
        }
    }

    @Test
    fun testContainerRecoveryCallbackFailureAndCancellationAbortRetry() = runTest {
        for (streaming in listOf(false, true)) {
            for (callbackFailure in listOf(IllegalStateException("projection failed"), CancellationException("cancelled"))) {
                val transport = ScriptedResponsesTransport(
                    postResponses = ArrayDeque(listOf(expiredContainerError())),
                    streamAttempts = ArrayDeque(listOf(listOf(expiredContainerError()))),
                )
                val client = OpenAILLMClient(OpenAIClientSettings(), transport)
                val params = recoveryParams().withContainerRecovery { _, _ -> throw callbackFailure }
                val actual = assertFailsWith<Exception> {
                    if (streaming) {
                        client.executeStreaming(prompt(params), OpenAIModels.Chat.GPT4o).toList()
                    } else {
                        client.execute(prompt(params), OpenAIModels.Chat.GPT4o)
                    }
                }
                assertTrue(actual === callbackFailure)
                assertEquals(1, transport.requests.size)
            }
        }
    }

    @Test
    fun testContainerRecoveryDoesNotRetrySecondFailure() = runTest {
        for (streaming in listOf(false, true)) {
            val transport = ScriptedResponsesTransport(
                postResponses = ArrayDeque(listOf(expiredContainerError(), expiredContainerError())),
                streamAttempts = ArrayDeque(listOf(listOf(expiredContainerError()), listOf(expiredContainerError()))),
            )
            val client = OpenAILLMClient(OpenAIClientSettings(), transport)
            var recoveries = 0
            val params = recoveryParams().withContainerRecovery { retry, _ ->
                recoveries++
                retry
            }
            val failure = assertFailsWith<Exception> {
                if (streaming) {
                    client.executeStreaming(prompt(params), OpenAIModels.Chat.GPT4o).toList()
                } else {
                    client.execute(prompt(params), OpenAIModels.Chat.GPT4o)
                }
            }
            assertIs<OpenAIContainerUnavailableException>(if (streaming) failure.cause else failure)
            assertEquals(1, recoveries)
            assertEquals(2, transport.requests.size)
        }
    }

    @Test
    fun testExpiryAfterProviderEventIsTypedWithoutRecovery() = runTest {
        val transport = ScriptedResponsesTransport(
            streamAttempts = ArrayDeque(
                listOf(
                    listOf(
                        OpenAIStreamEvent.ResponseOutputTextDelta("message", 0, 0, "partial", sequenceNumber = 1),
                        expiredContainerError(),
                    )
                )
            )
        )
        val client = OpenAILLMClient(OpenAIClientSettings(), transport)
        val params = recoveryParams().withContainerRecovery { _, _ -> error("Must not recover") }
        val frames = mutableListOf<StreamFrame>()
        val failure = assertFailsWith<LLMClientException> {
            client.executeStreaming(prompt(params), OpenAIModels.Chat.GPT4o).collect { frames += it }
        }
        assertEquals(OpenAIContainerUnavailableReason.Expired, assertIs<OpenAIContainerUnavailableException>(failure.cause).reason)
        assertTrue(frames.isNotEmpty())
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun testUnrecognisedContainerErrorsAndCancellationNeverRecover() = runTest {
        val bodies = listOf(
            "not json",
            "[]",
            """{"error":{"message":{"text":"Container is expired."}}}""",
            """{"error":{"message":"Container is expired.","param":"route"}}""",
            """{"error":{"message":"File is expired.","param":"file"}}""",
            """{"error":{"code":"invalid_container","param":"not_a_container"}}""",
        )
        val failures = bodies.map { KoogHttpClientException("fixture", 400, it) } +
            KoogHttpClientException("fixture", 500, """{"error":{"message":"Container is expired."}}""") +
            CancellationException("cancelled")
        for (streaming in listOf(false, true)) {
            for (failure in failures) {
                val transport = ScriptedResponsesTransport(
                    postResponses = ArrayDeque(listOf(failure)),
                    streamAttempts = ArrayDeque(listOf(listOf(failure))),
                )
                val params = recoveryParams().withContainerRecovery { _, _ -> error("Must not recover") }
                val client = OpenAILLMClient(OpenAIClientSettings(), transport)
                val actual = assertFailsWith<Exception> {
                    if (streaming) {
                        client.executeStreaming(prompt(params), OpenAIModels.Chat.GPT4o).toList()
                    } else {
                        client.execute(prompt(params), OpenAIModels.Chat.GPT4o)
                    }
                }
                assertTrue(actual !is OpenAIContainerUnavailableException)
                assertTrue(actual.cause !is OpenAIContainerUnavailableException)
                assertEquals(1, transport.requests.size)
            }
        }
    }

    @Test
    fun testStatefulExpiryIsTypedWithoutRecovery() = runTest {
        val transport = ScriptedResponsesTransport(postResponses = ArrayDeque(listOf(expiredContainerError())))
        val client = OpenAILLMClient(OpenAIClientSettings(), transport)
        val params = OpenAIResponsesParams(codeInterpreter = OpenAICodeInterpreterConfig(containerId = "stale_container"))
            .withContainerRecovery { _, _ -> error("Must not recover") }
        val failure = assertFailsWith<OpenAIContainerUnavailableException> {
            client.execute(prompt(params), OpenAIModels.Chat.GPT4o)
        }
        assertEquals("stale_container", failure.containerId)
        assertEquals(OpenAIContainerUnavailableReason.Expired, failure.reason)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun testProviderKeepalivePreventsRecoveryBeforeVisibleOutput() = runTest {
        val transport = ScriptedResponsesTransport(
            streamAttempts = ArrayDeque(
                listOf(
                    listOf(
                        OpenAIStreamEvent.ResponseKeepalive(1),
                        expiredContainerError(),
                    )
                )
            )
        )
        val client = OpenAILLMClient(OpenAIClientSettings(), transport)
        val params = recoveryParams().withContainerRecovery { _, _ -> error("Must not recover") }
        val frames = mutableListOf<StreamFrame>()
        assertFailsWith<LLMClientException> {
            client.executeStreaming(prompt(params), OpenAIModels.Chat.GPT4o).collect { frames += it }
        }
        assertTrue(frames.isEmpty())
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun testTypedContainerErrorCodes() = runTest {
        for ((code, reason) in listOf(
            "container_expired" to OpenAIContainerUnavailableReason.Expired,
            "container_not_found" to OpenAIContainerUnavailableReason.Missing,
            "invalid_container" to OpenAIContainerUnavailableReason.Missing,
        )) {
            val transport = ScriptedResponsesTransport(
                postResponses = ArrayDeque(
                    listOf(
                        LLMClientException(
                            "fixture",
                            cause = KoogHttpClientException(
                                "fixture",
                                400,
                                """{"code":"$code","param":"container"}""",
                            )
                        ),
                    )
                )
            )
            val client = OpenAILLMClient(OpenAIClientSettings(), transport)
            val params = OpenAIResponsesParams(codeInterpreter = OpenAICodeInterpreterConfig(containerId = "stale_container"))
            val failure = assertFailsWith<OpenAIContainerUnavailableException> {
                client.execute(prompt(params), OpenAIModels.Chat.GPT4o)
            }
            assertEquals(reason, failure.reason)
            assertEquals("stale_container", failure.containerId)
            assertIs<KoogHttpClientException>(assertIs<LLMClientException>(failure.cause).cause)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun testRecoveryCallbackSurvivesParameterCopies() {
        val source = recoveryParams().withContainerRecovery { retry, _ -> retry }
        val copies = listOf(
            source.copy(),
            (source as LLMParams).copy(maxTokens = 123),
            source.withCodeInterpreter(null),
            source.withPromptCacheIdentity(OpenAIPromptCacheIdentity("user", "chat")),
            source.asStateless(),
        )
        copies.forEach { assertTrue(assertIs<OpenAIResponsesParams>(it).containerRecovery === source.containerRecovery) }
        assertEquals(source, source.copy())
        assertEquals(source.hashCode(), source.copy().hashCode())
        assertTrue(source != source.withContainerRecovery(null))
        assertEquals(null, source.withContainerRecovery(null).containerRecovery)
    }

    private fun recoveryParams(): OpenAIResponsesParams = OpenAIResponsesParams(
        stateless = true,
        codeInterpreter = OpenAICodeInterpreterConfig(containerId = "stale_container"),
    )

    private fun expiredContainerError(): KoogHttpClientException = KoogHttpClientException(
        "fixture",
        400,
        """{"error":{"message":"Container is expired.","type":"invalid_request_error","param":null,"code":null}}""",
    )

    private fun prompt(params: OpenAIResponsesParams = OpenAIResponsesParams(store = false, stateless = true)): Prompt =
        Prompt(
            messages = listOf(Message.User("hello", RequestMetaInfo.Empty)),
            id = "responses-parity",
            params = params,
        )

    private fun replayPrompt(response: Message.Assistant): Prompt = Prompt(
        messages = listOf(response, Message.User("continue", RequestMetaInfo.Empty)),
        id = "recovered-response-replay",
        params = OpenAIResponsesParams(store = false, stateless = true),
    )

    private fun assertLocalRecoveryProgressAvailable(response: Message.Assistant) {
        val progress = assertIs<MessagePart.HostedExecution.Progress>(response.parts.first())
        assertEquals("stale_container_recovered", progress.message)
        assertEquals("stale-container-recovery:stale_container", progress.executionId)
        assertEquals(null, progress.providerItemId)
    }

    private fun assertLocalRecoveryProgressOmittedFromReplay(requestBody: String) {
        val replayItems = Json.parseToJsonElement(requestBody).jsonObject
            .getValue("input").jsonArray.map { it.jsonObject }
        assertEquals(
            emptyList(),
            replayItems.filter { item ->
                item["id"]?.jsonPrimitive?.content == "stale-container-recovery:stale_container"
            }
        )
        assertEquals(
            1,
            replayItems.count { item ->
                item["id"]?.jsonPrimitive?.content == "message_provider"
            },
        )
    }

    private fun staleContainerError(): String =
        """{"error":{"message":"container stale_container was not found","type":"invalid_request_error","param":"tools[0].container","code":"container_not_found"}}"""

    private fun response(
        output: List<Item> = listOf(
            Item.OutputMessage(
                content = listOf(OutputContent.Text(emptyList(), "ok")),
                id = "message_provider",
                status = OpenAIInputStatus.COMPLETED,
            )
        ),
        usage: OpenAIResponsesAPIResponse.Usage? = null,
    ): OpenAIResponsesAPIResponse = OpenAIResponsesAPIResponse(
        usage = usage,
        created = 1,
        id = "response_1",
        model = "gpt-4o",
        output = output,
        parallelToolCalls = false,
        status = OpenAIInputStatus.COMPLETED,
        text = OpenAITextConfig(),
    )

    private class ScriptedResponsesTransport(
        private val postResponses: ArrayDeque<Any> = ArrayDeque(),
        private val streamAttempts: ArrayDeque<List<Any>> = ArrayDeque(),
    ) : KoogHttpClient {
        override val clientName: String = "ScriptedResponsesTransport"
        val requests: MutableList<String> = mutableListOf()
        val paths: MutableList<String> = mutableListOf()

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
        ): R {
            requests += requestBody.toString()
            paths += path
            return when (val next = postResponses.removeFirst()) {
                is Throwable -> throw next
                else -> next as R
            }
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
        ): Flow<O> {
            requests += requestBody.toString()
            paths += path
            val attempt = streamAttempts.removeFirst()
            return flow {
                attempt.forEach { next ->
                    when (next) {
                        is Throwable -> throw next
                        else -> processStreamingChunk(next as R)?.let { emit(it) }
                    }
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
}
