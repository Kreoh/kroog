package ai.koog.prompt.executor.clients.openai

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.params.LLMParams
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
    fun testToolResultBreakpointsOnSingleAndMultipartOutput() = runTest {
        val text = MessagePart.Text("partial", cacheControl = cacheable)
        val image = MessagePart.Attachment(AttachmentSource.Image(AttachmentContent.URL("https://example.com/image.png"), "png"))
        val file = MessagePart.Attachment(AttachmentSource.File(AttachmentContent.URL("https://example.com/ref.pdf"), "ref.pdf", "application/pdf"))
        val results = listOf(
            MessagePart.Tool.Result("a", "lookup", "unmarked"),
            MessagePart.Tool.Result("b", "lookup", listOf(text)),
            MessagePart.Tool.Result("c", "lookup", "complete", cacheControl = cacheable),
            MessagePart.Tool.Result("d", "lookup", listOf(text, image, file), cacheControl = cacheable),
            MessagePart.Tool.Result("e", "lookup", listOf(text), isError = true),
            MessagePart.Tool.Result("f", "lookup", listOf(text, MessagePart.Text("end")), isError = true),
        )
        val request = Prompt.build("results", OpenAIResponsesParams()) { user(results) }
        val output = requestFor(request, OpenAIModels.Chat.GPT6_2Astra).getValue("input").jsonArray.map {
            it.jsonObject.getValue("output")
        }
        val explicit = buildJsonObject { put("mode", "explicit") }
        fun markers(value: JsonElement) = value.jsonArray.map { it.jsonObject["prompt_cache_breakpoint"] }
        assertEquals(JsonPrimitive("unmarked"), output[0])
        assertEquals(listOf(explicit), markers(output[1]))
        assertEquals(listOf(explicit), markers(output[2]))
        assertEquals(listOf(explicit, null, explicit), markers(output[3]))
        assertEquals(listOf("input_text", "input_image", "input_file"), output[3].jsonArray.map { it.jsonObject["type"]?.jsonPrimitive?.content })
        assertEquals(listOf(explicit), markers(output[4]))
        assertEquals(listOf(null, explicit, null), markers(output[5]))
        val unsupported = requestFor(request, OpenAIModels.Chat.GPT4o).getValue("input").jsonArray
        assertEquals(JsonPrimitive("partial"), unsupported[1].jsonObject["output"])
        assertFalse("prompt_cache_breakpoint" in unsupported.toString())
    }

    @Test
    fun testSettingsReplayInOriginalPositionsWithUnchangedRequestDefaults() = runTest {
        val params = OpenAIResponsesParams(reasoning = ReasoningConfig(ReasoningEffort.LOW))
        val original = Prompt.build("settings", params) {
            system("Instructions")
            user("Start")
        }
        val schema = LLMParams.Schema.JSON.Standard("answer", buildJsonObject { put("type", "object") })
        val next = Prompt.build(original) {
            assistant("Started")
            openAIConfigurationUpdate(ReasoningEffort.HIGH, schema)
            user("Produce a report")
        }
        val restored = next.withMessages { Json.decodeFromString<List<Message>>(Json.encodeToString(it)) }
        val last = Prompt.build(restored) {
            assistant("{}")
            openAIConfigurationUpdate(plainText = true)
            user("Explain")
        }
        val astra = OpenAIModels.Chat.GPT6_2Astra.copy(id = "astra-deployment")
        val firstRequest = requestFor(original, astra)
        val secondRequest = requestFor(next, astra)
        val transport = OpenAIResponsesParityTest.ScriptedResponsesTransport(
            streamAttempts = ArrayDeque(listOf(listOf(OpenAIStreamEvent.ResponseCompleted(response(astra.id), 1)))),
        )
        OpenAILLMClient(OpenAIClientSettings(), transport).executeStreaming(last, astra).toList()
        val finalRequest = Json.parseToJsonElement(transport.requests.single()).jsonObject
        val secondInput = secondRequest.getValue("input").jsonArray
        val finalInput = finalRequest.getValue("input").jsonArray
        assertEquals(firstRequest.getValue("input").jsonArray, secondInput.take(2))
        assertEquals(secondInput, finalInput.take(secondInput.size))
        for (request in listOf(firstRequest, secondRequest, finalRequest)) {
            assertEquals(JsonPrimitive(astra.id), request["model"])
            assertEquals(JsonPrimitive("low"), request.getValue("reasoning").jsonObject["effort"])
            assertNull(request["text"])
        }
        val update = secondInput[3].jsonObject
        assertEquals(JsonPrimitive("configuration_update"), update["type"])
        assertEquals(JsonPrimitive("high"), update.getValue("reasoning").jsonObject["effort"])
        val format = update.getValue("text").jsonObject.getValue("format").jsonObject
        assertEquals(JsonPrimitive("json_schema"), format["type"])
        assertEquals(schema.schema, format["schema"])
        assertEquals(JsonPrimitive("answer"), format["name"])
        assertEquals(JsonPrimitive(true), format["strict"])
        val reset = finalInput[6].jsonObject
        assertNull(reset["reasoning"])
        assertEquals(JsonPrimitive("text"), reset.getValue("text").jsonObject.getValue("format").jsonObject["type"])
    }

    @Test
    fun testInvalidConfigurationUpdatesAreRejected() = runTest {
        assertFailsWith<IllegalArgumentException> { Prompt.build("empty") { openAIConfigurationUpdate() } }
        val astra = OpenAIModels.Chat.GPT6_2Astra.copy(id = "astra-deployment")
        val invalid = Prompt.build("invalid") {
            openAIConfigurationUpdate(ReasoningEffort.NONE)
            user("Continue")
        }
        assertFailsWith<IllegalArgumentException> { requestFor(invalid, astra) }
        val valid = Prompt.build("valid") {
            openAIConfigurationUpdate(ReasoningEffort.HIGH)
            user("Continue")
        }
        assertFailsWith<IllegalArgumentException> { requestFor(valid.withParams(OpenAIResponsesParams()), OpenAIModels.Chat.GPT4o) }
        assertFailsWith<IllegalArgumentException> { requestFor(valid.withParams(OpenAIChatParams()), astra) }
    }

    @Test
    fun testExplicitOnlyRetentionAndCacheDiagnosticsSurviveParameterCopies() = runTest {
        val diagnostics = buildJsonObject { put("comparison_response_id", "previous") }
        val params = OpenAIResponsesParams(additionalProperties = mapOf("prompt_cache_options" to diagnostics))
            .withPromptCacheOptions(OpenAIPromptCacheMode.Explicit, OpenAIPromptCacheTtl.TwelveHours)
        val sent = requestFor(prompt().withParams(params.asStateless()), OpenAIModels.Chat.GPT6_2Astra)
        assertEquals(buildJsonObject {
            put("mode", "explicit")
            put("ttl", "12h")
            put("comparison_response_id", "previous")
        }, sent["prompt_cache_options"])
        assertFalse("prompt_cache_options" in requestFor(prompt()))
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
