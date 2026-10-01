package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.openai.models.OpenAIEmbeddingBatchRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenAIEmbeddingBatchTest {
    @Test
    fun testBatchCancellationPropagates() = runTest {
        val engine = MockEngine { throw CancellationException("cancelled batch") }
        val client = OpenAILLMClient(
            apiKey = key,
            httpClientFactory = KtorKoogHttpClient.Factory(baseClient = HttpClient(engine)),
        )
        try {
            assertFailsWith<CancellationException> { client.embed(listOf("a"), model) }
        } finally {
            client.close()
        }
    }

    @Test
    fun testDuplicateAndOutOfRangeIndicesAreRejected() = runTest {
        for (indices in listOf(listOf(0, 0), listOf(-1, 1), listOf(0, 2))) {
            val body = """{"data":[${indices.joinToString { """{"embedding":[0.1],"index":$it}""" }}],"model":"text-embedding-3-small"}"""
            val client = clientReturning(body)
            try {
                assertFailsWith<IllegalArgumentException> { client.embed(listOf("a", "b"), model) }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun testNonEmbeddingModelRejectedWithoutTransport() = runTest {
        val engine = MockEngine { error("HTTP must not be called for an unsupported model") }
        val client = OpenAILLMClient(
            apiKey = key,
            httpClientFactory = KtorKoogHttpClient.Factory(baseClient = HttpClient(engine)),
        )
        try {
            assertFailsWith<IllegalArgumentException> { client.embed(listOf("a"), OpenAIModels.Chat.GPT4o) }
        } finally {
            client.close()
        }
    }

    private val key = "test-key"
    private val model = OpenAIModels.Embeddings.TextEmbedding3Small

    private fun clientReturning(body: String, expectedInputs: List<String>? = null): OpenAILLMClient {
        val engine = MockEngine { request ->
            if (expectedInputs != null) {
                assertEquals("https://api.openai.com/v1/embeddings", request.url.toString())
                val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals(model.id, payload.getValue("model").jsonPrimitive.content)
                assertEquals(expectedInputs, payload.getValue("input").jsonArray.map { it.jsonPrimitive.content })
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return OpenAILLMClient(
            apiKey = key,
            httpClientFactory = KtorKoogHttpClient.Factory(baseClient = HttpClient(engine)),
        )
    }

    @Test
    fun testBatchRequestSerialisesInputAsJsonArray() {
        val request = OpenAIEmbeddingBatchRequest(model = "text-embedding-3-small", input = listOf("a", "b"))
        val jsonString = Json.Default.encodeToString(OpenAIEmbeddingBatchRequest.serializer(), request)
        assertEquals("""{"model":"text-embedding-3-small","input":["a","b"]}""", jsonString)
    }

    @Test
    fun testEmbedReordersResultsByIndex() = runTest {
        // Response intentionally out of order (index 2, 0, 1) to prove sortedBy { index }.
        val body = """
            {
              "data": [
                {"embedding": [0.3, 0.3], "index": 2},
                {"embedding": [0.1, 0.1], "index": 0},
                {"embedding": [0.2, 0.2], "index": 1}
              ],
              "model": "text-embedding-3-small",
              "usage": {"prompt_tokens": 3, "total_tokens": 3}
            }
        """.trimIndent()

        val client = clientReturning(body, expectedInputs = listOf("a", "b", "c"))
        val result = try {
            client.embed(listOf("a", "b", "c"), model)
        } finally {
            client.close()
        }

        assertEquals(
            listOf(
                listOf(0.1, 0.1),
                listOf(0.2, 0.2),
                listOf(0.3, 0.3),
            ),
            result,
        )
    }

    @Test
    fun testEmbedThrowsWhenResponseSizeDoesNotMatchInputs() = runTest {
        val body = """
            {
              "data": [{"embedding": [0.1], "index": 0}],
              "model": "text-embedding-3-small"
            }
        """.trimIndent()

        val client = clientReturning(body)
        try {
            assertFailsWith<IllegalArgumentException> { client.embed(listOf("a", "b"), model) }
        } finally {
            client.close()
        }
    }

    @Test
    fun testEmbedReturnsEmptyForEmptyInputWithoutCallingApi() = runTest {
        val engine = MockEngine { error("HTTP should not be called for empty input") }
        val client = OpenAILLMClient(
            apiKey = key,
            httpClientFactory = KtorKoogHttpClient.Factory(baseClient = HttpClient(engine)),
        )

        try {
            assertEquals(emptyList<List<Double>>(), client.embed(emptyList(), model))
        } finally {
            client.close()
        }
    }
}
