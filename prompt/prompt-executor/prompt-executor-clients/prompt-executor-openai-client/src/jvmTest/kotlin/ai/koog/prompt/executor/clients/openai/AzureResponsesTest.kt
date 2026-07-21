package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.azure.AzureOpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.models.Item
import ai.koog.prompt.executor.clients.openai.models.OpenAIInputStatus
import ai.koog.prompt.executor.clients.openai.models.OpenAIResponsesAPIResponse
import ai.koog.prompt.executor.clients.openai.models.OpenAITextConfig
import ai.koog.prompt.executor.clients.openai.models.OutputContent
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.test.utils.CapturingKoogHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AzureResponsesTest {
    @Test
    fun testDatedPreviewAzureResponsesGoldenRequest() = runTest {
        val settings = AzureOpenAIClientSettings(
            endpoint = "https://resource.openai.azure.com/",
            deploymentName = "chat-deployment",
            apiVersion = "2026-01-01-preview",
        )
        val factory = CapturingFactory()

        val client = OpenAILLMClient(apiKey = "azure-key", settings = settings, httpClientFactory = factory)
        client.execute(
            prompt = Prompt(
                messages = listOf(Message.User("hello", RequestMetaInfo.Empty)),
                id = "azure-responses-golden",
                params = OpenAIResponsesParams(store = false, stateless = true),
            ),
            model = OpenAIModels.Chat.GPT4o,
        )

        assertEquals("https://resource.openai.azure.com/", factory.baseUrl)
        assertEquals(mapOf("api-key" to "azure-key"), factory.headers)
        assertEquals(mapOf("api-version" to "2026-01-01-preview"), factory.queryParameters)
        assertEquals("openai/responses", factory.transport.lastPath)
        assertEquals("chat-deployment", settings.deployment)
        assertEquals("2026-01-01-preview", settings.apiVersion)
        assertEquals(OpenAIResponsesDialect.Azure, settings.responsesDialect)
        assertEquals(OpenAIResponsesCapability.Supported, settings.responsesCapability)
        val expected = requireNotNull(
            javaClass.getResource("/ai/koog/prompt/executor/clients/openai/azure-responses-dated-preview-request.json")
        ).readText()
        assertEquals(
            Json.parseToJsonElement(expected),
            Json.parseToJsonElement(factory.transport.lastRequest.toString()),
        )
    }

    @Test
    fun testCurrentAzureResponsesUsesResourceScopedV1Path() = runTest {
        val settings = AzureOpenAIClientSettings(
            endpoint = "https://resource.openai.azure.com",
            deploymentName = "current-deployment",
            apiVersion = "v1",
        )
        val factory = CapturingFactory()
        val client = OpenAILLMClient(apiKey = "azure-key", settings = settings, httpClientFactory = factory)

        client.execute(
            prompt = Prompt(
                messages = listOf(Message.User("hello", RequestMetaInfo.Empty)),
                id = "azure-responses-v1",
                params = OpenAIResponsesParams(),
            ),
            model = OpenAIModels.Chat.GPT4o,
        )

        assertEquals("https://resource.openai.azure.com/", factory.baseUrl)
        assertEquals("openai/v1/responses", factory.transport.lastPath)
        assertEquals(mapOf("api-version" to "v1"), factory.queryParameters)
        val request = Json.parseToJsonElement(factory.transport.lastRequest.toString())
        assertEquals("current-deployment", request.jsonObject.getValue("model").jsonPrimitive.content)
    }

    private class CapturingFactory : KoogHttpClient.Factory {
        lateinit var baseUrl: String
        lateinit var headers: Map<String, String>
        lateinit var queryParameters: Map<String, String>
        lateinit var transport: CapturingKoogHttpClient

        override fun create(
            clientName: String,
            baseUrl: String,
            headers: Map<String, String>,
            queryParameters: Map<String, String>,
            requestTimeoutMillis: Long,
            connectTimeoutMillis: Long,
            socketTimeoutMillis: Long,
            json: Json,
        ): KoogHttpClient {
            this.baseUrl = baseUrl
            this.headers = headers
            this.queryParameters = queryParameters
            transport = CapturingKoogHttpClient(clientName) { response() }
            return transport
        }
    }

    private companion object {
        fun response(): OpenAIResponsesAPIResponse = OpenAIResponsesAPIResponse(
            created = 1,
            id = "response_azure",
            model = "deployment-model",
            output = listOf(
                Item.OutputMessage(
                    content = listOf(OutputContent.Text(emptyList(), "ok")),
                    id = "message_azure",
                    status = OpenAIInputStatus.COMPLETED,
                )
            ),
            parallelToolCalls = false,
            status = OpenAIInputStatus.COMPLETED,
            text = OpenAITextConfig(),
        )
    }
}
