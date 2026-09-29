package ai.koog.prompt.executor.ollama.client

import ai.koog.prompt.executor.clients.list
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class OllamaModelsTest {

    @Test
    fun testQwenProfilesAndCatalogue() {
        val textCapabilities = listOf(
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Temperature,
            LLMCapability.Thinking,
            LLMCapability.ToolChoice,
            LLMCapability.Tools,
        )
        val profiles = listOf(
            Triple(OllamaModels.Alibaba.QWEN_3_6_27B, "qwen3.6:27b", textCapabilities),
            Triple(
                OllamaModels.Alibaba.QWEN_3_8_27B,
                "qwen3.8:27b",
                textCapabilities + LLMCapability.Vision.Image,
            ),
        )

        profiles.forEach { (model, id, capabilities) ->
            model.id shouldBe id
            model.provider shouldBe LLMProvider.Ollama
            model.contextLength shouldBe 256_000
            model.capabilities shouldBe capabilities
            OllamaModels.models.single { it.id == id } shouldBe model
        }
    }

    @Test
    fun testModelsHaveOllamaProvider() {
        val models = OllamaModels.list()

        models.forEach { model ->
            model.provider shouldBe LLMProvider.Ollama
        }
    }

    @Test
    fun testCatalogueContainsAllDeclaredModels() {
        val reflectionModels = OllamaModels.list().map { it.id }.toSet()

        val models = OllamaModels.models.map { it.id }.toSet()

        models.size shouldBe reflectionModels.size

        reflectionModels.forEach { model ->
            models shouldContain model
        }
    }
}
