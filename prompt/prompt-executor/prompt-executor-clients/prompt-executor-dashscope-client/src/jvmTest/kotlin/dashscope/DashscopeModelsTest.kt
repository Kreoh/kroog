package dashscope

import ai.koog.prompt.executor.clients.dashscope.DashscopeModels
import ai.koog.prompt.executor.clients.list
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import io.kotest.matchers.collections.shouldContain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class DashscopeModelsTest {

    @Test
    fun `DashScope models should have DashScope provider`() {
        val models = DashscopeModels.list()

        models.forEach { model ->
            assertSame(
                expected = LLMProvider.Alibaba,
                actual = model.provider,
                message = "DashScope model ${model.id} doesn't have DashScope provider but ${model.provider}."
            )
        }
    }

    @Test
    fun `DashscopeModels models should return all declared models`() {
        val reflectionModels = DashscopeModels.list().map { it.id }

        val models = DashscopeModels.models.map { it.id }

        assertEquals(reflectionModels.size, models.size)

        reflectionModels.forEach { model ->
            models shouldContain model
        }
    }
    @Test
    fun testNewQwenVersionsExposeExactProfiles() {
        val textCapabilities = setOf(
            LLMCapability.Completion,
            LLMCapability.Speculation,
            LLMCapability.Tools,
            LLMCapability.ToolChoice,
            LLMCapability.Temperature,
            LLMCapability.MultipleChoices,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.Thinking,
        )
        val profiles = listOf(
            Triple(DashscopeModels.QWEN3_5_PLUS, "qwen3.5-plus", 65_536),
            Triple(DashscopeModels.QWEN3_7_MAX, "qwen3.7-max", 65_536),
            Triple(DashscopeModels.QWEN3_8_MAX, "qwen3.8-max", 131_072),
        )
        profiles.forEach { (model, id, outputLimit) ->
            assertEquals(id, model.id)
            assertSame(LLMProvider.Alibaba, model.provider)
            assertEquals(1_000_000, model.contextLength)
            assertEquals(outputLimit.toLong(), model.maxOutputTokens)
            assertSame(model, DashscopeModels.models.single { it.id == id })
            assertSame(model, DashscopeModels.list().single { it.id == id })
            val visionCapabilities = when (id) {
                "qwen3.5-plus" -> setOf(LLMCapability.Vision.Image)
                "qwen3.8-max" -> setOf(LLMCapability.Vision.Image, LLMCapability.Vision.Video)
                else -> emptySet()
            }
            assertEquals(textCapabilities + visionCapabilities, assertNotNull(model.capabilities).toSet(), id)
        }
    }
}
