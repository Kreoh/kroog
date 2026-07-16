package ai.koog.prompt.executor.clients.openai

import ai.koog.prompt.executor.clients.list
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertNotNull

class OpenAIModelsTest {

    @Test
    fun `OpenAI models should have OpenAI provider`() {
        val models = OpenAIModels.list()

        models.forEach { model ->
            model.provider shouldBe LLMProvider.OpenAI
        }
    }

    @Test
    fun `OpenAIModels models should return all declared models`() {
        val reflectionModels = OpenAIModels.list().map { it.id }

        val models = OpenAIModels.models.map { it.id }

        assert(models.size == reflectionModels.size)

        reflectionModels.forEach { model ->
            models shouldContain model
        }
    }

    @Test
    fun `reasoning OpenAI models should advertise thinking capability`() {
        assertNotNull(OpenAIModels.Chat.O1.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(OpenAIModels.Chat.O3.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(OpenAIModels.Chat.O3Mini.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(OpenAIModels.Chat.O4Mini.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(OpenAIModels.Chat.GPT5.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(OpenAIModels.Chat.GPT5_4.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(OpenAIModels.Chat.GPT5_4Mini.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(OpenAIModels.Chat.GPT5_4Nano.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(OpenAIModels.Chat.GPT5_5.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(OpenAIModels.Chat.GPT5_5Pro.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(OpenAIModels.Chat.GPT5_6Sol.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(OpenAIModels.Chat.GPT5_6Terra.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(OpenAIModels.Chat.GPT5_6Luna.capabilities) shouldContain LLMCapability.Thinking
    }

    @Test
    fun `GPT-5_5 models should expose expected metadata and endpoint capabilities`() {
        OpenAIModels.Chat.GPT5_5.id shouldBe "gpt-5.5"
        OpenAIModels.Chat.GPT5_5.contextLength shouldBe 1_050_000
        OpenAIModels.Chat.GPT5_5.maxOutputTokens shouldBe 128_000
        assertNotNull(OpenAIModels.Chat.GPT5_5.capabilities) shouldContain LLMCapability.Document
        assertNotNull(OpenAIModels.Chat.GPT5_5.capabilities) shouldContain LLMCapability.OpenAIEndpoint.Completions
        assertNotNull(OpenAIModels.Chat.GPT5_5.capabilities) shouldContain LLMCapability.OpenAIEndpoint.Responses

        OpenAIModels.Chat.GPT5_5Pro.id shouldBe "gpt-5.5-pro"
        OpenAIModels.Chat.GPT5_5Pro.contextLength shouldBe 1_050_000
        OpenAIModels.Chat.GPT5_5Pro.maxOutputTokens shouldBe 128_000
        assertNotNull(OpenAIModels.Chat.GPT5_5Pro.capabilities) shouldContain LLMCapability.Document
        assertNotNull(OpenAIModels.Chat.GPT5_5Pro.capabilities) shouldContain LLMCapability.OpenAIEndpoint.Responses
        assertNotNull(OpenAIModels.Chat.GPT5_5Pro.capabilities) shouldNotContain LLMCapability.OpenAIEndpoint.Completions
    }

    @Test
    fun `GPT-5_6 models expose exact metadata capabilities and order`() {
        val models = listOf(
            OpenAIModels.Chat.GPT5_6Sol to "gpt-5.6-sol",
            OpenAIModels.Chat.GPT5_6Terra to "gpt-5.6-terra",
            OpenAIModels.Chat.GPT5_6Luna to "gpt-5.6-luna",
        )
        val expectedCapabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Speculation,
            LLMCapability.Tools,
            LLMCapability.ToolChoice,
            LLMCapability.Vision.Image,
            LLMCapability.MultipleChoices,
            LLMCapability.OpenAIEndpoint.Completions,
            LLMCapability.OpenAIEndpoint.Responses,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.Thinking,
        )

        models.forEach { (model, id) ->
            model.id shouldBe id
            model.contextLength shouldBe 1_050_000
            model.maxOutputTokens shouldBe 128_000
            assertNotNull(model.capabilities) shouldContainExactly expectedCapabilities
        }

        OpenAIModels.models
            .filter {
                it in listOf(
                    OpenAIModels.Chat.GPT5_5,
                    OpenAIModels.Chat.GPT5_6Sol,
                    OpenAIModels.Chat.GPT5_6Terra,
                    OpenAIModels.Chat.GPT5_6Luna,
                    OpenAIModels.Chat.GPT5Mini,
                )
            }.map { it.id } shouldContainExactly
            listOf("gpt-5.5", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5-mini")

        OpenAIModels.Chat.GPT5_6Sol.copy(id = "deployment-sol").let { deployment ->
            deployment.id shouldBe "deployment-sol"
            deployment.capabilities shouldBe OpenAIModels.Chat.GPT5_6Sol.capabilities
            deployment.contextLength shouldBe OpenAIModels.Chat.GPT5_6Sol.contextLength
            deployment.maxOutputTokens shouldBe OpenAIModels.Chat.GPT5_6Sol.maxOutputTokens
        }
    }
}
