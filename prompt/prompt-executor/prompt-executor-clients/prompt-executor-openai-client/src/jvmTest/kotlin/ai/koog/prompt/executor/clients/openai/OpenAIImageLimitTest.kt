package ai.koog.prompt.executor.clients.openai

import ai.koog.prompt.executor.clients.ImageInputException
import ai.koog.prompt.executor.clients.openai.models.InputContent
import ai.koog.prompt.executor.clients.openai.models.Item
import ai.koog.test.utils.CapturingKoogHttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class OpenAIImageLimitTest {
    private val toolImage = Item.FunctionToolCallOutput("tool", JsonArray(listOf(buildJsonObject {
        put("type", "input_image")
        put("image_url", "https://example.test/screenshot.png")
    })))

    private fun serialize(settings: OpenAIClientSettings, userImages: Int) {
        val client = OpenAILLMClient(
            settings = settings,
            httpClient = CapturingKoogHttpClient("unused") { error("Unexpected request") },
        )
        val image = InputContent.Image(fileId = "image")
        val items = listOf(Item.InputMessage(List(userImages) { image }, "user"), toolImage)
        client.serializeResponsesAPIRequest(items, OpenAIModels.Chat.GPT5, null, null, OpenAIResponsesParams(), false)
    }

    @Test
    fun testHostedLimitsIncludeToolImages() {
        val hostedLimits = listOf(
            OpenAIClientSettings() to 1_500,
            OpenAIClientSettings(baseUrl = "https://eu.api.openai.com") to 1_500,
            OpenAIClientSettings(responsesDialect = OpenAIResponsesDialect.Azure) to 50,
        )
        for ((settings, limit) in hostedLimits) {
            serialize(settings, limit - 1)
            assertFailsWith<ImageInputException> { serialize(settings, limit) }
        }
    }

    @Test
    fun testCustomEndpointsDecideTheirOwnLimits() {
        serialize(OpenAIClientSettings(baseUrl = "https://example.test"), 1_500)
        serialize(OpenAIClientSettings(responsesDialect = OpenAIResponsesDialect.Compatible), 1_500)
    }
}
