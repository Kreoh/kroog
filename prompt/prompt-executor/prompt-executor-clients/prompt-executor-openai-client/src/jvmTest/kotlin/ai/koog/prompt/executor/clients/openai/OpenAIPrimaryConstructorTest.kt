package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.models.Item
import ai.koog.prompt.executor.clients.openai.models.OpenAIInputStatus
import ai.koog.prompt.executor.clients.openai.models.OpenAIResponsesAPIResponse
import ai.koog.prompt.executor.clients.openai.models.OpenAIResponsesTool
import ai.koog.prompt.executor.clients.openai.models.OpenAIStreamEvent
import ai.koog.prompt.executor.clients.openai.models.OpenAITextConfig
import ai.koog.prompt.executor.clients.openai.models.OutputContent
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import ai.koog.test.utils.CapturingKoogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class OpenAIPrimaryConstructorTest {
    private val responseJson = """
        {
          "id": "chatcmpl-123",
          "object": "chat.completion",
          "created": 1716920005,
          "model": "gpt-4o",
          "choices": [
            {
              "index": 0,
              "message": {
                "role": "assistant",
                "content": "Hello from KoogHttpClient"
              },
              "finish_reason": "stop"
            }
          ],
          "usage": {"total_tokens": 10, "prompt_tokens": 4, "completion_tokens": 6}
        }
    """.trimIndent()

    @Test
    fun `primary constructor should execute through provided koog http client`() = runTest {
        val transport = CapturingKoogHttpClient(clientName = "CapturingOpenAIClient") { responseType ->
            when (responseType) {
                String::class -> responseJson
                else -> error("Unexpected response type: $responseType")
            }
        }
        val client = OpenAILLMClient(
            settings = OpenAIClientSettings(baseUrl = "https://unused.test"),
            httpClient = transport
        )

        val responses = client.execute(
            prompt = prompt("test") { user("Hello?") },
            model = OpenAIModels.Chat.GPT4o
        )

        assertEquals("v1/chat/completions", transport.lastPath)
        assertEquals(LLMProvider.OpenAI, client.llmProvider())
        assertEquals(
            """{"role":"user","content":"Hello?"}""",
            transport.lastRequest.toString().substringAfter("\"messages\":[").substringBefore("]")
        )
        assertEquals(1, responses.parts.size)
        val textPart = assertIs<MessagePart.Text>(responses.parts.single())
        assertEquals("Hello from KoogHttpClient", textPart.text)
    }

    @Test
    fun testResponsesCodeInterpreterRequestSerialization() {
        val client = OpenAILLMClient(
            settings = OpenAIClientSettings(baseUrl = "https://unused.test"),
            httpClient = CapturingKoogHttpClient(clientName = "UnusedOpenAIClient") {
                error("No provider request expected")
            },
        )
        val functionTool =
            OpenAIResponsesTool.Function(
                name = "lookup",
                parameters = buildJsonObject { put("type", JsonPrimitive("object")) },
            )

        fun serialise(
            codeInterpreter: OpenAICodeInterpreterConfig?,
            tools: List<OpenAIResponsesTool>? = null,
        ) = Json.parseToJsonElement(
            client.serializeResponsesAPIRequest(
                messages = emptyList(),
                model = OpenAIModels.Chat.GPT4o,
                tools = tools,
                toolChoice = null,
                params = OpenAIResponsesParams(codeInterpreter = codeInterpreter),
                stream = true,
            )
        ).jsonObject

        assertEquals(null, serialise(null)["tools"])
        assertEquals(
            Json.parseToJsonElement(
                """[{"type":"code_interpreter","container":"auto"}]"""
            ),
            serialise(OpenAICodeInterpreterConfig())["tools"],
        )
        assertEquals(
            Json.parseToJsonElement(
                """[{"type":"code_interpreter","container":{"type":"auto","file_ids":["file_123","file_456"]}}]"""
            ),
            serialise(OpenAICodeInterpreterConfig(fileIds = listOf("file_123", "file_456")))["tools"],
        )
        assertEquals(
            Json.parseToJsonElement(
                """[{"type":"code_interpreter","container":"cntr_123"}]"""
            ),
            serialise(OpenAICodeInterpreterConfig(containerId = "cntr_123"))["tools"],
        )
        assertEquals(
            Json.parseToJsonElement(
                """
                [
                  {"type":"function","name":"lookup","parameters":{"type":"object"}},
                  {"type":"code_interpreter","container":"auto"}
                ]
                """.trimIndent()
            ),
            serialise(OpenAICodeInterpreterConfig(), listOf(functionTool))["tools"],
        )

        val mutableFileIds = mutableListOf("file_123")
        val mutableConfig = OpenAICodeInterpreterConfig(fileIds = mutableFileIds)
        mutableFileIds += " "
        assertFailsWith<IllegalArgumentException> {
            serialise(mutableConfig)
        }
    }

    @Test
    fun testResponsesCodeInterpreterSavedReplayFailureStatuses() = runTest {
        val transport = CapturingKoogHttpClient(clientName = "CapturingResponsesClient") { responseType ->
            when (responseType) {
                OpenAIResponsesAPIResponse::class ->
                    OpenAIResponsesAPIResponse(
                        created = 1716920005,
                        id = "resp_code_failures",
                        model = "gpt-4o",
                        output = listOf(
                            Item.CodeInterpreterToolCall(
                                code = "print('response')",
                                containerId = "cntr_response",
                                id = "ci_response",
                                outputs = listOf(Item.CodeInterpreterToolCall.Output.Logs("response")),
                                status = OpenAIInputStatus.COMPLETED,
                            )
                        ),
                        parallelToolCalls = false,
                        status = OpenAIInputStatus.COMPLETED,
                        text = OpenAITextConfig(),
                    )
                else -> error("Unexpected response type: $responseType")
            }
        }
        val client = OpenAILLMClient(
            settings = OpenAIClientSettings(baseUrl = "https://unused.test"),
            httpClient = transport,
        )
        val savedParts = listOf(
            MessagePart.CodeExecution(
                id = "ci_failed",
                code = "raise RuntimeError()",
                containerId = "cntr_failed",
                failure = MessagePart.CodeExecution.Failure.FAILED,
            ),
            MessagePart.CodeExecution(
                id = "ci_incomplete",
                code = "while True: pass",
                containerId = "cntr_incomplete",
                failure = MessagePart.CodeExecution.Failure.INCOMPLETE,
            ),
        )

        client.execute(
            prompt = Prompt(
                messages = listOf(
                    Message.Assistant(parts = savedParts, metaInfo = ResponseMetaInfo.Empty),
                ),
                id = "saved-code-failures",
                params = OpenAIResponsesParams(),
            ),
            model = OpenAIModels.Chat.GPT4o,
        )

        val input = Json.parseToJsonElement(transport.lastRequest.toString()).jsonObject
            .getValue("input")
            .jsonArray

        assertEquals(
            listOf("failed", "incomplete"),
            input.map { it.jsonObject.getValue("status").toString().trim('"') },
        )
    }

    @Test
    fun testResponsesCodeInterpreterSavedReplayAndResponseConversion() = runTest {
        val savedPart = MessagePart.CodeExecution(
            id = "ci_saved",
            code = "print('saved')",
            containerId = "cntr_saved",
            outputs = listOf(
                MessagePart.CodeExecution.Output.Logs("saved output"),
                MessagePart.CodeExecution.Output.Image("https://example.test/saved.png"),
            ),
        )
        val returnedPart = MessagePart.CodeExecution(
            id = "ci_returned",
            code = "print('returned')",
            containerId = "cntr_returned",
            outputs = listOf(
                MessagePart.CodeExecution.Output.Logs("returned output"),
                MessagePart.CodeExecution.Output.Image("https://example.test/returned.png"),
            ),
        )
        val transport = CapturingKoogHttpClient(clientName = "CapturingResponsesClient") { responseType ->
            when (responseType) {
                OpenAIResponsesAPIResponse::class ->
                    OpenAIResponsesAPIResponse(
                        created = 1716920005,
                        id = "resp_code",
                        model = "gpt-4o",
                        output = listOf(
                            Item.CodeInterpreterToolCall(
                                code = returnedPart.code,
                                containerId = requireNotNull(returnedPart.containerId),
                                id = returnedPart.id,
                                outputs = listOf(
                                    Item.CodeInterpreterToolCall.Output.Logs("returned output"),
                                    Item.CodeInterpreterToolCall.Output.Image("https://example.test/returned.png"),
                                ),
                                status = OpenAIInputStatus.COMPLETED,
                            )
                        ),
                        parallelToolCalls = false,
                        status = OpenAIInputStatus.COMPLETED,
                        text = OpenAITextConfig(),
                    )
                else -> error("Unexpected response type: $responseType")
            }
        }
        val client = OpenAILLMClient(
            settings = OpenAIClientSettings(baseUrl = "https://unused.test"),
            httpClient = transport,
        )

        val response = client.execute(
            prompt = Prompt(
                messages = listOf(
                    Message.Assistant(parts = listOf(savedPart), metaInfo = ResponseMetaInfo.Empty),
                ),
                id = "saved-code",
                params = OpenAIResponsesParams(),
            ),
            model = OpenAIModels.Chat.GPT4o,
        )

        val returnedExecutionId = "execution:${returnedPart.id}"
        assertEquals(
            listOf(
                returnedPart.copy(id = returnedExecutionId, providerItemId = returnedPart.id),
                MessagePart.HostedExecution.Request(
                    code = returnedPart.code,
                    executionId = returnedExecutionId,
                    containerId = returnedPart.containerId,
                    providerItemId = returnedPart.id,
                ),
                MessagePart.HostedExecution.Result(
                    output = "returned outputhttps://example.test/returned.png",
                    executionId = returnedExecutionId,
                    containerId = returnedPart.containerId,
                    providerItemId = returnedPart.id,
                ),
            ),
            response.parts,
        )
        val input = Json.parseToJsonElement(transport.lastRequest.toString()).jsonObject
            .getValue("input")
            .jsonArray
        assertEquals(
            Json.parseToJsonElement(
                """
                {
                  "code":"print('saved')",
                  "container_id":"cntr_saved",
                  "id":"ci_saved",
                  "outputs":[
                    {"type":"logs","logs":"saved output"},
                    {"type":"image","url":"https://example.test/saved.png"}
                  ],
                  "status":"completed",
                  "type":"code_interpreter_call"
                }
                """.trimIndent()
            ),
            input.single(),
        )
    }

    @Test
    fun testResponsesCodeInterpreterRawStreamingLifecycle() = runTest {
        val itemId = "ci_stream"
        val containerId = "cntr_stream"
        val outputIndex = 2
        val chunks = listOf(
            """
                {
                  "type":"response.output_item.added",
                  "item":{
                    "type":"code_interpreter_call",
                    "code":null,
                    "container_id":"$containerId",
                    "id":"$itemId",
                    "outputs":null,
                    "status":"in_progress"
                  },
                  "output_index":$outputIndex,
                  "sequence_number":1
                }
            """.trimIndent(),
            """
                {
                  "type":"response.code_interpreter_call.in_progress",
                  "item_id":"$itemId",
                  "output_index":$outputIndex,
                  "sequence_number":2
                }
            """.trimIndent(),
            """
                {
                  "type":"response.code_interpreter_call_code.delta",
                  "item_id":"$itemId",
                  "output_index":$outputIndex,
                  "delta":"print(",
                  "sequence_number":3
                }
            """.trimIndent(),
            """
                {
                  "type":"response.code_interpreter_call.interpreting",
                  "item_id":"$itemId",
                  "output_index":$outputIndex,
                  "sequence_number":4
                }
            """.trimIndent(),
            """
                {
                  "type":"response.code_interpreter_call_code.delta",
                  "item_id":"$itemId",
                  "output_index":$outputIndex,
                  "delta":"'hello')",
                  "sequence_number":5
                }
            """.trimIndent(),
            """
                {
                  "type":"response.code_interpreter_call_code.done",
                  "item_id":"$itemId",
                  "output_index":$outputIndex,
                  "code":"print('hello')",
                  "sequence_number":6
                }
            """.trimIndent(),
            """
                {
                  "type":"response.code_interpreter_call.completed",
                  "item_id":"$itemId",
                  "output_index":$outputIndex,
                  "sequence_number":7
                }
            """.trimIndent(),
            """
                {
                  "type":"response.output_item.done",
                  "item":{
                    "type":"code_interpreter_call",
                    "code":"print('hello')",
                    "container_id":"$containerId",
                    "id":"$itemId",
                    "outputs":[
                      {"type":"logs","logs":"hello\n"},
                      {"type":"image","url":"https://example.test/chart.png"}
                    ],
                    "status":"completed"
                  },
                  "output_index":$outputIndex,
                  "sequence_number":8
                }
            """.trimIndent(),
            completedResponseChunk(sequenceNumber = 9),
        )
        val client = OpenAILLMClient(
            settings = OpenAIClientSettings(baseUrl = "https://unused.test"),
            httpClient = responsesStreamingTransport(chunks),
        )

        val frames = client.executeStreaming(
            prompt = Prompt(
                messages = listOf(Message.User("Run code", RequestMetaInfo.Empty)),
                id = "stream-code",
                params = OpenAIResponsesParams(codeInterpreter = OpenAICodeInterpreterConfig()),
            ),
            model = OpenAIModels.Chat.GPT4o,
        ).toList()

        val outputs = listOf(
            MessagePart.CodeExecution.Output.Logs("hello\n"),
            MessagePart.CodeExecution.Output.Image("https://example.test/chart.png"),
        )
        val executionId = "execution:$itemId"
        assertEquals(
            listOf(
                StreamFrame.CodeExecutionStart(executionId, containerId, outputIndex, itemId),
                StreamFrame.CodeExecutionCodeDelta(executionId, containerId, "print(", outputIndex, itemId),
                StreamFrame.CodeExecutionCodeDelta(executionId, containerId, "'hello')", outputIndex, itemId),
                StreamFrame.CodeExecutionOutput(executionId, containerId, outputs[0], outputIndex, itemId),
                StreamFrame.CodeExecutionOutput(executionId, containerId, outputs[1], outputIndex, itemId),
                StreamFrame.CodeExecutionComplete(
                    id = executionId,
                    code = "print('hello')",
                    containerId = containerId,
                    outputs = outputs,
                    index = outputIndex,
                    providerItemId = itemId,
                ),
                StreamFrame.HostedExecutionUpdate(
                    MessagePart.HostedExecution.Request(
                        code = "print('hello')",
                        executionId = executionId,
                        containerId = containerId,
                        providerItemId = itemId,
                    ),
                    outputIndex,
                ),
                StreamFrame.HostedExecutionUpdate(
                    MessagePart.HostedExecution.Result(
                        output = "hello\nhttps://example.test/chart.png",
                        executionId = executionId,
                        containerId = containerId,
                        providerItemId = itemId,
                    ),
                    outputIndex,
                ),
            ),
            frames.dropLast(1),
        )
        assertIs<StreamFrame.End>(frames.last())
        assertEquals(
            listOf(
                MessagePart.CodeExecution(executionId, "print('hello')", containerId, outputs, providerItemId = itemId),
                MessagePart.HostedExecution.Request(
                    code = "print('hello')",
                    executionId = executionId,
                    containerId = containerId,
                    providerItemId = itemId,
                ),
                MessagePart.HostedExecution.Result(
                    output = "hello\nhttps://example.test/chart.png",
                    executionId = executionId,
                    containerId = containerId,
                    providerItemId = itemId,
                ),
            ),
            frames.toMessageResponse().parts,
        )
    }

    @Test
    fun testResponsesCodeInterpreterFailurePrecedesCompletion() = runTest {
        listOf(
            OpenAIInputStatus.FAILED to MessagePart.CodeExecution.Failure.FAILED,
            OpenAIInputStatus.INCOMPLETE to MessagePart.CodeExecution.Failure.INCOMPLETE,
        ).forEachIndexed { testIndex, (status, failure) ->
            val itemId = "ci_failure_$testIndex"
            val containerId = "cntr_failure_$testIndex"
            val events = listOf(
                OpenAIStreamEvent.ResponseOutputItemAdded(
                    item = Item.CodeInterpreterToolCall(
                        code = null,
                        containerId = containerId,
                        id = itemId,
                        outputs = null,
                        status = OpenAIInputStatus.IN_PROGRESS,
                    ),
                    outputIndex = testIndex,
                    sequenceNumber = 1,
                ),
                OpenAIStreamEvent.ResponseCodeInterpreterCallInProgress(
                    itemId = itemId,
                    outputIndex = testIndex,
                    sequenceNumber = 2,
                ),
                OpenAIStreamEvent.ResponseOutputItemDone(
                    item = Item.CodeInterpreterToolCall(
                        code = "raise RuntimeError()",
                        containerId = containerId,
                        id = itemId,
                        outputs = emptyList(),
                        status = status,
                    ),
                    outputIndex = testIndex,
                    sequenceNumber = 3,
                ),
                completedResponseEvent(sequenceNumber = 4),
            )
            val client = OpenAILLMClient(
                settings = OpenAIClientSettings(baseUrl = "https://unused.test"),
                httpClient = streamingTransport(events),
            )

            val frames = client.executeStreaming(
                prompt = Prompt(
                    messages = listOf(Message.User("Run failing code", RequestMetaInfo.Empty)),
                    id = "stream-failure",
                    params = OpenAIResponsesParams(codeInterpreter = OpenAICodeInterpreterConfig()),
                ),
                model = OpenAIModels.Chat.GPT4o,
            ).toList()

            val executionId = "execution:$itemId"
            assertEquals(
                listOf(
                    StreamFrame.CodeExecutionStart(executionId, containerId, testIndex, itemId),
                    StreamFrame.CodeExecutionFailure(executionId, containerId, failure, testIndex, itemId),
                    StreamFrame.CodeExecutionComplete(
                        id = executionId,
                        code = "raise RuntimeError()",
                        containerId = containerId,
                        outputs = emptyList(),
                        failure = failure,
                        index = testIndex,
                        providerItemId = itemId,
                    ),
                    StreamFrame.HostedExecutionUpdate(
                        MessagePart.HostedExecution.Request(
                            code = "raise RuntimeError()",
                            executionId = executionId,
                            containerId = containerId,
                            providerItemId = itemId,
                        ),
                        testIndex,
                    ),
                    StreamFrame.HostedExecutionUpdate(
                        MessagePart.HostedExecution.Error(
                            message = status.name.lowercase(),
                            code = status.name.lowercase(),
                            executionId = executionId,
                            containerId = containerId,
                            providerItemId = itemId,
                        ),
                        testIndex,
                    ),
                ),
                frames.dropLast(1),
            )
            assertIs<StreamFrame.End>(frames.last())
        }
    }

    @Test
    fun `chat streaming accepts Azure annotation choices without delta`() = runTest {
        val inputTokens = 4
        val outputTokens = 2
        val totalTokens = 6
        val chunks = listOf(
            """
                {
                  "id": "chatcmpl-annotation",
                  "object": "chat.completion.chunk",
                  "created": 1716920005,
                  "model": "gpt-4o",
                  "choices": [
                    {
                      "index": 0,
                      "finish_reason": null,
                      "content_filter_results": {
                        "hate": {"filtered": false, "severity": "safe"}
                      },
                      "content_filter_offsets": {
                        "start_offset": 0,
                        "end_offset": 5,
                        "check_offset": 0
                      }
                    }
                  ]
                }
            """.trimIndent(),
            """
                {
                  "id": "chatcmpl-content",
                  "object": "chat.completion.chunk",
                  "created": 1716920005,
                  "model": "gpt-4o",
                  "choices": [
                    {
                      "index": 0,
                      "delta": {"content": "Hello"},
                      "finish_reason": null
                    }
                  ]
                }
            """.trimIndent(),
            """
                {
                  "id": "chatcmpl-finish",
                  "object": "chat.completion.chunk",
                  "created": 1716920005,
                  "model": "gpt-4o",
                  "choices": [
                    {
                      "index": 0,
                      "finish_reason": "stop"
                    }
                  ]
                }
            """.trimIndent(),
            """
                {
                  "id": "chatcmpl-usage",
                  "object": "chat.completion.chunk",
                  "created": 1716920005,
                  "model": "gpt-4o",
                  "choices": [],
                  "usage": {
                    "prompt_tokens": $inputTokens,
                    "completion_tokens": $outputTokens,
                    "total_tokens": $totalTokens
                  }
                }
            """.trimIndent()
        )
        val client = OpenAILLMClient(
            settings = OpenAIClientSettings(baseUrl = "https://unused.test"),
            httpClient = chatCompletionsStreamingTransport(chunks)
        )

        val frames = client.executeStreaming(
            prompt = Prompt(
                messages = listOf(Message.User("Hello?", RequestMetaInfo.Empty)),
                id = "test",
                params = OpenAIChatParams()
            ),
            model = OpenAIModels.Chat.GPT4o
        ).toList()

        assertEquals(
            listOf(
                StreamFrame.TextDelta("Hello", index = 0),
                StreamFrame.TextComplete("Hello", index = 0)
            ),
            frames.dropLast(1)
        )
        val end = assertIs<StreamFrame.End>(frames.last())
        assertEquals("stop", end.finishReason)
        assertEquals(inputTokens, end.metaInfo.inputTokensCount)
        assertEquals(outputTokens, end.metaInfo.outputTokensCount)
        assertEquals(totalTokens, end.metaInfo.totalTokensCount)
    }

    @Test
    fun `primary constructor should stream reasoning frames through provided koog http client`() = runTest {
        val responsesPath = "v1/responses"
        val reasoningId = "reasoning_123"
        val reasoningDelta = "Thinking"
        val reasoningContent = "Thinking complete"
        val reasoningSummary = "Short summary"
        val encryptedReasoning = "enc_123"
        val responseId = "resp_123"
        val inputTokens = 3
        val outputTokens = 4
        val reasoningTokens = 2
        val totalTokens = 7

        val transport = object : KoogHttpClient {
            override val clientName: String = "StreamingOpenAIClient"

            override suspend fun <R : Any> get(
                path: String,
                responseType: KClass<R>,
                parameters: Map<String, String>,
                headers: Map<String, String>,
            ): R = error("GET is not expected in this test")

            override suspend fun <T : Any, R : Any> post(
                path: String,
                requestBody: T,
                requestBodyType: KClass<T>,
                responseType: KClass<R>,
                parameters: Map<String, String>,
                headers: Map<String, String>,
            ): R = error("POST is not expected in this test")

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
                assertEquals(responsesPath, path)

                val events = listOfNotNull(
                    processStreamingChunk(
                        OpenAIStreamEvent.ResponseReasoningTextDelta(
                            itemId = reasoningId,
                            outputIndex = 0,
                            contentIndex = 0,
                            delta = reasoningDelta,
                            sequenceNumber = 1
                        ) as R
                    ),
                    processStreamingChunk(OpenAIStreamEvent.ResponseKeepalive(sequenceNumber = 2) as R),
                    processStreamingChunk(
                        OpenAIStreamEvent.ResponseOutputItemDone(
                            item = Item.Reasoning(
                                id = reasoningId,
                                summary = listOf(Item.Reasoning.Summary(reasoningSummary)),
                                content = listOf(Item.Reasoning.Content(reasoningContent)),
                                encryptedContent = encryptedReasoning,
                                status = OpenAIInputStatus.COMPLETED
                            ),
                            outputIndex = 0,
                            sequenceNumber = 3
                        ) as R
                    ),
                    processStreamingChunk(
                        OpenAIStreamEvent.ResponseCompleted(
                            response = OpenAIResponsesAPIResponse(
                                created = 1716920005,
                                id = responseId,
                                model = "gpt-5",
                                output = emptyList(),
                                parallelToolCalls = false,
                                status = OpenAIInputStatus.COMPLETED,
                                text = OpenAITextConfig(),
                                usage = OpenAIResponsesAPIResponse.Usage(
                                    inputTokens = inputTokens,
                                    inputTokensDetails = OpenAIResponsesAPIResponse.Usage.InputTokensDetails(cachedTokens = 0),
                                    outputTokens = outputTokens,
                                    outputTokensDetails = OpenAIResponsesAPIResponse.Usage.OutputTokensDetails(reasoningTokens = reasoningTokens),
                                    totalTokens = totalTokens
                                )
                            ),
                            sequenceNumber = 4
                        ) as R
                    )
                )

                return if (events.isNotEmpty()) {
                    flow {
                        events.forEach { emit(it) }
                    }
                } else {
                    emptyFlow()
                }
            }

            override fun <T : Any> lines(
                path: String,
                requestBody: T,
                requestBodyType: KClass<T>,
                parameters: Map<String, String>,
                headers: Map<String, String>,
            ): Flow<String> = error("lines is not expected in this test")

            override fun close(): Unit = Unit
        }
        val client = OpenAILLMClient(
            settings = OpenAIClientSettings(baseUrl = "https://unused.test"),
            httpClient = transport
        )

        val frames = client.executeStreaming(
            prompt = Prompt(
                messages = listOf(Message.User("Hello?", RequestMetaInfo.Empty)),
                id = "test",
                params = OpenAIResponsesParams()
            ),
            model = OpenAIModels.Chat.GPT4o
        ).toList()

        assertEquals(3, frames.size)
        assertEquals(
            StreamFrame.ReasoningDelta(
                text = reasoningDelta,
                index = 0,
                providerItemId = reasoningId,
            ),
            frames[0]
        )
        assertEquals(
            StreamFrame.ReasoningComplete(
                id = null,
                content = listOf(reasoningContent),
                summary = listOf(reasoningSummary),
                encrypted = encryptedReasoning,
                index = 0,
                providerItemId = reasoningId,
            ),
            frames[1]
        )
        val end = assertIs<StreamFrame.End>(frames[2])
        assertEquals(null, end.finishReason)
        assertEquals(totalTokens, end.metaInfo.totalTokensCount)
        assertEquals(inputTokens, end.metaInfo.inputTokensCount)
        assertEquals(outputTokens, end.metaInfo.outputTokensCount)
    }

    @Test
    fun testResponsesStreamingToolCallDeltasUseCanonicalIdentity() = runTest {
        val firstItemId = "item_first"
        val firstCallId = "call_first"
        val firstName = "firstTool"
        val secondItemId = "item_second"
        val secondCallId = "call_second"
        val secondName = "secondTool"
        val events = listOf(
            OpenAIStreamEvent.ResponseOutputItemAdded(
                item = Item.FunctionToolCall(
                    arguments = "",
                    callId = firstCallId,
                    name = firstName,
                    id = firstItemId,
                    status = OpenAIInputStatus.IN_PROGRESS
                ),
                outputIndex = 0,
                sequenceNumber = 1
            ),
            OpenAIStreamEvent.ResponseFunctionCallArgumentsDelta(
                itemId = firstItemId,
                outputIndex = 0,
                delta = "{\"first\":",
                sequenceNumber = 2
            ),
            OpenAIStreamEvent.ResponseOutputItemAdded(
                item = Item.FunctionToolCall(
                    arguments = "",
                    callId = secondCallId,
                    name = secondName,
                    id = secondItemId,
                    status = OpenAIInputStatus.IN_PROGRESS
                ),
                outputIndex = 1,
                sequenceNumber = 3
            ),
            OpenAIStreamEvent.ResponseFunctionCallArgumentsDelta(
                itemId = secondItemId,
                outputIndex = 1,
                delta = "{\"second\":true}",
                sequenceNumber = 4
            ),
            OpenAIStreamEvent.ResponseFunctionCallArgumentsDelta(
                itemId = firstItemId,
                outputIndex = 0,
                delta = "true}",
                sequenceNumber = 5
            ),
            OpenAIStreamEvent.ResponseOutputItemDone(
                item = Item.FunctionToolCall(
                    arguments = "{\"second\":true}",
                    callId = secondCallId,
                    name = secondName,
                    id = secondItemId,
                    status = OpenAIInputStatus.COMPLETED
                ),
                outputIndex = 1,
                sequenceNumber = 6
            ),
            OpenAIStreamEvent.ResponseOutputItemDone(
                item = Item.FunctionToolCall(
                    arguments = "{\"first\":true}",
                    callId = firstCallId,
                    name = firstName,
                    id = firstItemId,
                    status = OpenAIInputStatus.COMPLETED
                ),
                outputIndex = 0,
                sequenceNumber = 7
            ),
            completedResponseEvent(sequenceNumber = 8)
        )
        val client = OpenAILLMClient(
            settings = OpenAIClientSettings(baseUrl = "https://unused.test"),
            httpClient = streamingTransport(events)
        )

        val frames = client.executeStreaming(
            prompt = Prompt(
                messages = listOf(Message.User("Use both tools", RequestMetaInfo.Empty)),
                id = "test",
                params = OpenAIResponsesParams()
            ),
            model = OpenAIModels.Chat.GPT4o
        ).toList()

        assertEquals(
            listOf(
                StreamFrame.ToolCallDelta(firstCallId, firstName, "{\"first\":", 0, firstItemId),
                StreamFrame.ToolCallDelta(secondCallId, secondName, "{\"second\":true}", 1, secondItemId),
                StreamFrame.ToolCallDelta(firstCallId, firstName, "true}", 0, firstItemId),
                StreamFrame.ToolCallComplete(secondCallId, secondName, "{\"second\":true}", 1, secondItemId),
                StreamFrame.ToolCallComplete(firstCallId, firstName, "{\"first\":true}", 0, firstItemId)
            ),
            frames.dropLast(1)
        )
        assertIs<StreamFrame.End>(frames.last())
    }

    @Test
    fun testResponsesStreamingTextDoneEmitsTextComplete() = runTest {
        val itemId = "message_123"
        val text = "Hello from the Responses API"
        val events = listOf(
            OpenAIStreamEvent.ResponseOutputTextDelta(
                itemId = itemId,
                outputIndex = 0,
                contentIndex = 0,
                delta = text,
                sequenceNumber = 1
            ),
            OpenAIStreamEvent.ResponseOutputTextDone(
                itemId = itemId,
                outputIndex = 0,
                contentIndex = 0,
                text = text,
                sequenceNumber = 2
            ),
            OpenAIStreamEvent.ResponseOutputItemDone(
                item = Item.OutputMessage(
                    content = listOf(OutputContent.Text(annotations = emptyList(), text = text)),
                    id = itemId,
                    status = OpenAIInputStatus.COMPLETED
                ),
                outputIndex = 0,
                sequenceNumber = 3
            ),
            completedResponseEvent(sequenceNumber = 4)
        )
        val client = OpenAILLMClient(
            settings = OpenAIClientSettings(baseUrl = "https://unused.test"),
            httpClient = streamingTransport(events)
        )

        val frames = client.executeStreaming(
            prompt = Prompt(
                messages = listOf(Message.User("Hello?", RequestMetaInfo.Empty)),
                id = "test",
                params = OpenAIResponsesParams()
            ),
            model = OpenAIModels.Chat.GPT4o
        ).toList()

        assertEquals(
            listOf(
                StreamFrame.TextDelta(text, index = 0, providerItemId = itemId),
                StreamFrame.TextComplete(text, index = 0, providerItemId = itemId)
            ),
            frames.dropLast(1)
        )
        assertIs<StreamFrame.End>(frames.last())
    }

    private fun streamingTransport(events: List<OpenAIStreamEvent>): KoogHttpClient = object : KoogHttpClient {
        override val clientName: String = "StreamingOpenAIClient"

        override suspend fun <R : Any> get(
            path: String,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R = error("GET is not expected in this test")

        override suspend fun <T : Any, R : Any> post(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R = error("POST is not expected in this test")

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
            events.forEach { event ->
                processStreamingChunk(event as R)?.let { emit(it) }
            }
        }

        override fun <T : Any> lines(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<String> = error("lines is not expected in this test")

        override fun close(): Unit = Unit
    }

    private fun chatCompletionsStreamingTransport(chunks: List<String>): KoogHttpClient = object : KoogHttpClient {
        override val clientName: String = "StreamingOpenAIChatClient"

        override suspend fun <R : Any> get(
            path: String,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R = error("GET is not expected in this test")

        override suspend fun <T : Any, R : Any> post(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R = error("POST is not expected in this test")

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
            chunks.forEach { chunk ->
                processStreamingChunk(decodeStreamingResponse(chunk))?.let { emit(it) }
            }
        }

        override fun <T : Any> lines(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<String> = error("lines is not expected in this test")

        override fun close(): Unit = Unit
    }

    private fun responsesStreamingTransport(chunks: List<String>): KoogHttpClient = object : KoogHttpClient {
        override val clientName: String = "StreamingOpenAIResponsesClient"

        override suspend fun <R : Any> get(
            path: String,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R = error("GET is not expected in this test")

        override suspend fun <T : Any, R : Any> post(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R = error("POST is not expected in this test")

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
            chunks.forEach { chunk ->
                processStreamingChunk(decodeStreamingResponse(chunk))?.let { emit(it) }
            }
        }

        override fun <T : Any> lines(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<String> = error("lines is not expected in this test")

        override fun close(): Unit = Unit
    }

    private fun completedResponseChunk(sequenceNumber: Int): String =
        """
        {
          "type":"response.completed",
          "response":{
            "created_at":1716920005,
            "id":"response_id",
            "model":"gpt-4o",
            "output":[],
            "parallel_tool_calls":false,
            "status":"completed",
            "text":{}
          },
          "sequence_number":$sequenceNumber
        }
        """.trimIndent()

    private fun completedResponseEvent(sequenceNumber: Int): OpenAIStreamEvent.ResponseCompleted =
        OpenAIStreamEvent.ResponseCompleted(
            response = OpenAIResponsesAPIResponse(
                created = 1716920005,
                id = "response_id",
                model = "gpt-5",
                output = emptyList(),
                parallelToolCalls = true,
                status = OpenAIInputStatus.COMPLETED,
                text = OpenAITextConfig(),
                usage = null
            ),
            sequenceNumber = sequenceNumber
        )
}
