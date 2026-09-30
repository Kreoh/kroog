package ai.koog.prompt.executor.clients.bedrock

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.MessagePart
import ai.koog.test.utils.CapturingKoogHttpClient
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

class BedrockMantleLLMClientTest {
    @Test
    fun testMantleUsesExplicitPathsAndBedrockModelIds() = runTest {
        val models = listOf(
            BedrockModels.GoogleGemma4_31B,
            BedrockModels.GoogleGemma4_26BA4B,
            BedrockModels.GoogleGemma4E2B,
            BedrockModels.OpenAIGpt6Astra,
            BedrockModels.OpenAIGpt6Astra.copy(id = "global.openai.gpt-6-astra"),
        )
        for (model in models) {
            for (responses in listOf(false, true)) {
                val expectedId = model.id.removePrefix("us.").removePrefix("global.")
                val mockHttp = HttpClient(MockEngine { request ->
                    assertEquals("bedrock-mantle.us-west-2.api.aws", request.url.host)
                    assertEquals(if (responses) "/openai/v1/responses" else "/openai/v1/chat/completions",
                        request.url.encodedPath)
                    assertEquals("Bearer test-bedrock-key", request.headers[HttpHeaders.Authorization])
                    assertEquals(expectedId, Json.parseToJsonElement((request.body as TextContent).text)
                        .jsonObject.getValue("model").jsonPrimitive.content)
                    val body = if (responses) """{"id":"resp-1","object":"response","created_at":1,
                        "model":"$expectedId","status":"completed","parallel_tool_calls":false,"text":{"format":{"type":"text"}},"output":[{"id":"msg-1","type":"message",
                        "role":"assistant","status":"completed","content":[{"type":"output_text","text":"PONG","annotations":[]}]}]}"""
                    else """{"id":"chat-1","object":"chat.completion","created":1,"model":"$expectedId",
                        "choices":[{"index":0,"message":{"role":"assistant","content":"PONG"},"finish_reason":"stop"}]}"""
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                })
                val client = BedrockMantleLLMClient("test-bedrock-key", KtorKoogHttpClient.Factory(mockHttp))
                try {
                    val params = if (responses) OpenAIResponsesParams(stateless = true) else OpenAIChatParams()
                    val reply = client.execute(Prompt.build("mantle", params = params) { user("Ping") }, model, emptyList())
                    assertEquals("PONG", (reply.parts.single() as MessagePart.Text).text)
                    assertEquals(LLMProvider.Bedrock, client.llmProvider())
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun testAstraRejectsUnsupportedMantleRegionBeforeProviderTraffic() = runTest {
        val client = BedrockMantleLLMClient(
            CapturingKoogHttpClient("unused") { error("No provider traffic expected") },
            BedrockRegions.EU_WEST_1,
        )
        assertFailsWith<IllegalArgumentException> {
            client.execute(Prompt.build("region") { user("Hello") }, BedrockModels.OpenAIGpt6Astra, emptyList())
        }
    }
}
