package ai.koog.prompt.executor.clients.anthropic

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicEffort
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinkingDisplay
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.PromptCacheControl
import ai.koog.prompt.message.PromptCacheTtl
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.reflect.KClass
import kotlin.test.Test

class AnthropicVertexLLMClientTest {
    @Test
    fun `maps provider-neutral cache metadata through Vertex Anthropic`() {
        val client = vertexClient(RecordingAnthropicVertexHttpClient())
        val prompt = Prompt.build("vertex-cache") {
            user(
                "stable prefix",
                PromptCacheControl(cacheable = true, ttl = PromptCacheTtl.OneHour),
            )
        }

        val request = Json.parseToJsonElement(
            client.createAnthropicRequest(prompt, emptyList(), model, false)
        ).jsonObject
        val cacheControl = request.getValue("messages").jsonArray[0].jsonObject
            .getValue("content").jsonArray[0].jsonObject
            .getValue("cache_control").jsonObject

        cacheControl.getValue("ttl").jsonPrimitive.content shouldBe "1h"
    }

    @Test
    fun `builds Vertex adaptive requests with typed output config winning collisions`() {
        val transport = RecordingAnthropicVertexHttpClient()
        val client = vertexClient(transport)
        val schema =
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("answer", buildJsonObject { put("type", "string") }) })
            }
        val prompt =
            Prompt(
                messages = listOf(Message.User("Return JSON", RequestMetaInfo.Empty)),
                id = "adaptive",
                params =
                AnthropicParams(
                    temperature = 0.7,
                    schema = LLMParams.Schema.JSON.Standard("answer", schema),
                    thinking =
                    AnthropicThinking.Adaptive(
                        display = AnthropicThinkingDisplay.SUMMARIZED,
                        effort = AnthropicEffort.HIGH,
                    ),
                    additionalProperties =
                    mapOf(
                        "custom" to JsonPrimitive("kept"),
                        "output_config" to
                            buildJsonObject {
                                put("effort", "low")
                                put("format", buildJsonObject { put("type", "raw") })
                                put("trace", true)
                            },
                    ),
                ),
            )

        val request = Json.parseToJsonElement(client.createAnthropicRequest(prompt, emptyList(), model, true)).jsonObject

        request.containsKey("model") shouldBe false
        request.getValue("anthropic_version").jsonPrimitive.content shouldBe "vertex-2023-10-16"
        request.containsKey("temperature") shouldBe false
        request.getValue("custom").jsonPrimitive.content shouldBe "kept"
        request.getValue("thinking").jsonObject shouldBe
            buildJsonObject {
                put("type", "adaptive")
                put("display", "summarized")
            }
        val outputConfig = request.getValue("output_config").jsonObject
        outputConfig.getValue("effort").jsonPrimitive.content shouldBe "high"
        outputConfig.getValue("trace").jsonPrimitive.content shouldBe "true"
        outputConfig.getValue("format").jsonObject.getValue("type").jsonPrimitive.content shouldBe "json_schema"
        outputConfig.getValue("format").jsonObject.getValue("schema") shouldBe schema

        shouldThrow<IllegalArgumentException> {
            client.createAnthropicRequest(
                prompt.copy(
                    params = AnthropicParams(additionalProperties = mapOf("output_config" to JsonPrimitive("bad"))),
                ),
                emptyList(),
                model,
                false,
            )
        }.message shouldContain "output_config must be a JSON object"
    }

    @Test
    fun `streams literal Vertex SSE and replays signed thinking and tools in exact second request order`() = runTest {
        val transport =
            RecordingAnthropicVertexHttpClient(
                streams =
                ArrayDeque(
                    listOf(
                        signedToolStream(),
                        finalTextStream(),
                    ),
                ),
            )
        val client = vertexClient(transport)
        val tool = readFileTool()
        val firstPrompt =
            Prompt(
                messages =
                listOf(
                    Message.User(
                        parts =
                        listOf(
                            MessagePart.Text("Inspect this image and file"),
                            MessagePart.Attachment(
                                AttachmentSource.Image(
                                    content = AttachmentContent.Binary.Bytes(byteArrayOf(1, 2, 3)),
                                    format = "png",
                                    mimeType = "image/png",
                                ),
                            ),
                        ),
                        metaInfo = RequestMetaInfo.Empty,
                    ),
                ),
                id = "first",
            )

        val firstFrames = client.executeStreaming(firstPrompt, model, listOf(tool)).toList()

        transport.sseRequests[0].path shouldBe vertexPath("streamRawPredict")
        transport.sseRequests[0].headers shouldBe emptyMap()
        val firstBody = Json.parseToJsonElement(transport.sseRequests[0].body).jsonObject
        firstBody.containsKey("model") shouldBe false
        firstBody.getValue("anthropic_version").jsonPrimitive.content shouldBe "vertex-2023-10-16"
        firstBody.getValue("messages").jsonArray.single().jsonObject
            .getValue("content").jsonArray[1].jsonObject
            .getValue("source").jsonObject.getValue("data").jsonPrimitive.content shouldBe "AQID"
        firstBody.getValue("tools").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content shouldBe
            "read_file"

        val reasoningComplete = firstFrames.filterIsInstance<StreamFrame.ReasoningComplete>().single()
        reasoningComplete.content shouldContainExactly listOf("Check the file")
        reasoningComplete.encrypted shouldBe "thinking-signature"
        reasoningComplete.index shouldBe 0
        val toolComplete = firstFrames.filterIsInstance<StreamFrame.ToolCallComplete>().single()
        toolComplete.id shouldBe "call-1"
        toolComplete.name shouldBe "read_file"
        toolComplete.content shouldBe "{\"file_id\":\"file-1\"}"
        toolComplete.index shouldBe 1
        val firstEnd = firstFrames.filterIsInstance<StreamFrame.End>().single()
        firstEnd.metaInfo.inputTokensCount shouldBe 11
        firstEnd.metaInfo.outputTokensCount shouldBe 7
        firstEnd.metaInfo.totalTokensCount shouldBe 18

        val assistant = firstFrames.toMessageResponse()
        val secondPrompt =
            Prompt(
                messages =
                listOf(
                    firstPrompt.messages.single(),
                    assistant,
                    Message.User(
                        MessagePart.Tool.Result(
                            id = "call-1",
                            tool = "read_file",
                            output = "File contents",
                        ),
                        RequestMetaInfo.Empty,
                    ),
                ),
                id = "second",
            )
        val secondFrames = client.executeStreaming(secondPrompt, model, listOf(tool)).toList()

        transport.sseRequests[1].path shouldBe vertexPath("streamRawPredict")
        transport.sseRequests[1].headers shouldBe emptyMap()
        val secondMessages =
            Json.parseToJsonElement(transport.sseRequests[1].body).jsonObject
                .getValue("messages").jsonArray
        secondMessages.map { it.jsonObject.getValue("role").jsonPrimitive.content } shouldContainExactly
            listOf("user", "assistant", "user")
        val assistantParts = secondMessages[1].jsonObject.getValue("content").jsonArray
        assistantParts[0] shouldBe
            buildJsonObject {
                put("type", "thinking")
                put("signature", "thinking-signature")
                put("thinking", "Check the file")
            }
        assistantParts[1].jsonObject.getValue("type").jsonPrimitive.content shouldBe "tool_use"
        assistantParts[1].jsonObject.getValue("id").jsonPrimitive.content shouldBe "call-1"
        secondMessages[2].jsonObject.getValue("content").jsonArray.single().jsonObject
            .getValue("type").jsonPrimitive.content shouldBe "tool_result"
        secondFrames.filterIsInstance<StreamFrame.TextComplete>().single().text shouldBe "Done"
        secondFrames.filterIsInstance<StreamFrame.End>().single().metaInfo.totalTokensCount shouldBe 24
        client.close()
        transport.closeCount shouldBe 1
    }

    @Test
    fun `preserves signature-only thinking before a tool without visible reasoning text`() = runTest {
        val transport = RecordingAnthropicVertexHttpClient(streams = ArrayDeque(listOf(signatureOnlyToolStream())))
        val client = vertexClient(transport)

        val frames =
            client.executeStreaming(
                Prompt(listOf(Message.User("Use a tool", RequestMetaInfo.Empty)), "signature-only"),
                model,
                listOf(readFileTool()),
            ).toList()

        val reasoning = frames.filterIsInstance<StreamFrame.ReasoningComplete>().single()
        reasoning.content shouldBe emptyList()
        reasoning.encrypted shouldBe "tool-signature"
        reasoning.index shouldBe 0
        frames.indexOf(reasoning) shouldBe 0
        frames[1].shouldBeInstanceOf<StreamFrame.ToolCallDelta>()
        frames[2].shouldBeInstanceOf<StreamFrame.ToolCallDelta>()
        frames[3].shouldBeInstanceOf<StreamFrame.ToolCallComplete>()

        val replay = frames.toMessageResponse()
        val replayRequest =
            Json.parseToJsonElement(
                client.createAnthropicRequest(
                    Prompt(
                        listOf(
                            replay,
                            Message.User(
                                MessagePart.Tool.Result("call-2", "read_file", "result"),
                                RequestMetaInfo.Empty,
                            ),
                        ),
                        "replay",
                    ),
                    listOf(readFileTool()),
                    model,
                    false,
                ),
            ).jsonObject
        val replayAssistant = replayRequest.getValue("messages").jsonArray[0].jsonObject
        replayAssistant.getValue("content").jsonArray[0] shouldBe
            buildJsonObject {
                put("type", "thinking")
                put("signature", "tool-signature")
                put("thinking", "")
            }
    }

    @Test
    fun `uses rawPredict without auth headers and returns nonstream text and usage`() = runTest {
        val transport = RecordingAnthropicVertexHttpClient(postResponse = nonstreamTextResponse())
        val client = vertexClient(transport)

        val message =
            client.execute(
                Prompt(listOf(Message.User("Hello", RequestMetaInfo.Empty)), "nonstream"),
                model,
                emptyList(),
            )

        transport.postRequests.single().path shouldBe vertexPath("rawPredict")
        transport.postRequests.single().headers shouldBe emptyMap()
        Json.parseToJsonElement(transport.postRequests.single().body).jsonObject.containsKey("model") shouldBe false
        message.parts.single() shouldBe MessagePart.Text("Nonstream answer")
        message.metaInfo.totalTokensCount shouldBe 8
        client.models() shouldContainExactly listOf(model)
    }

    @Test
    fun `rejects literal nonstream citations for Vertex and direct Anthropic`() = runTest {
        val vertexTransport = RecordingAnthropicVertexHttpClient(postResponse = citedNonstreamResponse())
        val vertexFailure = shouldThrow<LLMClientException> {
            vertexClient(vertexTransport).execute(
                Prompt(listOf(Message.User("Hello", RequestMetaInfo.Empty)), "vertex-citations"),
                model,
                emptyList(),
            )
        }

        vertexFailure.message shouldContain "Anthropic citations are not supported"
        vertexTransport.postRequests.single().path shouldBe vertexPath("rawPredict")

        val directTransport = RecordingAnthropicVertexHttpClient(postResponse = citedNonstreamResponse())
        val directClient =
            AnthropicLLMClient(
                settings = AnthropicClientSettings(modelVersionsMap = mapOf(model to vertexModelVersion)),
                httpClient = directTransport,
            )
        val directFailure = shouldThrow<LLMClientException> {
            directClient.execute(
                Prompt(listOf(Message.User("Hello", RequestMetaInfo.Empty)), "direct-citations"),
                model,
                emptyList(),
            )
        }

        directFailure.message shouldContain "Anthropic citations are not supported"
        directTransport.postRequests.single().path shouldBe "v1/messages"
    }

    @Test
    fun `rejects unsafe paths citations malformed reasoning server tools and unknown deltas clearly`() = runTest {
        listOf("bad/project", "bad?project", "bad%2Fproject", " bad").forEach { invalid ->
            shouldThrow<IllegalArgumentException> {
                AnthropicVertexClientSettings(invalid, location, mapOf(model to vertexModelVersion))
            }.message shouldContain "safe path segment"
        }
        shouldThrow<IllegalArgumentException> {
            AnthropicVertexClientSettings(projectId, "bad/location", mapOf(model to vertexModelVersion))
        }.message shouldContain "safe path segment"
        shouldThrow<IllegalArgumentException> {
            AnthropicVertexClientSettings(projectId, location, mapOf(model to "bad/model"))
        }.message shouldContain "safe path segment"

        suspend fun failureFor(raw: String): String {
            val transport = RecordingAnthropicVertexHttpClient(streams = ArrayDeque(listOf(listOf(raw))))
            return shouldThrow<LLMClientException> {
                vertexClient(transport).executeStreaming(
                    Prompt(listOf(Message.User("Hello", RequestMetaInfo.Empty)), "unsupported"),
                    model,
                ).toList()
            }.message.orEmpty()
        }

        failureFor(
            """{"type":"content_block_start","index":0,"content_block":{"type":"redacted_thinking","data":7}}""",
        ) shouldContain "Malformed Anthropic redacted_thinking block: 'data' must be a string"
        failureFor(
            """{"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"x","name":"web_search","input":{}}}""",
        ) shouldContain "Unsupported Anthropic stream content block type: server_tool_use"
        failureFor(
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"x","citations":[]}}""",
        ) shouldContain "Anthropic citations are not supported"
        failureFor(
            """{"type":"content_block_delta","index":0,"delta":{"type":"citations_delta","citation":{}}}""",
        ) shouldContain "Unsupported Anthropic stream delta type: citations_delta"
    }
}

private val model = AnthropicModels.Fable_5
private const val projectId = "vertex-project-123"
private const val location = "europe-west1"
private const val vertexModelVersion = "claude-fable-5@20260701"

private fun vertexClient(transport: RecordingAnthropicVertexHttpClient): AnthropicVertexLLMClient =
    AnthropicVertexLLMClient(
        settings = AnthropicVertexClientSettings(projectId, location, mapOf(model to vertexModelVersion)),
        httpClient = transport,
    )

private fun vertexPath(method: String): String =
    "v1/projects/$projectId/locations/$location/publishers/anthropic/models/$vertexModelVersion:$method"

private fun readFileTool(): ToolDescriptor =
    ToolDescriptor(
        name = "read_file",
        description = "Read a file",
        requiredParameters =
        listOf(
            ToolParameterDescriptor(
                name = "file_id",
                description = "File id",
                type = ToolParameterType.String,
            ),
        ),
    )

private fun signedToolStream(): List<String> =
    listOf(
        """{"type":"message_start","message":{"id":"m1","type":"message","role":"assistant","content":[],"model":"$vertexModelVersion","usage":{"input_tokens":11,"output_tokens":0}}}""",
        """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Check the file"}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"thinking-signature"}}""",
        """{"type":"content_block_stop","index":0}""",
        """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call-1","name":"read_file","input":{}}}""",
        """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"file_id\":\"file-1\"}"}}""",
        """{"type":"content_block_stop","index":1}""",
        """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":7}}""",
        """{"type":"message_stop"}""",
    )

