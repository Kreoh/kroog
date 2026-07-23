package ai.koog.prompt.executor.clients.google

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.google.models.GoogleRequest
import ai.koog.prompt.executor.clients.google.models.GoogleResponse
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass
import kotlin.test.Test

class GeminiStreamingTest {
    @Test
    fun testSingleChunkCodeResultAndTextStreamInProviderOrder() = runTest {
        val model = GoogleModels.Gemini2_5Pro
        val response =
            """
            {
              "candidates": [{
                "content": {
                  "role": "model",
                  "parts": [
                    {"executableCode": {"language": "PYTHON", "code": "print('hello')"}},
                    {"codeExecutionResult": {"outcome": "OUTCOME_OK", "output": "hello\n"}},
                    {"text": "done"}
                  ]
                },
                "finishReason": "STOP",
                "index": 0
              }]
            }
            """.trimIndent()
        val client = GoogleLLMClient(httpClient = streamingTransport(model.id, listOf(response)))

        val frames = client.executeStreaming(
            Prompt(listOf(Message.User("calculate", RequestMetaInfo.Empty)), "single-chunk-order"),
            model,
        ).toList()
        val streamed = frames.toMessageResponse()
        val nonStream = client.processGoogleCandidate(
            Json.decodeFromString<GoogleResponse>(response).candidates.single(),
            ResponseMetaInfo.Empty,
        )

        streamed shouldBe nonStream
        frames.mapNotNull { frame ->
            when (frame) {
                is StreamFrame.HostedExecutionUpdate -> frame.update
                is StreamFrame.TextComplete -> MessagePart.Text(frame.text)
                else -> null
            }
        } shouldBe nonStream.parts
    }

    @Test
    fun testDisplacedCodeAndResultFlushInProviderOrderAndShareGeneratedId() = runTest {
        val model = GoogleModels.Gemini2_5Pro
        val chunks = listOf(
            """
            {
              "candidates": [{
                "content": {
                  "role": "model",
                  "parts": [
                    {"executableCode": {"language": "PYTHON", "code": "print('hello')"}},
                    {"codeExecutionResult": {"outcome": "OUTCOME_OK", "output": "hello\n"}}
                  ]
                },
                "index": 0
              }]
            }
            """.trimIndent(),
            """
            {
              "candidates": [{
                "content": {"role": "model", "parts": [{"text": "done"}]},
                "finishReason": "STOP",
                "index": 0
              }]
            }
            """.trimIndent(),
        )
        val client = GoogleLLMClient(httpClient = streamingTransport(model.id, chunks))

        val frames = client.executeStreaming(
            Prompt(listOf(Message.User("calculate", RequestMetaInfo.Empty)), "displaced-stream"),
            model,
        ).toList()
        val streamed = frames.toMessageResponse()
        val nonStream = client.processGoogleCandidate(
            Json.decodeFromString<GoogleResponse>(
                """
                {
                  "candidates": [{
                    "content": {"role": "model", "parts": [
                      {"executableCode": {"language": "PYTHON", "code": "print('hello')"}},
                      {"codeExecutionResult": {"outcome": "OUTCOME_OK", "output": "hello\n"}},
                      {"text": "done"}
                    ]},
                    "finishReason": "STOP",
                    "index": 0
                  }]
                }
                """.trimIndent()
            ).candidates.single(),
            ResponseMetaInfo.Empty,
        )

        streamed shouldBe nonStream
        frames.filterIsInstance<StreamFrame.HostedExecutionUpdate>().map { it.update } shouldBe listOf(
            MessagePart.HostedExecution.Request(
                code = "print('hello')",
                executionId = "google-execution-0-0",
            ),
            MessagePart.HostedExecution.Result(
                output = "hello\n",
                exitCode = 0,
                executionId = "google-execution-0-0",
            ),
        )
    }

    @Test
    fun testArbitraryCodeResultAndSignatureChunksMatchNonStreamOutput() = runTest {
        val model = GoogleModels.Gemini2_5Pro
        val chunks = listOf(
            responseChunk(
                field = "executableCode",
                value = """{"id":"exec-1","language":"PYTHON","code":"print("}""",
                thoughtSignature = "code-",
            ),
            responseChunk(
                field = "executableCode",
                value = """{"id":"exec-1","language":"PYTHON","code":"'hello')"}""",
                thoughtSignature = "signature",
            ),
            responseChunk(
                field = "codeExecutionResult",
                value = """{"id":"exec-1","outcome":"OUTCOME_OK","output":"hello"}""",
                thoughtSignature = "result-",
            ),
            responseChunk(
                field = "codeExecutionResult",
                value = """{"id":"exec-1","outcome":"OUTCOME_OK","output":"\n"}""",
                thoughtSignature = "signature",
            ),
            """{"candidates":[{"finishReason":"STOP","index":0}]}""",
        )
        val client = GoogleLLMClient(httpClient = streamingTransport(model.id, chunks))

        val frames = client.executeStreaming(
            Prompt(listOf(Message.User("calculate", RequestMetaInfo.Empty)), "stream"),
            model,
        ).toList()
        val streamed = frames.toMessageResponse()
        val nonStream = client.processGoogleCandidate(
            Json.decodeFromString<GoogleResponse>(
                """
                {
                  "candidates": [{
                    "content": {"role": "model", "parts": [
                      {
                        "executableCode": {"id": "exec-1", "language": "PYTHON", "code": "print('hello')"},
                        "thoughtSignature": "code-signature"
                      },
                      {
                        "codeExecutionResult": {"id": "exec-1", "outcome": "OUTCOME_OK", "output": "hello\n"},
                        "thoughtSignature": "result-signature"
                      }
                    ]},
                    "finishReason": "STOP",
                    "index": 0
                  }]
                }
                """.trimIndent()
            ).candidates.single(),
            ResponseMetaInfo.Empty,
        )

        streamed shouldBe nonStream
        frames.filterIsInstance<StreamFrame.HostedExecutionUpdate>().map { it.update } shouldBe listOf(
            MessagePart.HostedExecution.Request(
                code = "print('hello')",
                executionId = "exec-1",
                providerItemId = "exec-1",
            ),
            MessagePart.HostedExecution.Result(
                output = "hello\n",
                exitCode = 0,
                executionId = "exec-1",
                providerItemId = "exec-1",
            ),
        )
    }

    private fun responseChunk(field: String, value: String, thoughtSignature: String): String =
        """
            {
              "candidates": [{
                "content": {
                  "role": "model",
                  "parts": [{"$field": $value, "thoughtSignature": "$thoughtSignature"}]
                },
                "index": 0
              }]
            }
        """.trimIndent()
}

private fun streamingTransport(modelId: String, chunks: List<String>): KoogHttpClient = object : KoogHttpClient {
    override val clientName: String = "GeminiStreamingTestClient"

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
    ): R = error("POST is not expected")

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
        path shouldBe "v1beta/models/$modelId:streamGenerateContent"
        requestBody.shouldBeInstanceOf<GoogleRequest>()
        chunks.forEach { chunk ->
            if (dataFilter(chunk)) {
                processStreamingChunk(decodeStreamingResponse(chunk))?.let { emit(it) }
            }
        }
    }

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> = error("Lines are not expected")

    override fun close() = Unit
}
