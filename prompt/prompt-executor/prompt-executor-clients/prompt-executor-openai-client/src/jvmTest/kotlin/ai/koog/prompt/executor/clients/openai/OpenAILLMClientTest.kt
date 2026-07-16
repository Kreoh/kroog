package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.reflect.KClass

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenAILLMClientTest {

    fun openAiClientTestCases(): Stream<Arguments> =
        Stream.of(
            Arguments.of(
                LLMParams(),
                OpenAIModels.Chat.GPT4o,
                OpenAIChatParams::class,
            ),
            Arguments.of(
                LLMParams(),
                OpenAIModels.Chat.GPT5_5,
                OpenAIChatParams::class,
            ),
            Arguments.of(
                LLMParams(),
                OpenAIModels.Chat.GPT5_5Pro,
                OpenAIResponsesParams::class,
            ),
            Arguments.of(
                LLMParams(),
                OpenAIModels.Chat.GPT5_6Sol,
                OpenAIChatParams::class,
            ),
            Arguments.of(
                OpenAIChatParams(),
                OpenAIModels.Chat.GPT4o,
                OpenAIChatParams::class,
            ),
            Arguments.of(
                OpenAIResponsesParams(),
                OpenAIModels.Chat.GPT4o,
                OpenAIResponsesParams::class,
            ),
            Arguments.of(
                OpenAIChatParams(),
                OpenAIModels.Audio.GPT4oMiniAudio,
                OpenAIChatParams::class,
            )
        )

    @ParameterizedTest
    @MethodSource("openAiClientTestCases")
    fun `Should use determine Params by input params and model`(
        inputParams: LLMParams,
        model: LLModel,
        expectedClass: KClass<out OpenAIChatParams>
    ) {
        val client = OpenAILLMClient(apiKey = "dummy-key", httpClientFactory = KtorKoogHttpClient.Factory())
        val result = client.determineParams(
            params = inputParams,
            model = model,
        )

        result::class shouldBe expectedClass
    }

    @Test
    fun `GPT-5_6 Responses preserves max and suppresses temperature for positive reasoning`() {
        gpt5_6Models().forEach { model ->
            val request = responsesRequest(
                model,
                OpenAIResponsesParams(
                    temperature = 0.4,
                    reasoning = ReasoningConfig(effort = ReasoningEffort.MAX),
                ),
            )

            request["model"]?.jsonPrimitive?.content shouldBe model.id
            request["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content shouldBe "max"
            request.containsKey("temperature") shouldBe false
        }
    }

    @Test
    fun `GPT-5_6 Chat clamps max to xhigh and suppresses temperature`() {
        gpt5_6Models().forEach { model ->
            val request = chatRequest(
                model,
                OpenAIChatParams(temperature = 0.4, reasoningEffort = ReasoningEffort.MAX),
            )

            request["model"]?.jsonPrimitive?.content shouldBe model.id
            request["reasoning_effort"]?.jsonPrimitive?.content shouldBe "xhigh"
            request.containsKey("temperature") shouldBe false
        }
    }

    @Test
    fun `GPT-5_6 none preserves temperature on both endpoints`() {
        gpt5_6Models().forEach { model ->
            chatRequest(
                model,
                OpenAIChatParams(temperature = 0.4, reasoningEffort = ReasoningEffort.NONE),
            )["temperature"]?.jsonPrimitive?.double shouldBe 0.4
            responsesRequest(
                model,
                OpenAIResponsesParams(
                    temperature = 0.4,
                    reasoning = ReasoningConfig(effort = ReasoningEffort.NONE),
                ),
            )["temperature"]?.jsonPrimitive?.double shouldBe 0.4
        }
    }

    @Test
    fun `GPT-5_6 deployment copy keeps wire id and model behaviour while older models remain unchanged`() {
        val deployment = OpenAIModels.Chat.GPT5_6Terra.copy(id = "azure-terra-deployment")
        chatRequest(
            deployment,
            OpenAIChatParams(temperature = 0.4, reasoningEffort = ReasoningEffort.MAX),
        ).let { request ->
            request["model"]?.jsonPrimitive?.content shouldBe "azure-terra-deployment"
            request["reasoning_effort"]?.jsonPrimitive?.content shouldBe "xhigh"
            request.containsKey("temperature") shouldBe false
        }

        chatRequest(
            OpenAIModels.Chat.GPT5_5,
            OpenAIChatParams(temperature = 0.4, reasoningEffort = ReasoningEffort.XHIGH),
        ).let { request ->
            request["model"]?.jsonPrimitive?.content shouldBe "gpt-5.5"
            request["reasoning_effort"]?.jsonPrimitive?.content shouldBe "xhigh"
            request["temperature"]?.jsonPrimitive?.double shouldBe 0.4
        }
    }

    private fun chatRequest(model: LLModel, params: OpenAIChatParams): JsonObject =
        Json.parseToJsonElement(
            client.serializeChatRequest(model, params),
        ).jsonObject

    private fun responsesRequest(model: LLModel, params: OpenAIResponsesParams): JsonObject =
        Json.parseToJsonElement(
            client.serializeResponsesAPIRequest(
                messages = emptyList(),
                model = model,
                tools = null,
                toolChoice = null,
                params = params,
                stream = false,
            ),
        ).jsonObject

    private fun gpt5_6Models(): List<LLModel> = listOf(
        OpenAIModels.Chat.GPT5_6Sol,
        OpenAIModels.Chat.GPT5_6Terra,
        OpenAIModels.Chat.GPT5_6Luna,
    )

    private val client = RequestSerializingOpenAIClient()

    private class RequestSerializingOpenAIClient :
        OpenAILLMClient(apiKey = "dummy-key", httpClientFactory = KtorKoogHttpClient.Factory()) {
        fun serializeChatRequest(model: LLModel, params: OpenAIChatParams): String =
            serializeProviderChatRequest(
                messages = emptyList(),
                model = model,
                tools = null,
                toolChoice = null,
                params = params,
                stream = false,
            )
    }
}
