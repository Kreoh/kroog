package ai.koog.prompt.executor.clients.openai

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.models.*
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class OpenAIPromptCacheBreakpointTest {
    private val model = OpenAIModels.Chat.GPT6_1Terra
    private val cacheable = PromptCacheControl(cacheable = true)

    private fun prompt() = Prompt.build("cache", OpenAIResponsesParams()) {
        system("stable instructions")
        user("stable context", cacheable)
        user("runtime context")
    }

    private fun response(modelId: String) = OpenAIResponsesAPIResponse(
        created = 1, id = "response", model = modelId,
        output = listOf(Item.OutputMessage(content = listOf(OutputContent.Text(emptyList(), "ok")), id = "message", status = OpenAIInputStatus.COMPLETED)),
        parallelToolCalls = false, status = OpenAIInputStatus.COMPLETED, text = OpenAITextConfig(),
    )

    private suspend fun requestFor(
        prompt: Prompt,
        model: LLModel = this.model,
        settings: OpenAIClientSettings = OpenAIClientSettings(),
    ): JsonObject {
        val transport = OpenAIResponsesParityTest.ScriptedResponsesTransport(ArrayDeque(listOf(response(model.id))))
        OpenAILLMClient(settings, transport).execute(prompt, model)
        return Json.parseToJsonElement(transport.requests.single()).jsonObject
    }

    private fun JsonObject.breakpoints(): List<JsonElement?> = getValue("input").jsonArray.flatMap { item ->
        item.jsonObject["content"]?.jsonArray.orEmpty().map { it.jsonObject["prompt_cache_breakpoint"] }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun testTextImageFileSerializeExplicitEnvelopeOnlyWhenMarked() {
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val marker = OpenAIPromptCacheBreakpoint("explicit")
        val contents = listOf<InputContent>(
            InputContent.Text("stable", marker),
            InputContent.Image(imageUrl = "https://example.com/image.png", promptCacheBreakpoint = marker),
            InputContent.File(fileId = "file_1", promptCacheBreakpoint = marker),
        )
        contents.forEach {
            assertEquals(buildJsonObject { put("mode", "explicit") },
                json.parseToJsonElement(json.encodeToString(it)).jsonObject["prompt_cache_breakpoint"])
        }
        assertFalse(Json.encodeToString<InputContent>(InputContent.Text("plain")).contains("prompt_cache_breakpoint"))
    }

    @Test
    fun testCacheableInputPartsBecomeExplicitBreakpoints() = runTest {
        val explicit = buildJsonObject { put("mode", "explicit") }
        val request = requestFor(prompt())
        assertEquals(listOf("developer", "user", "user"), request.getValue("input").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals(listOf(null, explicit, null), request.breakpoints())

        // The requested TTL is not part of OpenAI's envelope, and system text maps onto the developer message.
        val hourly = Prompt.build("cache", OpenAIResponsesParams()) {
            system("stable instructions", PromptCacheControl(cacheable = true, ttl = PromptCacheTtl.OneHour))
            user("question")
        }
        assertEquals(listOf(explicit, null), requestFor(hourly).breakpoints())
    }

    @Test
    fun testOnlyCacheableControlsOnInputPartsAreSent() = runTest {
        val skipped = Prompt.build("cache", OpenAIResponsesParams()) {
            system("intent only", PromptCacheControl(cacheable = false))
            user("question")
        }
        assertFalse(requestFor(skipped).toString().contains("prompt_cache_breakpoint"))

        val assistant = Prompt(
            id = "assistant", params = OpenAIResponsesParams(), messages = listOf(
                Message.User(listOf(MessagePart.Text("question")), RequestMetaInfo.Empty),
                Message.Assistant(listOf(MessagePart.Text("answer", cacheControl = cacheable)), ResponseMetaInfo.Empty),
                Message.User(listOf(MessagePart.Text("follow up")), RequestMetaInfo.Empty),
            ),
        )
        assertFalse(requestFor(assistant).toString().contains("prompt_cache_breakpoint"))
    }

    @Test
    fun testImageAndFileAttachmentsCarryBreakpoints() = runTest {
        val inputs = listOf<MessagePart.RequestPart>(
            MessagePart.Text("stable", cacheControl = cacheable),
            MessagePart.Attachment(AttachmentSource.Image(AttachmentContent.URL("https://example.com/a.png"), "png"), cacheable),
            MessagePart.Attachment(AttachmentSource.File(AttachmentContent.URL("https://example.com/a.pdf"), "pdf", "application/pdf"), cacheable),
        )
        val attached = Prompt(
            id = "attachments", params = OpenAIResponsesParams(),
            messages = listOf(Message.User(parts = inputs, metaInfo = RequestMetaInfo.Empty)),
        )
        val documentModel = model.copy(capabilities = model.capabilities.orEmpty() + LLMCapability.Document)
        val content = requestFor(attached, documentModel).getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray
        assertEquals(listOf("input_text", "input_image", "input_file"), content.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        content.forEach { assertEquals("explicit", it.jsonObject.getValue("prompt_cache_breakpoint").jsonObject.getValue("mode").jsonPrimitive.content) }
    }

    @Test
    fun testStreamingSendsBreakpoints() = runTest {
        val transport = OpenAIResponsesParityTest.ScriptedResponsesTransport(
            streamAttempts = ArrayDeque(listOf(listOf(OpenAIStreamEvent.ResponseCompleted(response(model.id), 1)))),
        )
        OpenAILLMClient(OpenAIClientSettings(), transport).executeStreaming(prompt(), model).toList()
        assertEquals(1, Json.parseToJsonElement(transport.requests.single()).jsonObject.breakpoints().count { it != null })
    }

    @Test
    fun testRequestsGateModelAndDialectAndPreserveAzureDeployment() = runTest {
        for (dialect in OpenAIResponsesDialect.entries) {
            for (supported in listOf(true, false)) {
                val semantic = if (supported) model else OpenAIModels.Chat.GPT4o
                val routed = if (dialect == OpenAIResponsesDialect.Azure) {
                    semantic.copy(provider = LLMProvider.OpenAI, id = "opaque-production-deployment")
                } else semantic
                val settings = OpenAIClientSettings(
                    responsesDialect = dialect,
                    declaredResponsesCapability = OpenAIResponsesCapability.Supported,
                    deployment = if (dialect == OpenAIResponsesDialect.Azure) routed.id else null,
                    apiVersion = if (dialect == OpenAIResponsesDialect.Azure) "2025-04-01-preview" else null,
                )
                val request = requestFor(prompt(), routed, settings)
                assertEquals(supported && dialect != OpenAIResponsesDialect.Compatible,
                    request.toString().contains("prompt_cache_breakpoint"), "$dialect supported=$supported")
                assertEquals(routed.id, request.getValue("model").jsonPrimitive.content)
            }
        }
    }
}
