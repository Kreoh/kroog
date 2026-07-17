package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.models.Item
import ai.koog.prompt.executor.clients.openai.models.OpenAIInputStatus
import ai.koog.prompt.executor.clients.openai.models.OpenAIResponsesAPIResponse
import ai.koog.prompt.executor.clients.openai.models.OpenAIStreamEvent
import ai.koog.prompt.executor.clients.openai.models.OpenAITextConfig
import ai.koog.prompt.executor.clients.openai.models.OutputContent
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.test.utils.CapturingKoogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
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
            StreamFrame.ReasoningDelta(id = reasoningId, text = reasoningDelta, index = 0),
            frames[0]
        )
        assertEquals(
            StreamFrame.ReasoningComplete(
                id = reasoningId,
                content = listOf(reasoningContent),
                summary = listOf(reasoningSummary),
                encrypted = encryptedReasoning,
                index = 0
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
                StreamFrame.ToolCallDelta(firstCallId, firstName, "{\"first\":", 0),
                StreamFrame.ToolCallDelta(secondCallId, secondName, "{\"second\":true}", 1),
                StreamFrame.ToolCallDelta(firstCallId, firstName, "true}", 0),
                StreamFrame.ToolCallComplete(secondCallId, secondName, "{\"second\":true}", 1),
                StreamFrame.ToolCallComplete(firstCallId, firstName, "{\"first\":true}", 0)
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
                StreamFrame.TextDelta(text, index = 0),
                StreamFrame.TextComplete(text, index = 0)
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
