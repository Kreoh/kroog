package deepseek

import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.list
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DeepSeekModelsTest {

    @Test
    fun testExperimentalVisionModelExposesExactProfile() {
        val model = DeepSeekModels.DeepSeekV4FlashVisionExp
        assertEquals("deepseek-v4-flash-vision-exp", model.id)
        assertSame(LLMProvider.DeepSeek, model.provider)
        assertSame(model, DeepSeekModels.models.single { it.id == model.id })
        assertSame(model, DeepSeekModels.list().single { it.id == model.id })
        assertEquals(1_000_000L, model.contextLength)
        assertEquals(384_000L, model.maxOutputTokens)
        assertEquals(
            listOf(
                LLMCapability.Completion,
                LLMCapability.Temperature,
                LLMCapability.Tools,
                LLMCapability.ToolChoice,
                LLMCapability.Schema.JSON.Basic,
                LLMCapability.Schema.JSON.Standard,
                LLMCapability.MultipleChoices,
                LLMCapability.Thinking,
                LLMCapability.Vision.Image,
            ),
            model.capabilities,
        )
        assertEquals(
            DeepSeekModels.DeepSeekV4Flash.capabilities,
            model.capabilities?.filterNot { it == LLMCapability.Vision.Image },
        )
    }

    @Test
    fun `DeepSeek models should have DeepSeek provider`() {
        val models = DeepSeekModels.list()

        models.forEach { model ->
            assertSame(
                expected = LLMProvider.DeepSeek,
                actual = model.provider,
                message = "DeepSeek model ${model.id} doesn't have DeepSeek provider but ${model.provider}."
            )
        }
    }

    @Test
    fun `GoogleModels models should return all declared models`() {
        val reflectionModels = DeepSeekModels.list().map { it.id }

        val models = DeepSeekModels.models.map { it.id }

        assertEquals(reflectionModels.size, models.size)

        reflectionModels.forEach { model ->
            models shouldContain model
        }
    }

    @Test
    fun `DeepSeek models should include v4 entries`() {
        DeepSeekModels.models.map { it.id } shouldContainAll listOf(
            "deepseek-v4-flash",
            "deepseek-v4-pro",
        )
    }
}
