package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.reflect.KClass
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenAILLMClientTest {

    @Test
    fun testAstraDefaultsToResponsesAndHonoursExplicitChat() {
        val model = OpenAIModels.Chat.GPT6Astra
        assertIs<OpenAIResponsesParams>(client.determineParams(LLMParams(), model))
        assertIs<OpenAIChatParams>(client.determineParams(OpenAIChatParams(), model))
        assertIs<OpenAIResponsesParams>(client.determineParams(LLMParams(), model.copy(id = "astra-deployment")))
    }

    @Test
    fun testAstraPreservesSupportedReasoningAndOmitsSamplingOnBothEndpoints() {
        val efforts = listOf(
            null,
            ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH,
            ReasoningEffort.XHIGH,
            ReasoningEffort.MAX
        )
        val additional = mapOf(
            "temperature" to JsonPrimitive(0.5),
            "top_p" to JsonPrimitive(0.9),
            "top_logprobs" to JsonPrimitive(5),
            "logprobs" to JsonPrimitive(true),
            "custom_option" to JsonPrimitive("preserved"),
        )
        listOf(OpenAIModels.Chat.GPT6Astra, OpenAIModels.Chat.GPT6Astra.copy(id = "astra-deployment"))
            .forEach { model ->
                efforts.forEach { effort ->
                    val chat = chatRequest(
                        model,
                        OpenAIChatParams(
                            reasoningEffort = effort,
                            temperature = 0.4,
                            topLogprobs = 5,
                            logprobs = true,
                            additionalProperties = additional,
                        )
                    )
                    val responses = responsesRequest(
                        model,
                        OpenAIResponsesParams(
                            reasoning = effort?.let { ReasoningConfig(effort = it) },
                            temperature = 0.4,
                            topLogprobs = 5,
                            logprobs = true,
                            include = listOf(OpenAIInclude.OUTPUT_TEXT_LOGPROBS, OpenAIInclude.INPUT_IMAGE_URL),
                            stateless = true,
                            additionalProperties = additional,
                        )
                    )
                    listOf(chat, responses).forEach { request ->
                        request["model"]?.jsonPrimitive?.content shouldBe model.id
                        listOf("temperature", "top_p", "top_logprobs", "logprobs").forEach {
                            request.containsKey(it) shouldBe false
                        }
                        request["custom_option"]?.jsonPrimitive?.content shouldBe "preserved"
                    }
                    chat["reasoning_effort"]?.jsonPrimitive?.content shouldBe effort?.name?.lowercase()
                    responses["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content shouldBe
                        effort?.name?.lowercase()
                    responses["include"]?.jsonArray?.map { it.jsonPrimitive.content } shouldBe
                        listOf("message.input_image.image_url", "reasoning.encrypted_content")
                    chatRequest(model, OpenAIChatParams(topP = 0.9)).containsKey("top_p") shouldBe false
                    responsesRequest(model, OpenAIResponsesParams(topP = 0.9)).containsKey("top_p") shouldBe false
                }
            }
    }

    @Test
    fun testAstraRejectsUnsupportedReasoningEfforts() {
        listOf(ReasoningEffort.NONE, ReasoningEffort.MINIMAL).forEach { effort ->
            val chatFailure = assertFailsWith<IllegalArgumentException> {
                chatRequest(OpenAIModels.Chat.GPT6Astra, OpenAIChatParams(reasoningEffort = effort))
            }
            val responsesFailure = assertFailsWith<IllegalArgumentException> {
                responsesRequest(
                    OpenAIModels.Chat.GPT6Astra,
                    OpenAIResponsesParams(
                        reasoning = ReasoningConfig(effort = effort),
                    )
                )
            }
            chatFailure.message shouldBe responsesFailure.message
            chatFailure.message?.contains("Use low instead of none or minimal") shouldBe true
        }
    }

    @Test
    fun testAstraRawPropertiesCannotBypassReasoningToolsOrIncludeRestrictions() {
        val model = OpenAIModels.Chat.GPT6Astra
        listOf("none", "minimal", "ultra").forEach { effort ->
            assertFailsWith<IllegalArgumentException> {
                chatRequest(
                    model,
                    OpenAIChatParams(
                        additionalProperties = mapOf(
                            "reasoning_effort" to JsonPrimitive(effort),
                        )
                    )
                )
            }
            assertFailsWith<IllegalArgumentException> {
                responsesRequest(
                    model,
                    OpenAIResponsesParams(
                        additionalProperties = mapOf(
                            "reasoning" to buildJsonObject { put("effort", JsonPrimitive(effort)) },
                        )
                    )
                )
            }
        }
        listOf(
            "tools" to Json.parseToJsonElement("""[{"type":"function","function":{"name":"lookup"}}]"""),
            "tool_choice" to JsonPrimitive("auto"),
            "parallel_tool_calls" to JsonPrimitive(false),
        ).forEach { property ->
            assertFailsWith<IllegalArgumentException> {
                chatRequest(model, OpenAIChatParams(additionalProperties = mapOf(property)))
            }
        }
        val include = mapOf(
            "include" to buildJsonArray {
                add(JsonPrimitive("message.output_text.logprobs"))
                add(JsonPrimitive("message.input_image.image_url"))
            }
        )
        responsesRequest(model, OpenAIResponsesParams(additionalProperties = include))
            .get("include")?.jsonArray?.map { it.jsonPrimitive.content } shouldBe listOf("message.input_image.image_url")
        responsesRequest(OpenAIModels.Chat.GPT4o, OpenAIResponsesParams(additionalProperties = include))
            .get("include") shouldBe include["include"]
        chatRequest(
            OpenAIModels.Chat.GPT4o,
            OpenAIChatParams(
                additionalProperties = mapOf(
                    "reasoning_effort" to JsonPrimitive("none"),
                )
            )
        )["reasoning_effort"]?.jsonPrimitive?.content shouldBe "none"
    }

    @Test
    fun testOlderModelsRetainSamplingAndLogprobControls() {
        val chat = chatRequest(
            OpenAIModels.Chat.GPT4o,
            OpenAIChatParams(
                temperature = 0.4,
                topLogprobs = 5,
                logprobs = true,
            )
        )
        val responses = responsesRequest(
            OpenAIModels.Chat.GPT4o,
            OpenAIResponsesParams(
                temperature = 0.4,
                topLogprobs = 5,
                logprobs = true,
                include = listOf(OpenAIInclude.OUTPUT_TEXT_LOGPROBS),
            )
        )
        listOf(chat, responses).forEach { request ->
            request["temperature"]?.jsonPrimitive?.double shouldBe 0.4
            request["top_logprobs"]?.jsonPrimitive?.content shouldBe "5"
        }
        chat["logprobs"]?.jsonPrimitive?.content shouldBe "true"
        responses["include"]?.jsonArray?.single()?.jsonPrimitive?.content shouldBe "message.output_text.logprobs"
        chatRequest(OpenAIModels.Chat.GPT4o, OpenAIChatParams(topP = 0.9))
            .get("top_p")?.jsonPrimitive?.double shouldBe 0.9
        responsesRequest(OpenAIModels.Chat.GPT4o, OpenAIResponsesParams(topP = 0.9))
            .get("top_p")?.jsonPrimitive?.double shouldBe 0.9
    }

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

    @Test
    fun `Chat Completions passes arbitrary chat template kwargs unchanged`() {
        val chatTemplateKwargs = buildJsonObject {
            put("thinking", JsonPrimitive(true))
            put("thinking_mode", JsonPrimitive("adaptive"))
            put(
                "provider_options",
                buildJsonArray {
                    add(JsonPrimitive(7))
                    add(buildJsonObject { put("nested", JsonPrimitive("value")) })
                },
            )
        }

        val request = chatRequest(
            OpenAIModels.Chat.GPT4o,
            OpenAIChatParams().withChatTemplateKwargs(chatTemplateKwargs),
        )

        request["chat_template_kwargs"]?.jsonObject shouldBe chatTemplateKwargs
        request["chat_template_kwargs"]?.jsonObject
            ?.get("provider_options")?.jsonArray?.size shouldBe 2
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
