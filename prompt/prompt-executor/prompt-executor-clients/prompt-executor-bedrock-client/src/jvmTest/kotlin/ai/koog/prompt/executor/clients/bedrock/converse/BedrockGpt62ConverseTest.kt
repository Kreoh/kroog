package ai.koog.prompt.executor.clients.bedrock.converse

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.bedrock.BedrockCacheControl
import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import ai.koog.prompt.executor.clients.bedrock.util.JsonDocumentConverters
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.OutputFormatStructure
import aws.sdk.kotlin.services.bedrockruntime.model.OutputFormatType
import aws.sdk.kotlin.services.bedrockruntime.model.SystemContentBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import aws.sdk.kotlin.services.bedrockruntime.model.Tool as BedrockTool

class BedrockGpt62ConverseTest {
    private val models = listOf(BedrockModels.OpenAIGpt6_2Astra, BedrockModels.OpenAIGpt6_2Sol)

    @Test
    fun testStrictSchemaAndStreamingSupport() {
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{},"additionalProperties":false,"required":[]}""").jsonObject
        val properties = Json.parseToJsonElement("""{"text":{"format":{"strict":false},"verbosity":"low"},"vendor":1}""").jsonObject
        val expected = Json.parseToJsonElement("""{"text":{"format":{"strict":true},"verbosity":"low"},"vendor":1}""")
        val params = BedrockConverseParams(schema = LLMParams.Schema.JSON.Standard("result", schema), additionalProperties = properties)
        val prompt = Prompt.build("schema", params) { user("reply") }
        for (model in models) {
            assertTrue(model.supports(LLMCapability.Schema.JSON.Standard))
            val request = BedrockConverseConverters.createConverseRequest(prompt, model, emptyList())
            val format = requireNotNull(request.outputConfig?.textFormat)
            assertEquals(OutputFormatType.JsonSchema, format.type)
            val spec = assertIs<OutputFormatStructure.JsonSchema>(format.structure).value
            assertEquals("result", spec.name)
            assertEquals(schema.toString(), spec.schema)
            assertEquals(expected, JsonDocumentConverters.convertToJsonElement(request.additionalModelRequestFields))
            if (model == BedrockModels.OpenAIGpt6_2Astra) {
                assertFailsWith<IllegalArgumentException> {
                    BedrockConverseConverters.createConverseStreamRequest(prompt, model, emptyList())
                }
            } else {
                val stream = BedrockConverseConverters.createConverseStreamRequest(prompt, model, emptyList())
                assertEquals(request.outputConfig, stream.outputConfig)
                assertEquals(expected, JsonDocumentConverters.convertToJsonElement(stream.additionalModelRequestFields))
            }
        }
    }

    @Test
    fun testExplicitCacheControlsAreOmitted() {
        val cache = BedrockCacheControl.Default
        val tool = ToolDescriptor("lookup", "lookup", cacheControl = cache)
        val prompt = Prompt.build("cache") {
            system("stable", cache)
            repeat(5) { user("stable", cache) }
            toolResult("lookup", "found", id = "call", cacheControl = cache)
        }
        for (model in models) {
            val request = BedrockConverseConverters.createConverseRequest(prompt, model, listOf(tool))
            assertIs<SystemContentBlock.Text>(request.system.orEmpty().single())
            assertIs<BedrockTool.ToolSpec>(request.toolConfig!!.tools.single())
            assertTrue(request.messages.orEmpty().flatMap { it.content }.none { it is ContentBlock.CachePoint })
        }
    }

    @Test
    fun testConverseAllowsUserImagesAndDisallowsDocumentsAndToolResultAttachments() {
        val bytes = AttachmentContent.Binary.Bytes(byteArrayOf(1))
        val image = MessagePart.Attachment(AttachmentSource.Image(bytes, "png"))
        val document = MessagePart.Attachment(AttachmentSource.File(bytes, "pdf", "application/pdf", "report.pdf"))
        for (model in models) {
            val userImage = Prompt.build("image") { user(listOf(image)) }
            val request = BedrockConverseConverters.createConverseRequest(userImage, model, emptyList())
            assertIs<ContentBlock.Image>(request.messages.orEmpty().single().content.single())
            val rejected = listOf(
                Prompt.build("document") { user(listOf(document)) },
                Prompt.build("tool-document") { toolResult(MessagePart.Tool.Result("call", "lookup", listOf(document))) },
                Prompt.build("tool-image") { toolResult(MessagePart.Tool.Result("call", "lookup", listOf(image))) },
            )
            for (prompt in rejected) {
                assertFailsWith<IllegalArgumentException> {
                    BedrockConverseConverters.createConverseRequest(prompt, model, emptyList())
                }
                assertFailsWith<IllegalArgumentException> {
                    BedrockConverseConverters.createConverseStreamRequest(prompt, model, emptyList())
                }
            }
        }
    }
}
