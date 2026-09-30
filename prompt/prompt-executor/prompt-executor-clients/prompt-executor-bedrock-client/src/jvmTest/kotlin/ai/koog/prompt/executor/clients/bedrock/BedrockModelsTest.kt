package ai.koog.prompt.executor.clients.bedrock

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.list
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import io.kotest.matchers.collections.shouldContain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BedrockModelsTest {
    @Test
    fun testLatestModelsHaveRegisteredProviderProfiles() {
        val models = listOf(
            BedrockModels.AnthropicClaude55Opus,
            BedrockModels.AnthropicClaude55Sonnet,
            BedrockModels.OpenAIGpt6Astra,
            BedrockModels.GoogleGemma4_31B,
            BedrockModels.GoogleGemma4_26BA4B,
            BedrockModels.GoogleGemma4E2B,
            BedrockModels.Embeddings.AmazonNova2MultimodalEmbeddings,
        )
        assertEquals(listOf(
            "global.anthropic.claude-opus-5-5",
            "global.anthropic.claude-sonnet-5-5",
            "us.openai.gpt-6-astra",
            "google.gemma-4-31b",
            "google.gemma-4-26b-a4b",
            "google.gemma-4-e2b",
            "amazon.nova-2-multimodal-embeddings-v1:0",
        ), models.map { it.id })
        models.forEach { model ->
            assertSame(LLMProvider.Bedrock, model.provider)
            assertSame(model, BedrockModels.models.single { it.id == model.id })
        }
        assertEquals(AnthropicModels.Opus_5_5.capabilities, models[0].capabilities)
        assertEquals(AnthropicModels.Sonnet_5_5.capabilities, models[1].capabilities)
        assertEquals(listOf(256_000L, 256_000L, 128_000L), models.subList(3, 6).map { it.contextLength })
        models.subList(2, 6).forEach {
            assertTrue(it.supports(LLMCapability.Vision.Image))
            assertTrue(it.supports(LLMCapability.Thinking))
        }
        assertEquals(listOf(LLMCapability.Embed), models.last().capabilities)
    }


    @Test
    fun `BedrockModels models should have Bedrock provider`() {
        val models = BedrockModels.list()

        models.forEach { model ->
            assertSame(
                expected = LLMProvider.Bedrock,
                actual = model.provider,
                message = "Bedrock model ${model.id} doesn't have Bedrock provider but ${model.provider}."
            )
        }
    }

    @Test
    fun `BedrockModels models should return all declared models`() {
        val reflectionModels = BedrockModels.list().map { it.id }

        val models = BedrockModels.models.map { it.id }

        assert(models.size == reflectionModels.size)

        reflectionModels.forEach { model ->
            models shouldContain model
        }
    }

    @Test
    fun `Claude Fable 5 Bedrock model should expose documented model profile`() {
        val model = BedrockModels.AnthropicClaudeFable5

        assertEquals(LLMProvider.Bedrock, model.provider)
        assertEquals("us.anthropic.claude-fable-5", model.id)
        assertEquals(1_000_000, model.contextLength)
        assertEquals(128_000, model.maxOutputTokens)
        assertTrue(model.supports(LLMCapability.Vision.Image))
        assertTrue(model.supports(LLMCapability.Tools))
    }

    @Test
    fun testClaudeFable51BedrockModelExposesExactEffectiveProfiles() {
        val model = BedrockModels.AnthropicClaudeFable5_1

        assertEquals(LLMProvider.Bedrock, model.provider)
        assertEquals("us.anthropic.claude-fable-5-1", model.id)
        assertEquals(AnthropicModels.Fable_5_1.capabilities, model.capabilities)
        assertEquals(1_000_000, model.contextLength)
        assertEquals(128_000, model.maxOutputTokens)
        assertTrue(model.supports(LLMCapability.Tools))
        assertFalse(model.supports(LLMCapability.ToolChoice))
        assertFalse(model.supports(LLMCapability.Temperature))
        BedrockModels.models shouldContain model

        val globalModel = BedrockModel(
            model = AnthropicModels.Fable_5_1,
            modelId = "anthropic.claude-fable-5-1",
            inferenceProfilePrefix = BedrockInferencePrefixes.GLOBAL.prefix,
        ).effectiveModel
        assertEquals("global.anthropic.claude-fable-5-1", globalModel.id)
        assertEquals(AnthropicModels.Fable_5_1.capabilities, globalModel.capabilities)
    }

    @Test
    fun `Claude Opus 4_8 Bedrock model should expose exact effective profile`() {
        val model = BedrockModels.AnthropicClaude48Opus

        assertEquals(LLMProvider.Bedrock, model.provider)
        assertEquals("us.anthropic.claude-opus-4-8", model.id)
        assertEquals(AnthropicModels.Opus_4_8.capabilities, model.capabilities)
        assertEquals(1_000_000, model.contextLength)
        assertEquals(128_000, model.maxOutputTokens)
        BedrockModels.models shouldContain model
    }

    @Test
    fun testClaudeOpus5BedrockModelExposesExactEffectiveProfile() {
        val model = BedrockModels.AnthropicClaude5Opus

        assertEquals(LLMProvider.Bedrock, model.provider)
        assertEquals("us.anthropic.claude-opus-5", model.id)
        assertEquals(AnthropicModels.Opus_5.capabilities, model.capabilities)
        assertEquals(1_000_000, model.contextLength)
        assertEquals(128_000, model.maxOutputTokens)
        BedrockModels.models shouldContain model
    }

    @Test
    fun testClaudeSonnet5BedrockModelExposesExactEffectiveProfile() {
        val model = BedrockModels.models.single { it.id == "us.anthropic.claude-sonnet-5" }

        assertEquals(LLMProvider.Bedrock, model.provider)
        assertEquals(AnthropicModels.Sonnet_5.capabilities, model.capabilities)
        assertEquals(AnthropicModels.Sonnet_5.contextLength, model.contextLength)
        assertEquals(AnthropicModels.Sonnet_5.maxOutputTokens, model.maxOutputTokens)
        assertTrue(model.supports(LLMCapability.Thinking))
        assertTrue(model.supports(LLMCapability.Temperature))
        assertTrue(model.supports(LLMCapability.ToolChoice))
    }

    @Test
    fun testAmazonNova2LiteExposesExactEffectiveProfile() {
        val model = BedrockModels.models.single { it.id == "us.amazon.nova-2-lite-v1:0" }

        assertEquals(LLMProvider.Bedrock, model.provider)
        assertEquals(1_000_000, model.contextLength)
        assertEquals(
            requireNotNull(BedrockModels.AmazonNovaMicro.capabilities) + listOf(
                LLMCapability.Vision.Image,
                LLMCapability.Vision.Video,
                LLMCapability.Thinking,
            ),
            model.capabilities,
        )
        assertEquals(null, model.maxOutputTokens)
    }

}
