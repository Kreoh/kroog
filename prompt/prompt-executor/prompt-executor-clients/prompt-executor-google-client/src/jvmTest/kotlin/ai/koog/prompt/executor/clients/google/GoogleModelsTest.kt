package ai.koog.prompt.executor.clients.google

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.list
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import io.kotest.matchers.collections.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// "Bad" request from Gemini with missing `parts` field
private val badRequest: String = """
    {
      "candidates": [
        {
          "content": {
            "role": "model"
          },
          "finishReason": "STOP",
          "index": 0
        }
      ],
      "usageMetadata": {
        "promptTokenCount": 36,
        "totalTokenCount": 146,
        "promptTokensDetails": [
          {
            "modality": "TEXT",
            "tokenCount": 36
          }
        ],
        "thoughtsTokenCount": 110
      },
      "modelVersion": "gemini-2.5-pro",
      "responseId": "B0esaJmqKv-0xN8P-dzlwQY"
    }
""".trimIndent()

class GoogleModelsTest {

    @Test
    fun `Google models should have Google provider`() {
        val models = GoogleModels.list()

        models.forEach { model ->
            assertSame(
                expected = LLMProvider.Google,
                actual = model.provider,
                message = "Google model ${model.id} doesn't have Google provider but ${model.provider}."
            )
        }
    }

    @Test
    fun `Test when FLASH_2_5 returns no parts GoogleLLMClient does not fail`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(badRequest),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val googleClient = GoogleLLMClient(
            apiKey = "test-key",
            httpClientFactory = KtorKoogHttpClient.Factory(HttpClient(mockEngine)) // Ktor client would always respond with the json from above
        )

        val response = googleClient.execute(
            prompt = prompt("test") { user("What is the capital of France?") },
            model = GoogleModels.Gemini2_5Flash
        )

        // When no parts returned -- parts should be empty
        assertEquals(0, response.parts.size)
        // Also let's check some other fields parsing
        assertEquals(36, response.metaInfo.inputTokensCount)
        assertEquals(146, response.metaInfo.totalTokensCount)
    }

    @Test
    fun `GoogleModels models should return all declared models`() {
        val reflectionModels = GoogleModels.list().map { it.id }

        val models = GoogleModels.models.map { it.id }

        assert(models.size == reflectionModels.size)

        reflectionModels.forEach { model ->
            models shouldContain model
        }
    }

    @Test
    fun `Gemini 3 preview models should advertise thinking capability`() {
        assertNotNull(GoogleModels.Gemini3_Flash_Preview.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(GoogleModels.Gemini3_1Pro_Preview.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(GoogleModels.Gemini3_1FlashLite_Preview.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(GoogleModels.Gemini3_1FlashLite.capabilities) shouldContain LLMCapability.Thinking
        assertNotNull(GoogleModels.Gemini3_5Flash.capabilities) shouldContain LLMCapability.Thinking
    }

    @Test
    fun testNewGeminiModelsExposeExactProfiles() {
        listOf(
            GoogleModels.Gemini3_5FlashLite,
            GoogleModels.Gemini3_6Flash,
            GoogleModels.Gemini3_7Flash,
        ).forEach { model ->
            assertEquals(LLMProvider.Google, model.provider)
            assertEquals(1_048_576, model.contextLength)
            assertEquals(65_536, model.maxOutputTokens)
            assertTrue(model.supports(LLMCapability.Completion))
            assertTrue(model.supports(LLMCapability.Vision.Image))
            assertTrue(model.supports(LLMCapability.Vision.Video))
            assertTrue(model.supports(LLMCapability.Audio))
            assertTrue(model.supports(LLMCapability.Document))
            assertTrue(model.supports(LLMCapability.Tools))
            assertTrue(model.supports(LLMCapability.ToolChoice))
            assertTrue(model.supports(LLMCapability.Schema.JSON.Standard))
            assertTrue(model.supports(LLMCapability.Thinking))
            assertFalse(model.supports(LLMCapability.Temperature))
            assertFalse(model.supports(LLMCapability.MultipleChoices))
            assertTrue(model in GoogleModels.models)
        }
        assertEquals("gemini-3.5-flash-lite", GoogleModels.Gemini3_5FlashLite.id)
        assertEquals("gemini-3.6-flash", GoogleModels.Gemini3_6Flash.id)
        assertEquals("gemini-3.7-flash", GoogleModels.Gemini3_7Flash.id)
    }

    @Test
    fun testSharedGoogleCapabilitiesIncludeDocuments() {
        assertTrue(GoogleModels.Gemini2_5Pro.supports(LLMCapability.Document))
        assertTrue(GoogleModels.Gemini3_5Flash.supports(LLMCapability.Document))
    }
}
