package ai.koog.prompt.executor.clients.anthropic

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicEffort
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinkingDisplay
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicMessageRequest
import ai.koog.prompt.executor.clients.list
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.params.LLMParams
import io.kotest.matchers.collections.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AnthropicModelsTest {

    @Test
    fun `Anthropic models should have Anthropic provider`() {
        val models = AnthropicModels.list()

        models.forEach { model ->
            assertSame(
                expected = LLMProvider.Anthropic,
                actual = model.provider,
                message = "Anthropic model ${model.id} doesn't have Anthropic provider but ${model.provider}."
            )
        }
    }

    @Test
    fun `Claude 4_5 and newer models should support structured output`() {
        val modelsWithSchema = listOf(
            AnthropicModels.Fable_5,
            AnthropicModels.Fable_5_1,
            AnthropicModels.Haiku_4_5,
            AnthropicModels.Sonnet_4_5,
            AnthropicModels.Sonnet_4_6,
            AnthropicModels.Sonnet_5,
            AnthropicModels.Opus_4_5,
            AnthropicModels.Opus_4_6,
            AnthropicModels.Opus_4_7,
            AnthropicModels.Opus_4_8,
            AnthropicModels.Opus_5,
        )

        modelsWithSchema.forEach { model ->
            assertTrue(
                model.supports(LLMCapability.Schema.JSON.Standard),
                "Model ${model.id} should support Schema.JSON.Standard capability"
            )
        }
    }

    @Test
    fun `Pre-4_5 models should not support structured output`() {
        val modelsWithoutSchema = listOf(
            AnthropicModels.Sonnet_4,
            AnthropicModels.Opus_4,
            AnthropicModels.Opus_4_1,
        )

        modelsWithoutSchema.forEach { model ->
            assertTrue(
                !model.supports(LLMCapability.Schema.JSON.Standard),
                "Model ${model.id} should NOT support Schema.JSON.Standard capability"
            )
        }
    }

    @Test
    fun `AnthropicMessageRequest should use custom maxTokens when provided`() {
        val customMaxTokens = 4000
        val request = AnthropicMessageRequest(
            model = AnthropicModels.Opus_4_6.id,
            messages = emptyList(),
            maxTokens = customMaxTokens
        )

        assertEquals(customMaxTokens, request.maxTokens)
    }

    @Test
    fun `AnthropicMessageRequest should use default maxTokens when not provided`() {
        val request = AnthropicMessageRequest(
            model = AnthropicModels.Opus_4_6.id,
            messages = emptyList()
        )

        assertEquals(AnthropicMessageRequest.MAX_TOKENS_DEFAULT, request.maxTokens)
    }

    @Test
    fun `AnthropicMessageRequest should reject zero maxTokens`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            AnthropicMessageRequest(
                model = AnthropicModels.Opus_4_6.id,
                messages = emptyList(),
                maxTokens = 0
            )
        }
        assertEquals("maxTokens must be greater than 0, but was 0", exception.message)
    }

    @Test
    fun `AnthropicModels models should return all declared models`() {
        val reflectionModels = AnthropicModels.list().map { it.id }

        val models = AnthropicModels.models.map { it.id }

        assert(models.size == reflectionModels.size)

        reflectionModels.forEach { model ->
            models shouldContain model
        }
    }

    @Test
    fun `Anthropic thinking-capable models should advertise thinking capability`() {
        assertNotNull(AnthropicModels.Fable_5.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(AnthropicModels.Fable_5_1.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(AnthropicModels.Haiku_4_5.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(AnthropicModels.Sonnet_4.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(AnthropicModels.Opus_4_6.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(AnthropicModels.Opus_4_7.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(AnthropicModels.Opus_4_8.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(AnthropicModels.Opus_5.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(AnthropicModels.Sonnet_5.capabilities) shouldContain LLMCapability.Thinking
    }

    @Test
    fun `Claude Fable 5 should expose documented model profile`() {
        val model = AnthropicModels.Fable_5

        assertEquals("claude-fable-5", model.id)
        assertEquals(1_000_000, model.contextLength)
        assertEquals(128_000, model.maxOutputTokens)
        assertTrue(model.supports(LLMCapability.Vision.Image))
        assertTrue(model.supports(LLMCapability.Tools))
        assertTrue(model.supports(LLMCapability.ToolChoice))
    }

    @Test
    fun testClaude55ProfilesAndCompatibleRequests() {
        val client = AnthropicLLMClient(apiKey = "unused")
        listOf(AnthropicModels.Opus_5_5, AnthropicModels.Sonnet_5_5).forEach { model ->
            assertEquals(1_000_000, model.contextLength)
            assertEquals(128_000, model.maxOutputTokens)
            assertEquals(model.id, DEFAULT_ANTHROPIC_MODEL_VERSIONS_MAP[model])
            assertTrue(model in AnthropicModels.models)
            assertTrue(model.supports(LLMCapability.Tools))
            assertTrue(model.supports(LLMCapability.Thinking))
            assertFalse(model.supports(LLMCapability.ToolChoice))
            assertFalse(model.supports(LLMCapability.Temperature))
            listOf(false, true).forEach { stream ->
                val body = Json.parseToJsonElement(client.createAnthropicRequest(
                    prompt = prompt("claude-55", params = AnthropicParams(
                        temperature = 0.7, topP = 0.8, topK = 10,
                        thinking = AnthropicThinking.Adaptive(AnthropicThinkingDisplay.SUMMARIZED, AnthropicEffort.HIGH),
                        additionalProperties = mapOf("top_p" to JsonPrimitive(0.9), "custom" to JsonPrimitive(true)),
                    )) { user("Hello") }, tools = emptyList(), model = model, stream = stream,
                )).jsonObject
                listOf("temperature", "top_p", "top_k").forEach { assertFalse(it in body) }
                assertEquals(JsonPrimitive(model.id), body["model"])
                assertEquals(JsonPrimitive(true), body["custom"])
                assertEquals(JsonPrimitive("high"), body["output_config"]?.jsonObject?.get("effort"))
            }
        }
        assertEquals("claude-opus-5-5", AnthropicModels.Opus_5_5.id)
        assertEquals("claude-sonnet-5-5", AnthropicModels.Sonnet_5_5.id)
    }

    @Test
    fun testClaude55RejectsUnsupportedTypedAndRawRequests() {
        val client = AnthropicLLMClient(apiKey = "unused")
        listOf(AnthropicModels.Opus_5_5, AnthropicModels.Sonnet_5_5).forEach { model ->
            val invalid = listOf(
                AnthropicParams(thinking = AnthropicThinking.Disabled()),
                AnthropicParams(thinking = AnthropicThinking.Enabled(1024)),
                AnthropicParams(toolChoice = LLMParams.ToolChoice.Required),
                AnthropicParams(toolChoice = LLMParams.ToolChoice.Named("lookup")),
                AnthropicParams(additionalProperties = mapOf("thinking" to buildJsonObject {
                    put("type", JsonPrimitive("disabled"))
                })),
                AnthropicParams(additionalProperties = mapOf("tool_choice" to buildJsonObject {
                    put("type", JsonPrimitive("any"))
                })),
            )
            invalid.forEach { params ->
                assertFailsWith<IllegalArgumentException> {
                    client.createAnthropicRequest(prompt("invalid", params = params) { user("Hello") }, emptyList(), model, false)
                }
            }
            assertFailsWith<IllegalArgumentException> {
                client.createAnthropicRequest(prompt("prefill") { user("Hello"); assistant("The answer is") }, emptyList(), model, false)
            }
            listOf(LLMParams.ToolChoice.Auto, LLMParams.ToolChoice.None).forEach { choice ->
                client.createAnthropicRequest(prompt("valid", params = AnthropicParams(toolChoice = choice)) {
                    user("Hello")
                }, emptyList(), model, false)
            }
        }
    }

    @Test
    fun testSonnet55BetweenToolsThinkingAndEffortLimit() {
        val client = AnthropicLLMClient(apiKey = "unused")
        listOf("display", "budget_tokens", "block_binding").forEach { key ->
            assertFailsWith<IllegalArgumentException> {
                client.createAnthropicRequest(prompt("invalid-between-tools", params = AnthropicParams(
                    additionalProperties = mapOf("thinking" to buildJsonObject {
                        put("type", JsonPrimitive("between_tools"))
                        put(key, JsonPrimitive("unsupported"))
                    }),
                )) { user("Hello") }, emptyList(), AnthropicModels.Sonnet_5_5, false)
            }
        }
        listOf("low", "medium", "high", "xhigh", "max").forEach { effort ->
            val params = AnthropicParams(additionalProperties = mapOf(
                "thinking" to buildJsonObject { put("type", JsonPrimitive("between_tools")) },
                "output_config" to buildJsonObject { put("effort", JsonPrimitive(effort)) },
            ))
            val request = { client.createAnthropicRequest(prompt("between-tools", params = params) {
                user("Hello")
            }, emptyList(), AnthropicModels.Sonnet_5_5, false) }
            if (effort in listOf("xhigh", "max")) assertFailsWith<IllegalArgumentException> { request() }
            else assertTrue(request().contains("between_tools"))
        }
    }

    @Test
    fun testClaudeFable51ExposesExactProfileAndDefaultVersion() {
        val model = AnthropicModels.Fable_5_1

        assertEquals(LLMProvider.Anthropic, model.provider)
        assertEquals("claude-fable-5-1", model.id)
        assertEquals(1_000_000, model.contextLength)
        assertEquals(128_000, model.maxOutputTokens)
        assertFalse(model.supports(LLMCapability.Temperature))
        assertTrue(model.supports(LLMCapability.Tools))
        assertFalse(model.supports(LLMCapability.ToolChoice))
        assertTrue(model.supports(LLMCapability.Vision.Image))
        assertTrue(model.supports(LLMCapability.Document))
        assertTrue(model.supports(LLMCapability.Schema.JSON.Standard))
        assertTrue(model.supports(LLMCapability.Thinking))
        assertTrue(model.supports(LLMCapability.PromptCaching))
        assertTrue(model in AnthropicModels.models)
        assertEquals("claude-fable-5-1", DEFAULT_ANTHROPIC_MODEL_VERSIONS_MAP[model])
    }

    @Test
    fun `Claude Opus 4_8 should expose exact model profile and default version`() {
        val model = AnthropicModels.Opus_4_8

        assertEquals(LLMProvider.Anthropic, model.provider)
        assertEquals("claude-opus-4-8", model.id)
        assertEquals(AnthropicModels.Opus_4_7.capabilities, model.capabilities)
        assertEquals(1_000_000, model.contextLength)
        assertEquals(128_000, model.maxOutputTokens)
        AnthropicModels.models shouldContain model
        assertEquals("claude-opus-4-8", DEFAULT_ANTHROPIC_MODEL_VERSIONS_MAP[model])
    }

    @Test
    fun testClaudeOpus5ExposesExactProfileAndDefaultVersion() {
        val model = AnthropicModels.Opus_5

        assertEquals(LLMProvider.Anthropic, model.provider)
        assertEquals("claude-opus-5", model.id)
        assertEquals(1_000_000, model.contextLength)
        assertEquals(128_000, model.maxOutputTokens)
        assertFalse(model.supports(LLMCapability.Temperature))
        assertTrue(model.supports(LLMCapability.Tools))
        assertTrue(model.supports(LLMCapability.ToolChoice))
        assertTrue(model.supports(LLMCapability.Vision.Image))
        assertTrue(model.supports(LLMCapability.Document))
        assertTrue(model.supports(LLMCapability.Schema.JSON.Standard))
        assertTrue(model.supports(LLMCapability.Thinking))
        assertTrue(model.supports(LLMCapability.PromptCaching))
        assertTrue(model in AnthropicModels.models)
        assertEquals("claude-opus-5", DEFAULT_ANTHROPIC_MODEL_VERSIONS_MAP[model])
    }

    @Test
    fun testSonnet5ExposesUpstreamProfileAndDefaultVersion() {
        val model = AnthropicModels.Sonnet_5

        assertEquals(LLMProvider.Anthropic, model.provider)
        assertEquals("claude-sonnet-5", model.id)
        assertEquals(1_000_000, model.contextLength)
        assertEquals(128_000, model.maxOutputTokens)
        assertEquals(
            listOf(
                LLMCapability.Temperature,
                LLMCapability.Tools,
                LLMCapability.ToolChoice,
                LLMCapability.Vision.Image,
                LLMCapability.Document,
                LLMCapability.Completion,
                LLMCapability.Schema.JSON.Basic,
                LLMCapability.Schema.JSON.Standard,
                LLMCapability.Thinking,
                LLMCapability.PromptCaching,
            ),
            model.capabilities,
        )
        assertTrue(model in AnthropicModels.models)
        assertEquals("claude-sonnet-5", DEFAULT_ANTHROPIC_MODEL_VERSIONS_MAP[model])
    }

    @Test
    fun testSonnet5RequestUsesDefaultVersionAndExplicitAdaptiveThinking() {
        val client = AnthropicLLMClient(apiKey = "unused")
        listOf(false, true).forEach { adaptive ->
            val request = client.createAnthropicRequest(
                prompt = prompt(
                    "sonnet-5",
                    params = AnthropicParams(
                        temperature = 0.7,
                        maxTokens = 128_000,
                        thinking = if (adaptive) {
                            AnthropicThinking.Adaptive(
                                display = AnthropicThinkingDisplay.SUMMARIZED,
                                effort = AnthropicEffort.HIGH,
                            )
                        } else {
                            null
                        },
                    ),
                ) { user("Hello") },
                tools = emptyList(),
                model = AnthropicModels.Sonnet_5,
                stream = adaptive,
            )
            val body = Json.parseToJsonElement(request).jsonObject
            assertEquals("\"claude-sonnet-5\"", body.getValue("model").toString())
            assertEquals("128000", body.getValue("max_tokens").toString())
            assertEquals(adaptive.toString(), body.getValue("stream").toString())
            if (adaptive) {
                assertFalse("temperature" in body)
                assertEquals("\"adaptive\"", body.getValue("thinking").jsonObject.getValue("type").toString())
                assertEquals("\"high\"", body.getValue("output_config").jsonObject.getValue("effort").toString())
            } else {
                assertEquals("0.7", body.getValue("temperature").toString())
                assertFalse("thinking" in body)
            }
        }
    }

    @Test
    fun testModelsAfterOpus46OmitTemperatureFromRequests() {
        val client = AnthropicLLMClient(apiKey = "unused")
        val models = listOf(
            AnthropicModels.Opus_4_7,
            AnthropicModels.Opus_4_8,
            AnthropicModels.Fable_5,
            AnthropicModels.Fable_5_1,
            AnthropicModels.Opus_5,
        )

        models.forEach { model ->
            assertFalse(model.supports(LLMCapability.Temperature))
            val request = client.createAnthropicRequest(
                prompt = prompt(
                    "${model.id}-temperature",
                    params = LLMParams(
                        temperature = 0.7,
                        additionalProperties = mapOf("temperature" to kotlinx.serialization.json.JsonPrimitive(0.9)),
                    ),
                ) {
                    user("Hello")
                },
                tools = emptyList(),
                model = model,
                stream = false,
            )

            assertFalse("temperature" in Json.parseToJsonElement(request).jsonObject)
        }
    }

    @Test
    fun testTemperatureCapableAnthropicModelRetainsTypedTemperature() {
        val request = AnthropicLLMClient(apiKey = "unused").createAnthropicRequest(
            prompt = prompt("opus-4-6-temperature", params = LLMParams(temperature = 0.7)) {
                user("Hello")
            },
            tools = emptyList(),
            model = AnthropicModels.Opus_4_6,
            stream = false,
        )

        assertEquals(0.7, Json.parseToJsonElement(request).jsonObject.getValue("temperature").toString().toDouble())
    }
}
