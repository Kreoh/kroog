package ai.koog.prompt.executor.clients.openrouter

import ai.koog.prompt.executor.clients.list
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class OpenRouterModelsTest {

    @Test
    fun testModelsHaveOpenRouterProvider() {
        val models = OpenRouterModels.list()

        models.forEach { model ->
            assertSame(
                expected = LLMProvider.OpenRouter,
                actual = model.provider,
                message = "OpenRouter model ${model.id} doesn't have OpenRouter provider but ${model.provider}."
            )
        }
    }
    @Test
    fun testCatalogueContainsEverySupportedModelExactlyOnce() {
        val expected = listOf(
            OpenRouterModels.Phi4Reasoning,
            OpenRouterModels.Claude3Opus,
            OpenRouterModels.Claude3Sonnet,
            OpenRouterModels.Claude3Haiku,
            OpenRouterModels.Claude3_5Sonnet,
            OpenRouterModels.Claude3_7Sonnet,
            OpenRouterModels.Claude4Sonnet,
            OpenRouterModels.Claude4_1Opus,
            OpenRouterModels.Claude4_5Haiku,
            OpenRouterModels.Claude4_5Sonnet,
            OpenRouterModels.Claude4_5Opus,
            OpenRouterModels.Claude4_6Sonnet,
            OpenRouterModels.Claude4_6Opus,
            OpenRouterModels.Claude4_7Opus,
            OpenRouterModels.Claude4_8Opus,
            OpenRouterModels.Claude5Opus,
            OpenRouterModels.Claude5Sonnet,
            OpenRouterModels.Claude3VisionSonnet,
            OpenRouterModels.Claude3VisionOpus,
            OpenRouterModels.Claude3VisionHaiku,
            OpenRouterModels.GPT35Turbo,
            OpenRouterModels.GPT4,
            OpenRouterModels.GPT4o,
            OpenRouterModels.GPT4oMini,
            OpenRouterModels.GPT4Turbo,
            OpenRouterModels.GPT5,
            OpenRouterModels.GPT5Mini,
            OpenRouterModels.GPT5Nano,
            OpenRouterModels.GPT5Chat,
            OpenRouterModels.GPT_OSS_120b,
            OpenRouterModels.GPT5_2,
            OpenRouterModels.GPT5_2Pro,
            OpenRouterModels.GPT5_6Sol,
            OpenRouterModels.GPT5_6Terra,
            OpenRouterModels.GPT5_6Luna,
            OpenRouterModels.Llama3,
            OpenRouterModels.Llama3Instruct,
            OpenRouterModels.Mistral7B,
            OpenRouterModels.Mixtral8x7B,
            OpenRouterModels.DeepSeekV30324,
            OpenRouterModels.Gemini2_5FlashLite,
            OpenRouterModels.Gemini2_5Flash,
            OpenRouterModels.Gemini2_5Pro,
            OpenRouterModels.Gemini3ProPreview,
            OpenRouterModels.Qwen2_5,
            OpenRouterModels.Qwen3VL,
        )
        assertEquals(expected, OpenRouterModels.models)
        assertEquals(expected.size, expected.map { it.id }.distinct().size)
        val reflected = OpenRouterModels.list()
        expected.forEach { model ->
            assertSame(model, reflected.single { it === model }, model.id)
        }
    }

    @Test
    fun testNewProfilesMatchPinnedIdentifiersCapabilitiesAndLimits() {
        val profiles = listOf(
            OpenRouterModels.Claude4_7Opus to "anthropic/claude-opus-4.7",
            OpenRouterModels.Claude4_8Opus to "anthropic/claude-opus-4.8",
            OpenRouterModels.Claude5Opus to "anthropic/claude-opus-5",
            OpenRouterModels.Claude5Sonnet to "anthropic/claude-sonnet-5",
            OpenRouterModels.GPT5_6Sol to "openai/gpt-5.6-sol",
            OpenRouterModels.GPT5_6Terra to "openai/gpt-5.6-terra",
            OpenRouterModels.GPT5_6Luna to "openai/gpt-5.6-luna",
            OpenRouterModels.Gemini3ProPreview to "google/gemini-3-pro-preview",
        )
        profiles.forEach { (model, id) ->
            val isClaude = id.startsWith("anthropic/")
            val isGemini = id.startsWith("google/")
            assertEquals(id, model.id)
            assertSame(LLMProvider.OpenRouter, model.provider, id)
            assertEquals(
                when {
                    isClaude -> 1_000_000L
                    isGemini -> 1_048_576L
                    else -> 1_050_000L
                },
                model.contextLength,
                id
            )
            assertEquals(if (isGemini) 65_536L else 128_000L, model.maxOutputTokens, id)
            val capabilities = listOf(
                LLMCapability.Temperature,
                LLMCapability.Speculation,
                LLMCapability.Tools,
                LLMCapability.Completion,
                LLMCapability.Vision.Image,
            ) + (if (isClaude) listOf(LLMCapability.Schema.JSON.Basic) else emptyList()) + listOf(
                LLMCapability.Schema.JSON.Standard,
                LLMCapability.ToolChoice,
                LLMCapability.Thinking,
            )
            assertEquals(capabilities, model.capabilities, id)
            assertSame(model, OpenRouterModels.models.single { it.id == id }, id)
        }
    }
}
