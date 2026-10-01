package ai.koog.prompt.executor.clients.anthropic

import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AnthropicModelIdTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val client = AnthropicLLMClient(apiKey = "test-key")
    private val prompt = Prompt.build("test") { user("Hello") }

    private fun modelIdSentFor(model: LLModel): String {
        val request = client.createAnthropicRequest(prompt, emptyList(), model, false)
        return json.parseToJsonElement(request).jsonObject["model"]!!.jsonPrimitive.content
    }

    @Test
    fun testAPredefinedModelKeepsItsPinnedVersion() {
        assertEquals(
            DEFAULT_ANTHROPIC_MODEL_VERSIONS_MAP.getValue(AnthropicModels.Sonnet_4),
            modelIdSentFor(AnthropicModels.Sonnet_4),
        )
    }

    @Test
    fun testAModelTheMapDoesNotKnowIsSentUnderItsOwnId() {
        val newer = LLModel(
            provider = LLMProvider.Anthropic,
            id = "claude-opus-5",
            capabilities = listOf(LLMCapability.Completion, LLMCapability.Temperature, LLMCapability.Tools),
            contextLength = 1_000_000,
        )

        assertEquals("claude-opus-5", modelIdSentFor(newer))
    }

    @Test
    fun testACopiedPredefinedModelIsStillSentUnderItsId() {
        val narrowed = AnthropicModels.Sonnet_4.copy(contextLength = 100_000)

        assertEquals(AnthropicModels.Sonnet_4.id, modelIdSentFor(narrowed))
    }

    @Test
    fun testAModelOfAnotherProviderIsStillRefused() {
        val foreign = LLModel(
            provider = LLMProvider.OpenAI,
            id = "gpt-4o",
            capabilities = listOf(LLMCapability.Completion),
            contextLength = 128_000,
        )

        val failure = kotlin.runCatching { modelIdSentFor(foreign) }.exceptionOrNull()
        assertEquals(
            IllegalArgumentException::class,
            failure?.let { it::class },
            "an Anthropic client asked for an OpenAI model should say so, not send it",
        )
    }
}
