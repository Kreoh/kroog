package ai.koog.prompt.executor.llms.all

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.ModelResolutionException
import ai.koog.prompt.message.MessagePart
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SimpleDeepSeekExecutorTest {
    @Test
    fun testHelperRoutesRequestsToDeepSeek() = runTest {
        var calls = 0
        val http = HttpClient(
            MockEngine { request ->
                calls++
                assertEquals("https://api.deepseek.com/chat/completions", request.url.toString())
                assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals(DeepSeekModels.DeepSeekV4Flash.id, body.getValue("model").jsonPrimitive.content)
                respond(
                    """{"id":"test","object":"chat.completion","created":1,"system_fingerprint":"test","model":"deepseek-v4-flash","choices":[{"index":0,"message":{"role":"assistant","content":"Hello"},"finish_reason":"stop"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        )
        val executor = simpleDeepSeekExecutor("test-key", KtorKoogHttpClient.Factory(http))
        try {
            val response = executor.execute(Prompt.build("helper") { user("Hi") }, DeepSeekModels.DeepSeekV4Flash)
            assertEquals("Hello", response.parts.filterIsInstance<MessagePart.Text>().single().text)
            assertFailsWith<ModelResolutionException> {
                executor.execute(Prompt.build("foreign") { user("Hi") }, OpenAIModels.Chat.GPT4o)
            }
            assertEquals(1, calls)
        } finally {
            executor.close()
        }
    }

    @Test
    fun testJvmConvenienceHelperCreatesExecutor() = runTest {
        val executor = simpleDeepSeekExecutor("test-key")
        executor.close()
    }
}
