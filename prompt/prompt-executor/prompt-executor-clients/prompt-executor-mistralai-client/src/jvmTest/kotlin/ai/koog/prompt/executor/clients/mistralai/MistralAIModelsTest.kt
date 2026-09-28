package ai.koog.prompt.executor.clients.mistralai

import ai.koog.prompt.executor.clients.list
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MistralAIModelsTest {

    @Test
    fun testModelsHaveMistralAIProvider() {
        MistralAIModels.list().forEach { model ->
            assertSame(LLMProvider.MistralAI, model.provider, model.id)
        }
    }

    @Test
    fun testCatalogueContainsEveryDeclaredModelExactlyOnce() {
        val expected = listOf(
            MistralAIModels.Chat.MistralMedium31,
            MistralAIModels.Chat.MistralLarge21,
            MistralAIModels.Chat.MistralSmall2,
            MistralAIModels.Chat.MagistralMedium12,
            MistralAIModels.Chat.Codestral,
            MistralAIModels.Chat.DevstralMedium,
            MistralAIModels.Chat.Ministral3_3B,
            MistralAIModels.Chat.Ministral3_8B,
            MistralAIModels.Chat.Ministral3_14B,
            MistralAIModels.Embeddings.MistralEmbed,
            MistralAIModels.Embeddings.CodestralEmbed,
            MistralAIModels.Moderation.MistralModeration
        )
        assertEquals(expected, MistralAIModels.models)
        assertEquals(expected.size, expected.map { it.id }.distinct().size)
        val reflected = MistralAIModels.list()
        assertEquals(expected.size, reflected.size)
        expected.forEach { model ->
            assertSame(model, reflected.single { it.id == model.id })
        }
    }

    @Test
    fun testMinistral3ProfilesMatchPinnedAliasesAndLimits() {
        val profiles = listOf(
            MistralAIModels.Chat.Ministral3_3B to "ministral-3b-latest",
            MistralAIModels.Chat.Ministral3_8B to "ministral-8b-latest",
            MistralAIModels.Chat.Ministral3_14B to "ministral-14b-latest"
        )
        profiles.forEach { (model, id) ->
            assertEquals(id, model.id)
            assertSame(LLMProvider.MistralAI, model.provider)
            assertEquals(128_000L, model.contextLength, id)
            assertNull(model.maxOutputTokens, id)
            assertEquals(
                listOf(
                    LLMCapability.Temperature,
                    LLMCapability.Completion,
                    LLMCapability.Tools,
                    LLMCapability.ToolChoice,
                    LLMCapability.Schema.JSON.Basic,
                    LLMCapability.MultipleChoices
                ),
                model.capabilities,
                id
            )
            assertSame(model, MistralAIModels.models.single { it.id == id })
        }
    }

    @Test
    fun testMistralLargeRetainsLegacyFieldAndAliasWithExpandedContext() {
        val model = MistralAIModels.Chat.MistralLarge21
        assertEquals("mistral-large-latest", model.id)
        assertSame(LLMProvider.MistralAI, model.provider)
        assertEquals(256_000L, model.contextLength)
        assertNull(model.maxOutputTokens)
        assertEquals(
            listOf(
                LLMCapability.Temperature,
                LLMCapability.Completion,
                LLMCapability.Tools,
                LLMCapability.ToolChoice,
                LLMCapability.Schema.JSON.Basic,
                LLMCapability.Schema.JSON.Standard,
                LLMCapability.MultipleChoices
            ),
            model.capabilities
        )
        assertSame(model, MistralAIModels.models.single { it.id == "mistral-large-latest" })
    }
}
