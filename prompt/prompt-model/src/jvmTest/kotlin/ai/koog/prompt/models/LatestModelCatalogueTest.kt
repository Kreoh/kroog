package ai.koog.prompt.models

import ai.koog.prompt.provider.HostedExecutionAcceptance
import ai.koog.prompt.provider.HostedExecutionAcceptanceUnsupportedReason
import ai.koog.prompt.provider.ProviderApi
import ai.koog.prompt.provider.ProviderCapabilityMatrix
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LatestModelCatalogueTest {
    @Test
    fun testLatestModelsResolveWithoutProviderClientRegistration() {
        val ids = setOf(
            "gpt-6-sol", "gpt-6-luna", "gpt-6.1-sol",
            "claude-opus-5-5", "claude-sonnet-5-5", "gemini-3.8-flash",
            "deepseek-flash", "deepseek-v4-pro",
            "gemma-4-31b", "gemma-4-26b-a4b", "gemma-4-e2b",
            "nova-2-multimodal-embeddings",
        )
        ids.forEach { id -> assertNotNull(ModelCatalogue.find(id), "Missing semantic profile: $id") }
        assertNull(ModelCatalogue.find("gpt-6-terra"))
        assertNull(ModelCatalogue.find("gpt-6.1-luna"))
        assertNull(ModelCatalogue.find("gpt-6.1-terra"))
    }

    @Test
    fun testSolAndLunaRetainInputBudgetAndConditionalSampling() {
        listOf("gpt-6-sol", "gpt-6-luna").forEach { id ->
            val model = assertNotNull(ModelCatalogue.find(id))
            assertEquals(ModelPublisher.OPENAI, model.publisher)
            assertEquals(922_000, model.maxInputTokens)
            assertEquals(128_000, model.maxOutputTokens)
            assertEquals(setOf("none", "low", "medium", "high", "xhigh", "max"), efforts(model))
            assertEquals(setOf("none"), model.temperature.allowedReasoningEfforts)
            assertEquals(setOf(ProviderApi.OPENAI_RESPONSES), model.providerApis)
            assertTrue(model.supportsImages)
            assertTrue(model.structuredOutput)
            assertTrue(model.hostedExecution)
        }
    }

    @Test
    fun testSol61HasMandatoryReasoningAndNoSampling() {
        val model = assertNotNull(ModelCatalogue.find("gpt-6.1-sol"))
        assertEquals(922_000, model.maxInputTokens)
        assertEquals(128_000, model.maxOutputTokens)
        assertEquals(setOf("low", "medium", "high", "xhigh", "max"), efforts(model))
        assertEquals(setOf(ProviderApi.OPENAI_RESPONSES), model.providerApis)
        assertEquals(model.providerApis, model.temperature.omittedProviderApis)
        assertTrue(model.supportsImages)
        assertTrue(model.structuredOutput)
        assertTrue(model.hostedExecution)
        assertEquals(ModelProviderApiCompatibility.Undeclared, model.compatibility(ProviderApi.CODEX_RESPONSES))
        assertEquals(ModelProviderApiCompatibility.Undeclared, model.compatibility(ProviderApi.AZURE_RESPONSES))
    }

    @Test
    fun testClaude55ProfilesDoNotOfferDisabledThinking() {
        listOf("claude-opus-5-5", "claude-sonnet-5-5").forEach { id ->
            val model = assertNotNull(ModelCatalogue.find(id))
            assertEquals(ModelPublisher.ANTHROPIC, model.publisher)
            assertEquals(1_000_000, model.maxInputTokens)
            assertEquals(128_000, model.maxOutputTokens)
            assertEquals(setOf("low", "medium", "high", "xhigh", "max"), efforts(model))
            assertEquals(
                setOf(ProviderApi.VERTEX_ANTHROPIC_MESSAGES, ProviderApi.BEDROCK_ANTHROPIC_MESSAGES, ProviderApi.BEDROCK_CONVERSE),
                model.providerApis,
            )
            assertEquals(model.providerApis, model.temperature.omittedProviderApis)
            assertTrue("application/pdf" in model.supportedMimeTypes)
            assertTrue(model.supportsImages)
            assertTrue(model.structuredOutput)
            assertTrue(model.hostedExecution)
        }
    }

    @Test
    fun testGemini38RetainsMultimodalProfileAndExactThinkingLevels() {
        val model = assertNotNull(ModelCatalogue.find("gemini-3.8-flash"))
        val previous = assertNotNull(ModelCatalogue.find("gemini-3.7-flash"))
        assertEquals(ModelPublisher.GOOGLE, model.publisher)
        assertEquals(1_048_576, model.maxInputTokens)
        assertEquals(65_536, model.maxOutputTokens)
        assertEquals(setOf("low", "medium", "high"), efforts(model))
        assertEquals(previous.supportedMimeTypes, model.supportedMimeTypes)
        assertEquals(previous.temperature, model.temperature)
        assertEquals(previous.providerApis, model.providerApis)
        assertTrue(model.structuredOutput)
        assertTrue(model.hostedExecution)
    }

    @Test
    fun testDeepSeekAliasesResolveToFlashWithImagesWhileProStaysTextOnly() {
        val flash = assertNotNull(ModelCatalogue.find("deepseek-flash"))
        listOf("deepseek-v4-flash", "deepseek-v4-flash-vision-exp").forEach { alias ->
            assertEquals(flash, ModelCatalogue.find(alias))
        }
        val pro = assertNotNull(ModelCatalogue.find("deepseek-v4-pro"))
        listOf(flash, pro).forEach { model ->
            assertEquals(ModelPublisher.DEEPSEEK, model.publisher)
            assertEquals(1_000_000, model.maxInputTokens)
            assertEquals(384_000, model.maxOutputTokens)
            assertEquals(setOf("none", "low", "high", "max"), efforts(model))
            assertEquals(setOf("none"), model.temperature.allowedReasoningEfforts)
            assertEquals(setOf(ProviderApi.OPENAI_COMPATIBLE_CHAT_COMPLETIONS, ProviderApi.OPENAI_COMPATIBLE_RESPONSES), model.providerApis)
            assertFalse(model.hostedExecution)
            assertTrue(model.structuredOutput)
        }
        assertTrue(flash.supportsImages)
        assertEquals(setOf("text/plain"), pro.supportedMimeTypes)
        assertEquals(ModelProviderApiCompatibility.Undeclared, flash.compatibility(ProviderApi.BEDROCK_CONVERSE))
    }

    @Test
    fun testGemmaProfilesOnlyDeclareMantleAndPreserveUnknownOutputLimit() {
        mapOf("gemma-4-31b" to 256_000L, "gemma-4-26b-a4b" to 256_000L, "gemma-4-e2b" to 128_000L)
            .forEach { (id, input) ->
                val model = assertNotNull(ModelCatalogue.find(id))
                assertEquals(ModelPublisher.GOOGLE, model.publisher)
                assertEquals(input, model.maxInputTokens)
                assertEquals(0, model.maxOutputTokens)
                assertNull(model.outputTokenLimit)
                assertEquals(setOf("none", "high"), efforts(model))
                assertEquals(setOf(ProviderApi.OPENAI_COMPATIBLE_RESPONSES, ProviderApi.OPENAI_COMPATIBLE_CHAT_COMPLETIONS), model.providerApis)
                assertTrue(model.supportsImages)
                assertFalse(model.structuredOutput)
                assertFalse(model.hostedExecution)
                assertEquals(
                    ModelProviderApiCompatibility.Unsupported(ModelProviderApiUnsupportedReason.MODEL_API_NOT_SUPPORTED),
                    model.compatibility(ProviderApi.BEDROCK_CONVERSE),
                )
                assertNull(ModelCatalogue.find(id, ProviderApi.BEDROCK_CONVERSE))
                assertNotNull(ModelCatalogue.find(id, ProviderApi.OPENAI_COMPATIBLE_RESPONSES))
            }
        assertEquals(128_000, assertNotNull(ModelCatalogue.find("gpt-6.1-sol")).outputTokenLimit)
    }

    @Test
    fun testNovaDeclaresTextEmbeddingOnRuntimeWithoutGenerationCapabilities() {
        val model = assertNotNull(ModelCatalogue.find("nova-2-multimodal-embeddings"))
        assertEquals(ModelPublisher.AMAZON, model.publisher)
        assertEquals(ModelKind.EMBEDDING, model.kind)
        assertEquals(setOf(ProviderApi.BEDROCK_EMBEDDINGS), model.providerApis)
        assertEquals(8_192, model.maxInputTokens)
        assertEquals(0, model.maxOutputTokens)
        assertEquals(0, model.outputTokenLimit)
        assertEquals(ReasoningSupport.Unsupported, model.reasoning)
        assertEquals(setOf("text/plain"), model.supportedMimeTypes)
        assertFalse(model.supportsImages)
        assertFalse(model.structuredOutput)
        assertFalse(model.hostedExecution)
        assertNull(ModelCatalogue.find(model.id, ProviderApi.BEDROCK_CONVERSE))
    }

    @Test
    fun testAstraProviderProfilesDoNotAdvertiseRuntimeStructuredOutputOrMantleHostedExecution() {
        val semantic = assertNotNull(ModelCatalogue.find("gpt-6-astra"))
        val openAi = assertNotNull(ModelCatalogue.find(semantic.id, ProviderApi.OPENAI_RESPONSES))
        val runtime = assertNotNull(ModelCatalogue.find(semantic.id, ProviderApi.BEDROCK_CONVERSE))
        val mantle = assertNotNull(ModelCatalogue.find(semantic.id, ProviderApi.OPENAI_COMPATIBLE_RESPONSES))
        assertTrue(openAi.structuredOutput)
        assertTrue(openAi.hostedExecution)
        assertFalse(runtime.structuredOutput)
        assertFalse(runtime.hostedExecution)
        assertTrue(mantle.structuredOutput)
        assertFalse(mantle.hostedExecution)
        assertEquals(semantic.maxInputTokens, runtime.maxInputTokens)
        assertEquals(semantic.maxOutputTokens, mantle.maxOutputTokens)
        assertEquals(semantic.providerApis, semantic.temperature.omittedProviderApis)
        listOf(runtime, mantle).forEach { model ->
            assertEquals(
                HostedExecutionAcceptance.Unsupported(HostedExecutionAcceptanceUnsupportedReason.MODEL_DOES_NOT_SUPPORT_HOSTED_EXECUTION),
                ProviderCapabilityMatrix.acceptHostedExecution(
                    if (model === runtime) ProviderApi.BEDROCK_CONVERSE else ProviderApi.OPENAI_COMPATIBLE_RESPONSES,
                    model.id,
                ),
            )
            assertTrue(ModelCatalogue.validate(listOf(model)).isEmpty())
        }
        assertNull(ModelCatalogue.find(semantic.id, ProviderApi.AZURE_RESPONSES))
        assertNull(ModelCatalogue.find("unknown-model", ProviderApi.OPENAI_RESPONSES))
        // Provider-aware lookups must not mutate the canonical profile.
        assertEquals(openAi, ModelCatalogue.find(semantic.id))
    }

    @Test
    fun testProviderLookupResolvesAliasesAndRejectsMismatchedRoutes() {
        val flash = assertNotNull(ModelCatalogue.find("deepseek-v4-flash", ProviderApi.OPENAI_COMPATIBLE_CHAT_COMPLETIONS))
        assertEquals("deepseek-flash", flash.id)
        assertNull(ModelCatalogue.find(flash.id, ProviderApi.BEDROCK_CONVERSE))
        assertNull(ModelCatalogue.find("deepseek-3.2", ProviderApi.AZURE_RESPONSES))
        assertTrue(ModelCatalogue.validate(ModelCatalogue.entries).isEmpty())
    }

    private fun efforts(model: ModelCatalogueEntry): Set<String> =
        (model.reasoning as ReasoningSupport.Supported).efforts.keys
}