private fun finalTextStream(): List<String> =
    listOf(
        """{"type":"message_start","message":{"id":"m2","type":"message","role":"assistant","content":[],"model":"$vertexModelVersion","usage":{"input_tokens":19,"output_tokens":0}}}""",
        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Done"}}""",
        """{"type":"content_block_stop","index":0}""",
        """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}""",
        """{"type":"message_stop"}""",
    )

private fun signatureOnlyToolStream(): List<String> =
    listOf(
        """{"type":"message_start","message":{"id":"m3","type":"message","role":"assistant","content":[],"model":"$vertexModelVersion","usage":{"input_tokens":2,"output_tokens":0}}}""",
        """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"tool-signature"}}""",
        """{"type":"content_block_stop","index":0}""",
        """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call-2","name":"read_file","input":{}}}""",
        """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"file_id\":\"file-2\"}"}}""",
        """{"type":"content_block_stop","index":1}""",
        """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":2}}""",
        """{"type":"message_stop"}""",
    )

private fun nonstreamTextResponse(): String =
    """
    {
      "id":"message-1",
      "type":"message",
      "role":"assistant",
      "content":[{"type":"text","text":"Nonstream answer","future_content":{"ignored":true}}],
      "model":"$vertexModelVersion",
      "stop_reason":"end_turn",
      "usage":{"input_tokens":5,"output_tokens":3},
      "future_top_level":{"ignored":true}
    }
    """.trimIndent()

