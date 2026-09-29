package ai.koog.integration.tests.client

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.integration.tests.utils.Models
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicEffort
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinkingDisplay
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingConfig
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingLevel
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.toMessageResponse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Explicit live checks. Missing credentials fail the selected test rather than silently skipping it. */
class LatestModelsIntegrationTest {
    @Test
    fun integration_testOpenAISol() = runTest(timeout = 180.seconds) {
        checkOpenAI(OpenAIModels.Chat.GPT6Sol)
    }

    @Test
    fun integration_testOpenAILuna() = runTest(timeout = 180.seconds) {
        checkOpenAI(OpenAIModels.Chat.GPT6Luna)
    }

    @Test
    fun integration_testAnthropicOpus55() = runTest(timeout = 180.seconds) {
        checkAnthropic(AnthropicModels.Opus_5_5)
    }

    @Test
    fun integration_testAnthropicSonnet55() = runTest(timeout = 180.seconds) {
        checkAnthropic(AnthropicModels.Sonnet_5_5)
    }

    @Test
    fun integration_testGemini38Flash() = runTest(timeout = 180.seconds) {
        val model = GoogleModels.Gemini3_8Flash
        Models.assumeAvailable(model.provider)
        val key = credential("GEMINI_API_TEST_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY")
        val metadata = modelMetadata(
            "https://generativelanguage.googleapis.com/v1beta/models/${model.id}",
            mapOf("x-goog-api-key" to key),
        )
        assertEquals(model.contextLength, metadata.getValue("inputTokenLimit").jsonPrimitive.long)
        assertEquals(model.maxOutputTokens, metadata.getValue("outputTokenLimit").jsonPrimitive.long)
        GoogleLLMClient(key).use { client ->
            checkGeneration(client, model, GoogleParams(
                maxTokens = 2048,
                thinkingConfig = GoogleThinkingConfig(thinkingLevel = GoogleThinkingLevel.LOW),
            ))
        }
    }

    private suspend fun checkOpenAI(model: LLModel) {
        Models.assumeAvailable(model.provider)
        val key = credential("OPEN_AI_API_TEST_KEY", "OPENAI_API_KEY")
        val metadata = modelMetadata("https://api.openai.com/v1/models/${model.id}", mapOf("Authorization" to "Bearer $key"))
        assertEquals(model.id, metadata.getValue("id").jsonPrimitive.content)
        // OpenAI's Models API does not expose token limits. Catalogue limits are verified against official specifications.
        OpenAILLMClient(key).use { client ->
            checkGeneration(client, model, OpenAIResponsesParams(
                maxTokens = 2048, reasoning = ReasoningConfig(effort = ReasoningEffort.LOW), stateless = true,
            ))
            val response = client.execute(
                toolPrompt(OpenAIChatParams(maxTokens = 256, reasoningEffort = ReasoningEffort.NONE)),
                model, listOf(probeTool),
            )
            assertEquals("read_probe", response.parts.filterIsInstance<MessagePart.Tool.Call>().single().tool)
        }
    }

    private suspend fun checkAnthropic(model: LLModel) {
        Models.assumeAvailable(model.provider)
        val key = credential("ANTHROPIC_API_TEST_KEY", "ANTHROPIC_API_KEY")
        val metadata = modelMetadata("https://api.anthropic.com/v1/models/${model.id}", mapOf(
            "x-api-key" to key, "anthropic-version" to "2023-06-01",
        ))
        assertEquals(model.contextLength, metadata.getValue("max_input_tokens").jsonPrimitive.long)
        assertEquals(model.maxOutputTokens, metadata.getValue("max_tokens").jsonPrimitive.long)
        AnthropicLLMClient(key).use { client ->
            checkGeneration(client, model, AnthropicParams(
                maxTokens = 2048,
                thinking = AnthropicThinking.Adaptive(AnthropicThinkingDisplay.SUMMARIZED, AnthropicEffort.LOW),
            ))
        }
    }

    private suspend fun checkGeneration(client: LLMClient, model: LLModel, params: LLMParams) {
        val streamed = client.executeStreaming(prompt("live-stream", params = params) {
            user("Reply with the single word PONG.")
        }, model).toList().toMessageResponse()
        assertTrue(streamed.parts.filterIsInstance<MessagePart.Text>().joinToString { it.text }.contains("PONG"))
        assertTrue((streamed.metaInfo.inputTokensCount ?: 0) > 0)
        val called = client.execute(toolPrompt(params), model, listOf(probeTool))
        val call = called.parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals("read_probe", call.tool)
        val answer = client.execute(prompt("live-replay", params = params) {
            user("Call read_probe, then reply with only the returned value.")
            message(called)
            toolResult(tool = call.tool, output = "probe-739", id = call.id)
        }, model, listOf(probeTool))
        assertTrue(answer.parts.filterIsInstance<MessagePart.Text>().joinToString { it.text }.contains("probe-739"))
        assertTrue((answer.metaInfo.outputTokensCount ?: 0) > 0)
    }

    private fun toolPrompt(params: LLMParams) = prompt("live-tools", params = params) {
        user("Call read_probe, then reply with only the returned value.")
    }

    private fun credential(vararg names: String): String = names.firstNotNullOfOrNull {
        System.getenv(it)?.takeIf(String::isNotBlank)
    } ?: error("Set ${names.first()} to run this live test")

    private fun modelMetadata(url: String, headers: Map<String, String>): JsonObject {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).GET()
        headers.forEach { (name, value) -> request.header(name, value) }
        val response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
        // Do not include response bodies or authenticated request headers in assertion output.
        assertEquals(200, response.statusCode(), "Model metadata request failed")
        return Json.parseToJsonElement(response.body()).jsonObject
    }

    private val probeTool = ToolDescriptor("read_probe", "Read the probe value. Call this to obtain the value; it cannot be inferred.")
}
