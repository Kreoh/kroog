package ai.koog.prompt.executor.clients.openai

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
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
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
        assertEquals("container_active", request.getValue("tools").jsonArray.single().jsonObject
            .getValue("container").jsonPrimitive.content)
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

        client.execute(replayPrompt(recovered), OpenAIModels.Chat.GPT4o)

        assertEquals(3, transport.requests.size)
        assertRecoveredProgressReplayed(transport.requests.last())
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

        client.execute(replayPrompt(recovered), OpenAIModels.Chat.GPT4o)

        assertEquals(3, transport.requests.size)
        assertRecoveredProgressReplayed(transport.requests.last())
    }

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

    private fun assertRecoveredProgressReplayed(requestBody: String) {
        val recoveredItems = Json.parseToJsonElement(requestBody).jsonObject
            .getValue("input").jsonArray.map { it.jsonObject }
            .filter { item ->
                item["type"]?.jsonPrimitive?.content == "code_interpreter_call" &&
                    item["id"]?.jsonPrimitive?.content == "stale-container-recovery:stale_container"
            }
        assertEquals(1, recoveredItems.size)
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
    ): OpenAIResponsesAPIResponse = OpenAIResponsesAPIResponse(
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