private fun citedNonstreamResponse(): String =
    """
    {
      "id":"message-cited",
      "type":"message",
      "role":"assistant",
      "content":[
        {
          "type":"text",
          "text":"Unsupported cited answer",
          "citations":[{"type":"char_location","start_char_index":0,"end_char_index":11}]
        }
      ],
      "model":"$vertexModelVersion",
      "stop_reason":"end_turn",
      "usage":{"input_tokens":5,"output_tokens":3}
    }
    """.trimIndent()

private data class RecordedAnthropicRequest(
    val path: String,
    val body: String,
    val parameters: Map<String, String>,
    val headers: Map<String, String>,
)

private class RecordingAnthropicVertexHttpClient(
    private val streams: ArrayDeque<List<String>> = ArrayDeque(),
    private val postResponse: Any? = null,
) : KoogHttpClient {
    override val clientName: String = "recording-vertex-anthropic"
    val sseRequests = mutableListOf<RecordedAnthropicRequest>()
    val postRequests = mutableListOf<RecordedAnthropicRequest>()
    var closeCount = 0

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
        postRequests += RecordedAnthropicRequest(path, requestBody as String, parameters, headers)
        responseType shouldBe String::class
        return requireNotNull(postResponse) as R
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
    ): Flow<O> =
        flow {
            sseRequests += RecordedAnthropicRequest(path, requestBody as String, parameters, headers)
            streams.removeFirst().forEach { raw ->
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

    override fun close() {
        closeCount += 1
    }
}
