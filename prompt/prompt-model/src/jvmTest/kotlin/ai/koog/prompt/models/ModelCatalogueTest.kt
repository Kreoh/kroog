package ai.koog.prompt.models

import ai.koog.prompt.provider.ProviderApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelCatalogueTest {
    @Test
    fun testCatalogueMatchesNormalisedKreLLMGolden() {
        val expected = assertNotNull(
            javaClass.getResourceAsStream("/model-catalogue/krellm-model-catalogue.txt")
        ).bufferedReader().use { it.readText().trimEnd() }

        assertEquals(expected, ModelCatalogue.normalisedSnapshot())
    }

    @Test
    fun testCatalogueContainsEveryCurrentKreLLMSemanticId() {
        assertEquals(expectedIds, ModelCatalogue.entries.map { it.id }.toSet())
        assertEquals(32, ModelCatalogue.entries.size)
        assertTrue(ModelCatalogue.validate(ModelCatalogue.entries).isEmpty())
    }

    @Test
    fun testNamedNewModelsRetainExactCapabilities() {
        val sonnet = assertNotNull(ModelCatalogue.find("claude-sonnet-5"))
        assertEquals(1_000_000, sonnet.maxInputTokens)
        assertEquals(128_000, sonnet.maxOutputTokens)
        assertTrue(sonnet.supportsImages)
        assertTrue(sonnet.structuredOutput)
        assertTrue(sonnet.hostedExecution)
        assertEquals(
            setOf(ProviderApi.VERTEX_ANTHROPIC_MESSAGES, ProviderApi.BEDROCK_ANTHROPIC_MESSAGES),
            sonnet.providerApis,
        )
        assertEquals(
            mapOf(
                "off" to 0.0,
                "low" to 0.2,
                "medium" to 0.4,
                "high" to 0.6,
                "xhigh" to 0.8,
                "max" to 1.0,
            ),
            (sonnet.reasoning as ReasoningSupport.Supported).efforts,
        )

        val flashLite = assertNotNull(ModelCatalogue.find("gemini-3.1-flash-lite"))
        assertEquals(1_048_576, flashLite.maxInputTokens)
        assertEquals(65_535, flashLite.maxOutputTokens)
        assertEquals(
            mapOf("minimal" to 0.0, "low" to 0.25, "medium" to 0.5, "high" to 1.0),
            (flashLite.reasoning as ReasoningSupport.Supported).efforts,
        )
        assertEquals(
            setOf(ProviderApi.VERTEX_GEMINI_GENERATE_CONTENT),
            flashLite.providerApis,
        )
    }

    @Test
    fun testAliasesAreExplicitAndCanonicalIdsResolve() {
        ModelCatalogue.entries.forEach { entry ->
            assertEquals(entry, ModelCatalogue.find(entry.id))
            entry.aliases.forEach { alias -> assertEquals(entry, ModelCatalogue.find(alias)) }
        }
        assertEquals(null, ModelCatalogue.find("unknown-model"))
    }

    @Test
    fun testValidationReportsDuplicateIdsAliasesAndAliasIdCollisions() {
        val base = ModelCatalogue.entries.first()
        val entries = listOf(
            base.copy(aliases = setOf("shared", "gpt-4.1-mini")),
            base.copy(aliases = setOf("shared")),
            ModelCatalogue.entries[1],
        )
        val kinds = ModelCatalogue.validate(entries).map { it.kind }.toSet()

        assertTrue(ModelCatalogueIssueKind.DUPLICATE_MODEL_ID in kinds)
        assertTrue(ModelCatalogueIssueKind.DUPLICATE_ALIAS in kinds)
        assertTrue(ModelCatalogueIssueKind.MODEL_ID_ALIAS_COLLISION in kinds)
    }

    @Test
    fun testValidationReportsLimitsEffortsTemperatureAndProviderContradictions() {
        val base = ModelCatalogue.entries.first()
        val invalidReasoning = ReasoningSupport.Supported(
            mode = ReasoningMode.TOKEN_BUDGET,
            efforts = mapOf("bad" to 1.5),
            minBudgetTokens = 10,
            maxBudgetTokens = 5,
        )
        val invalid = base.copy(
            maxInputTokens = 0,
            reasoning = invalidReasoning,
            temperature = TemperatureSupport(
                minimum = 2.0,
                maximum = 1.0,
                allowedReasoningEfforts = setOf("missing"),
                omittedProviderApis = setOf(ProviderApi.CODEX_RESPONSES),
            ),
            providerApis = setOf(ProviderApi.OPENAI_EMBEDDINGS),
        )
        val kinds = ModelCatalogue.validate(listOf(invalid)).map { it.kind }.toSet()

        assertTrue(ModelCatalogueIssueKind.INVALID_TOKEN_LIMIT in kinds)
        assertTrue(ModelCatalogueIssueKind.INVALID_REASONING in kinds)
        assertTrue(ModelCatalogueIssueKind.INVALID_REASONING_EFFORT in kinds)
        assertTrue(ModelCatalogueIssueKind.INVALID_TEMPERATURE in kinds)
        assertTrue(ModelCatalogueIssueKind.TEMPERATURE_PROVIDER_CONTRADICTION in kinds)
        assertTrue(ModelCatalogueIssueKind.PROVIDER_API_CONTRADICTION in kinds)
    }

    @Test
    fun testDeepSeekAzureRouteIsExplicitlyUnavailableWithoutResponsesEvidence() {
        val deepSeek = assertNotNull(ModelCatalogue.find("deepseek-3.2"))

        assertEquals(
            ModelProviderApiCompatibility.Unsupported(
                ModelProviderApiUnsupportedReason.MODEL_SUPPORT_NOT_ESTABLISHED
            ),
            deepSeek.compatibility(ProviderApi.AZURE_RESPONSES),
        )
        assertFalse(ProviderApi.OPENAI_COMPATIBLE_CHAT_COMPLETIONS in deepSeek.providerApis)
        assertEquals(
            ModelProviderApiCompatibility.Undeclared,
            deepSeek.compatibility(ProviderApi.OPENAI_COMPATIBLE_CHAT_COMPLETIONS),
        )
    }

    @Test
    fun testValidationRejectsProviderApiMarkedSupportedAndUnsupported() {
        val base = ModelCatalogue.entries.first()
        val invalid = base.copy(
            unsupportedProviderApis = mapOf(
                ProviderApi.OPENAI_RESPONSES to
                    ModelProviderApiUnsupportedReason.MODEL_SUPPORT_NOT_ESTABLISHED
            )
        )

        assertTrue(
            ModelCatalogue.validate(listOf(invalid)).any {
                it.kind == ModelCatalogueIssueKind.PROVIDER_API_SUPPORT_CONTRADICTION
            }
        )
    }

    @Test
    fun testValidationReportsUnsatisfiedExecutionRequirement() {
        val invalid = ModelCatalogue.entries.first().copy(
            providerApis = setOf(ProviderApi.OPENAI_COMPATIBLE_CHAT_COMPLETIONS),
            hostedExecution = true,
        )

        assertTrue(
            ModelCatalogue.validate(listOf(invalid)).any {
                it.kind == ModelCatalogueIssueKind.EXECUTION_REQUIREMENT_CONTRADICTION
            }
        )
    }

    @Test
    fun testNonGenerativeModelsDoNotDeclareTextCapabilities() {
        ModelCatalogue.entries.filter { it.kind != ModelKind.TEXT }.forEach { entry ->
            assertFalse(entry.structuredOutput)
            assertFalse(entry.hostedExecution)
        }
    }

    private companion object {
        val expectedIds = setOf(
            "gpt-4.1",
            "gpt-4.1-mini",
            "gpt-4.1-nano",
            "gpt-5",
            "gpt-5.1",
            "gpt-5.2",
            "gpt-5.4",
            "gpt-5.4-mini",
            "gpt-5.4-nano",
            "gpt-5.5",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-5-mini",
            "gpt-5-nano",
            "claude-4.5-haiku",
            "claude-4.6-sonnet",
            "claude-4.6-opus",
            "claude-4.7-opus",
            "claude-4.8-opus",
            "claude-fable-5",
            "claude-sonnet-5",
            "deepseek-3.2",
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-3.1-pro",
            "gemini-3.1-flash-lite",
            "gemini-3.5-flash",
            "gemini-3-flash",
            "text-embedding-3-large",
            "gpt-realtime",
            "gpt-realtime-whisper",
        )
    }
}
