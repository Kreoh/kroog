package ai.koog.integration.tests.client

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.models.ModelCatalogue
import ai.koog.prompt.models.ModelKind
import ai.koog.prompt.provider.ProviderApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Checks discovery across module boundaries without credentials or provider requests. */
class LatestModelCatalogueIntegrationTest {
    @Test
    fun testRecentProviderDefinitionsHaveConsumerCatalogueProfiles() {
        listOf(OpenAIModels.Chat.GPT6Sol, OpenAIModels.Chat.GPT6Luna, OpenAIModels.Chat.GPT6_1Sol).forEach {
            assertProfile(it, it.id, ProviderApi.OPENAI_RESPONSES)
        }
        listOf(AnthropicModels.Opus_5_5, AnthropicModels.Sonnet_5_5).forEach {
            assertProfile(it, it.id, ProviderApi.VERTEX_ANTHROPIC_MESSAGES)
        }
        assertProfile(GoogleModels.Gemini3_8Flash, "gemini-3.8-flash", ProviderApi.VERTEX_GEMINI_GENERATE_CONTENT)
        listOf(
            DeepSeekModels.DeepSeekV4_1Flash, DeepSeekModels.DeepSeekV4Flash,
            DeepSeekModels.DeepSeekV4FlashVisionExp, DeepSeekModels.DeepSeekV4Pro,
        ).forEach { assertProfile(it, it.id, ProviderApi.OPENAI_COMPATIBLE_CHAT_COMPLETIONS) }
    }

    @Test
    fun testBedrockDeploymentDefinitionsHaveMatchingSemanticRouteProfiles() {
        assertProfile(BedrockModels.AnthropicClaude55Opus, "claude-opus-5-5", ProviderApi.BEDROCK_CONVERSE)
        assertProfile(BedrockModels.AnthropicClaude55Sonnet, "claude-sonnet-5-5", ProviderApi.BEDROCK_CONVERSE)
        assertProfile(BedrockModels.OpenAIGpt6Astra, "gpt-6-astra", ProviderApi.BEDROCK_CONVERSE)
        assertProfile(BedrockModels.GoogleGemma4_31B, "gemma-4-31b", ProviderApi.OPENAI_COMPATIBLE_RESPONSES)
        assertProfile(BedrockModels.GoogleGemma4_26BA4B, "gemma-4-26b-a4b", ProviderApi.OPENAI_COMPATIBLE_RESPONSES)
        assertProfile(BedrockModels.GoogleGemma4E2B, "gemma-4-e2b", ProviderApi.OPENAI_COMPATIBLE_RESPONSES)
        assertProfile(
            BedrockModels.Embeddings.AmazonNova2MultimodalEmbeddings,
            "nova-2-multimodal-embeddings", ProviderApi.BEDROCK_EMBEDDINGS,
        )
    }

    private fun assertProfile(definition: LLModel, semanticId: String, api: ProviderApi) {
        val profile = assertNotNull(ModelCatalogue.find(semanticId, api), "Missing profile for ${definition.id} on $api")
        assertTrue(profile.maxInputTokens <= assertNotNull(definition.contextLength), "Input exceeds context for ${definition.id}")
        if (profile.kind == ModelKind.EMBEDDING) {
            assertEquals(0, profile.maxOutputTokens)
        } else {
            assertEquals(definition.maxOutputTokens, profile.outputTokenLimit, "Output limit for ${definition.id}")
        }
        assertEquals(
            LLMCapability.Vision.Image in definition.capabilities.orEmpty(),
            profile.supportsImages, "Image capability for ${definition.id}",
        )
        assertEquals(
            LLMCapability.Schema.JSON.Standard in definition.capabilities.orEmpty(),
            profile.structuredOutput, "Structured output for ${definition.id}",
        )
    }
}
