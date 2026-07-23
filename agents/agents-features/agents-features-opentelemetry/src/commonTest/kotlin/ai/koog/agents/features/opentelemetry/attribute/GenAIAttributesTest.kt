package ai.koog.agents.features.opentelemetry.attribute

import ai.koog.agents.features.opentelemetry.mock.MockAttributesMutator
import ai.koog.agents.features.opentelemetry.mock.MockLLMProvider
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GenAIAttributesTest {

    @Test
    fun testHostedExecutionAndGeneratedFileMetadataRetainsVariantOrderWithoutSensitiveContent() {
        val generatedFile = MessagePart.GeneratedFile(
            providerFileId = "provider-file-secret",
            containerId = "container-secret",
            filename = "/private/report.csv",
            mediaType = "text/csv",
            sizeBytes = 42,
            producingExecutionId = "execution-secret",
            providerItemId = "provider-item-secret",
        )
        val message = Message.Assistant(
            parts = listOf(
                generatedFile,
                MessagePart.HostedExecution.Request(
                    code = "print('code-secret')",
                    language = "python",
                    executionId = "request-execution-secret",
                    containerId = "request-container-secret",
                    providerItemId = "request-provider-item-secret",
                ),
                MessagePart.HostedExecution.Progress(
                    message = "progress-secret",
                    sequence = 1,
                    executionId = "progress-execution-secret",
                    containerId = "progress-container-secret",
                    providerItemId = "progress-provider-item-secret",
                ),
                MessagePart.HostedExecution.CumulativeOutput(
                    output = "output-secret",
                    sequence = 2,
                    executionId = "output-execution-secret",
                    containerId = "output-container-secret",
                    providerItemId = "output-provider-item-secret",
                ),
                MessagePart.HostedExecution.Result(
                    output = "result-secret",
                    exitCode = 0,
                    generatedFiles = listOf(generatedFile),
                    executionId = "result-execution-secret",
                    containerId = "result-container-secret",
                    providerItemId = "result-provider-item-secret",
                ),
                MessagePart.HostedExecution.Error(
                    message = "error-secret",
                    code = "credential-secret",
                    executionId = "error-execution-secret",
                    containerId = "error-container-secret",
                    providerItemId = "error-provider-item-secret",
                ),
            ),
            metaInfo = ResponseMetaInfo.Empty,
        )

        val encoded = GenAIAttributes.Output.Messages(listOf(message)).value.value
        val parts = Json.parseToJsonElement(encoded).jsonArray
            .single().jsonObject.getValue("parts").jsonArray

        assertEquals(
            listOf(
                """{"type":"generated_file","status":"available","media_type":"text/csv","size_bytes":42}""",
                """{"type":"hosted_execution_request","status":"requested","language":"python"}""",
                """{"type":"hosted_execution_progress","status":"in_progress","sequence":1}""",
                """{"type":"hosted_execution_output","status":"in_progress","sequence":2,"output_character_count":13}""",
                """{"type":"hosted_execution_result","status":"completed","exit_code":0,"output_character_count":13,"generated_file_count":1}""",
                """{"type":"hosted_execution_error","status":"failed"}""",
            ),
            parts.map { it.toString() },
        )
        listOf(
            "provider-file-secret",
            "container-secret",
            "/private/report.csv",
            "execution-secret",
            "provider-item-secret",
            "code-secret",
            "progress-secret",
            "output-secret",
            "result-secret",
            "error-secret",
            "credential-secret",
        ).forEach { sensitiveValue ->
            assertFalse(encoded.contains(sensitiveValue), "Encoded telemetry contained '$sensitiveValue'")
        }
    }

    @Test
    fun testHostedExecutionAndGeneratedFileMetadataOmitsEmptyOptionals() {
        val message = Message.Assistant(
            parts = listOf(
                MessagePart.GeneratedFile(providerFileId = "file-secret"),
                MessagePart.HostedExecution.Progress(),
                MessagePart.HostedExecution.CumulativeOutput(output = ""),
                MessagePart.HostedExecution.Result(),
            ),
            metaInfo = ResponseMetaInfo.Empty,
        )

        val encoded = GenAIAttributes.Output.Messages(listOf(message)).value.value
        val parts = Json.parseToJsonElement(encoded).jsonArray
            .single().jsonObject.getValue("parts").jsonArray

        assertEquals(
            listOf(
                """{"type":"generated_file","status":"available"}""",
                """{"type":"hosted_execution_progress","status":"in_progress"}""",
                """{"type":"hosted_execution_output","status":"in_progress","output_character_count":0}""",
                """{"type":"hosted_execution_result","status":"completed","generated_file_count":0}""",
            ),
            parts.map { it.toString() },
        )
    }

    @Test
    fun testHostedExecutionMetadataPreservesHiddenStringOptInBoundary() {
        val attribute = GenAIAttributes.Output.Messages(
            listOf(
                Message.Assistant(
                    parts = listOf(
                        MessagePart.HostedExecution.Request(code = "code-secret"),
                        MessagePart.HostedExecution.Error(message = "error-secret"),
                    ),
                    metaInfo = ResponseMetaInfo.Empty,
                )
            )
        )
        val defaultAttributes = mutableMapOf<String, Any>()
        MockAttributesMutator(defaultAttributes).applyAttributes(listOf(attribute), verbose = false)
        val verboseAttributes = mutableMapOf<String, Any>()
        MockAttributesMutator(verboseAttributes).applyAttributes(listOf(attribute), verbose = true)
        val verboseValue = verboseAttributes.getValue(attribute.key) as String

        assertEquals("HIDDEN:non-empty", defaultAttributes[attribute.key])
        assertEquals(
            """[{"role":"Assistant","parts":[{"type":"hosted_execution_request","status":"requested","language":"python"},{"type":"hosted_execution_error","status":"failed"}]}]""",
            verboseValue,
        )
        assertFalse(verboseValue.contains("code-secret"))
        assertFalse(verboseValue.contains("error-secret"))
    }

    @Test
    fun testCodeExecutionOutputMessagesRetainCodeOrderedOutputsAndFailure() {
        val message = Message.Assistant(
            parts = listOf(
                MessagePart.CodeExecution(
                    id = "ci_123",
                    code = "print('telemetry')",
                    containerId = "cntr_123",
                    outputs = listOf(
                        MessagePart.CodeExecution.Output.Logs("first output"),
                        MessagePart.CodeExecution.Output.Image("https://example.test/second.png"),
                        MessagePart.CodeExecution.Output.Logs("third output"),
                    ),
                    failure = MessagePart.CodeExecution.Failure.INCOMPLETE,
                )
            ),
            metaInfo = ResponseMetaInfo.Empty,
        )

        val encoded = GenAIAttributes.Output.Messages(listOf(message)).value.value
        val part = Json.parseToJsonElement(encoded).jsonArray
            .single().jsonObject.getValue("parts").jsonArray
            .single().jsonObject

        assertEquals("code_execution", part.getValue("type").jsonPrimitive.content)
        assertEquals("ci_123", part.getValue("id").jsonPrimitive.content)
        assertEquals("cntr_123", part.getValue("container_id").jsonPrimitive.content)
        assertEquals("print('telemetry')", part.getValue("code").jsonPrimitive.content)
        assertEquals("incomplete", part.getValue("status").jsonPrimitive.content)
        assertEquals(
            listOf(
                """{"type":"logs","logs":"first output"}""",
                """{"type":"image","url":"https://example.test/second.png"}""",
                """{"type":"logs","logs":"third output"}""",
            ),
            part.getValue("outputs").jsonArray.map { it.toString() },
        )
    }

    //region Tool

    @Test
    fun `test tool call arguments with empty object value`() {
        val arguments = JsonObject(emptyMap())
        val actualAttribute = GenAIAttributes.Tool.Call.Arguments(arguments)
        assertEquals("gen_ai.tool.call.arguments", actualAttribute.key)
        assertEquals(arguments.toString(), actualAttribute.value.value)
    }

    @Test
    fun `test tool call arguments with valid json value`() {
        val arguments = JsonObject(
            mapOf(
                "key1" to JsonPrimitive("value1"),
                "key2" to JsonPrimitive("value2")
            )
        )
        val actualAttribute = GenAIAttributes.Tool.Call.Arguments(arguments)
        assertEquals("gen_ai.tool.call.arguments", actualAttribute.key)
        assertEquals(arguments.toString(), actualAttribute.value.value)
    }

    @Test
    fun `test tool call arguments value string hidden by default`() {
        val arguments = JsonObject(
            mapOf(
                "argument" to JsonPrimitive("sensitive user input")
            )
        )
        val actualAttribute = GenAIAttributes.Tool.Call.Arguments(arguments)

        assertEquals("gen_ai.tool.call.arguments", actualAttribute.key)
        assertEquals("HIDDEN:non-empty", actualAttribute.value.toString())
        assertEquals(arguments.toString(), actualAttribute.value.value)
    }

    @Test
    fun `test tool result with empty string value`() {
        val result = JsonObject(emptyMap())
        val actualAttribute = GenAIAttributes.Tool.Call.Result(result)
        assertEquals("gen_ai.tool.call.result", actualAttribute.key)
        assertEquals(result.toString(), actualAttribute.value.value)
    }

    @Test
    fun `test tool call result with valid json value`() {
        val result = JsonObject(
            mapOf(
                "key1" to JsonPrimitive("value1"),
                "key2" to JsonPrimitive("value2")
            )
        )
        val actualAttribute = GenAIAttributes.Tool.Call.Result(result)
        assertEquals("gen_ai.tool.call.result", actualAttribute.key)
        assertEquals(result.toString(), actualAttribute.value.value)
    }

    @Test
    fun `test tool call result string hidden by default`() {
        val result = JsonObject(
            mapOf(
                "output" to JsonPrimitive("sensitive tool output")
            )
        )
        val actualAttribute = GenAIAttributes.Tool.Call.Result(result)

        assertEquals("gen_ai.tool.call.result", actualAttribute.key)
        assertEquals("HIDDEN:non-empty", actualAttribute.value.toString())
        assertEquals(result.toString(), actualAttribute.value.value)
    }

    @Test
    fun `test tool call id attribute`() {
        val attribute = GenAIAttributes.Tool.Call.Id("call-123")
        assertEquals("gen_ai.tool.call.id", attribute.key)
        assertEquals("call-123", attribute.value)
    }

    @Test
    fun `test tool name attribute`() {
        val attribute = GenAIAttributes.Tool.Name("search")
        assertEquals("gen_ai.tool.name", attribute.key)
        assertEquals("search", attribute.value)
    }

    @Test
    fun `test tool description attribute`() {
        val attribute = GenAIAttributes.Tool.Description("Performs web search")
        assertEquals("gen_ai.tool.description", attribute.key)
        assertEquals("Performs web search", attribute.value)
    }

    //endregion Tool

    //region Agent

    @Test
    fun `test agent id attribute`() {
        val idAttribute = GenAIAttributes.Agent.Id("test-agent")
        assertEquals("gen_ai.agent.id", idAttribute.key)
        assertEquals("test-agent", idAttribute.value)
    }

    @Test
    fun `test agent name attribute`() {
        val nameAttribute = GenAIAttributes.Agent.Name("Test Agent")
        assertEquals("gen_ai.agent.name", nameAttribute.key)
        assertEquals("Test Agent", nameAttribute.value)
    }

    @Test
    fun `test agent description attributes`() {
        val descriptionAttribute = GenAIAttributes.Agent.Description("This is a test agent")
        assertEquals("gen_ai.agent.description", descriptionAttribute.key)
        assertEquals("This is a test agent", descriptionAttribute.value)
    }

    //endregion Agent

    //region Conversation

    @Test
    fun `test conversation id attribute`() {
        val conversationAttribute = GenAIAttributes.Conversation.Id("conversation-id")
        assertEquals("gen_ai.conversation.id", conversationAttribute.key)
        assertEquals("conversation-id", conversationAttribute.value)
    }

    //endregion Conversation

    //region Data Source

    @Test
    fun `test data source id attribute`() {
        val dataSourceAttribute = GenAIAttributes.DataSource.Id("data-source-id")
        assertEquals("gen_ai.data_source.id", dataSourceAttribute.key)
        assertEquals("data-source-id", dataSourceAttribute.value)
    }

    //endregion Data Source

    //region Operation

    @Test
    fun `test operation name chat attribute`() {
        val attribute = GenAIAttributes.Operation.Name(GenAIAttributes.Operation.OperationNameType.CHAT)
        assertEquals("gen_ai.operation.name", attribute.key)
        assertEquals("chat", attribute.value)
    }

    @Test
    fun `test operation name execute tool attribute`() {
        val attribute = GenAIAttributes.Operation.Name(GenAIAttributes.Operation.OperationNameType.EXECUTE_TOOL)
        assertEquals("gen_ai.operation.name", attribute.key)
        assertEquals("execute_tool", attribute.value)
    }

    @Test
    fun `test operation name generate content attribute`() {
        val attribute = GenAIAttributes.Operation.Name(GenAIAttributes.Operation.OperationNameType.GENERATE_CONTENT)
        assertEquals("gen_ai.operation.name", attribute.key)
        assertEquals("generate_content", attribute.value)
    }

    //endregion Operation

    //region Output

    @Test
    fun `test output type text attribute`() {
        val attribute = GenAIAttributes.Output.Type(GenAIAttributes.Output.OutputType.TEXT)
        assertEquals("gen_ai.output.type", attribute.key)
        assertEquals("text", attribute.value)
    }

    @Test
    fun `test output type json attribute`() {
        val attribute = GenAIAttributes.Output.Type(GenAIAttributes.Output.OutputType.JSON)
        assertEquals("gen_ai.output.type", attribute.key)
        assertEquals("json", attribute.value)
    }

    @Test
    fun `test output type image attribute`() {
        val attribute = GenAIAttributes.Output.Type(GenAIAttributes.Output.OutputType.IMAGE)
        assertEquals("gen_ai.output.type", attribute.key)
        assertEquals("image", attribute.value)
    }

    //endregion Output

    //region Request

    @Test
    fun `test request choice count attribute`() {
        val attribute = GenAIAttributes.Request.Choice.Count(3)
        assertEquals("gen_ai.request.choice.count", attribute.key)
        assertEquals(3, attribute.value)
    }

    @Test
    fun `test request model attribute`() {
        val model = LLModel(MockLLMProvider(), "gpt-4o", listOf(LLMCapability.Completion), 8192, 4096)
        val attribute = GenAIAttributes.Request.Model(model)
        assertEquals("gen_ai.request.model", attribute.key)
        assertEquals("gpt-4o", attribute.value)
    }

    @Test
    fun `test request seed attribute`() {
        val attribute = GenAIAttributes.Request.Seed(42)
        assertEquals("gen_ai.request.seed", attribute.key)
        assertEquals(42, attribute.value)
    }

    @Test
    fun `test request frequency penalty attribute`() {
        val attribute = GenAIAttributes.Request.FrequencyPenalty(0.25)
        assertEquals("gen_ai.request.frequency_penalty", attribute.key)
        assertEquals(0.25, attribute.value)
    }

    @Test
    fun `test request max tokens attribute`() {
        val attribute = GenAIAttributes.Request.MaxTokens(1024)
        assertEquals("gen_ai.request.max_tokens", attribute.key)
        assertEquals(1024, attribute.value)
    }

    @Test
    fun `test request presence penalty attribute`() {
        val attribute = GenAIAttributes.Request.PresencePenalty(0.75)
        assertEquals("gen_ai.request.presence_penalty", attribute.key)
        assertEquals(0.75, attribute.value)
    }

    @Test
    fun `test request stop sequences attribute`() {
        val stops = listOf("END", "STOP")
        val attribute = GenAIAttributes.Request.StopSequences(stops)
        assertEquals("gen_ai.request.stop_sequences", attribute.key)
        assertEquals(stops, attribute.value)
    }

    @Test
    fun `test request temperature attribute`() {
        val attribute = GenAIAttributes.Request.Temperature(0.8)
        assertEquals("gen_ai.request.temperature", attribute.key)
        assertEquals(0.8, attribute.value)
    }

    @Test
    fun `test request top_p attribute`() {
        val attribute = GenAIAttributes.Request.TopP(0.9)
        assertEquals("gen_ai.request.top_p", attribute.key)
        assertEquals(0.9, attribute.value)
    }

    //endregion Request

    //region Response

    @Test
    fun `test response finish reasons attribute`() {
        val reasons = listOf(
            GenAIAttributes.Response.FinishReasonType.Stop,
            GenAIAttributes.Response.FinishReasonType.Custom("custom-reason"),
        )
        val attribute = GenAIAttributes.Response.FinishReasons(reasons)
        assertEquals("gen_ai.response.finish_reasons", attribute.key)
        assertEquals(listOf("stop", "custom-reason"), attribute.value)
    }

    @Test
    fun `test response id attribute`() {
        val attribute = GenAIAttributes.Response.Id("resp-001")
        assertEquals("gen_ai.response.id", attribute.key)
        assertEquals("resp-001", attribute.value)
    }

    @Test
    fun `test response model attribute`() {
        val model = LLModel(MockLLMProvider(), "gemini-1.5-pro", emptyList(), 32768)
        val attribute = GenAIAttributes.Response.Model(model)
        assertEquals("gen_ai.response.model", attribute.key)
        assertEquals("gemini-1.5-pro", attribute.value)
    }

    //endregion Response

    //region Token

    @Test
    fun `test token type attribute`() {
        val attribute = GenAIAttributes.Token.Type(GenAIAttributes.Token.TokenType.INPUT)
        assertEquals("gen_ai.token.type", attribute.key)
        assertEquals("input", attribute.value)
    }

    //endregion Token

    //region Usage

    @Test
    fun `test usage input tokens attribute`() {
        val attribute = GenAIAttributes.Usage.InputTokens(123)
        assertEquals("gen_ai.usage.input_tokens", attribute.key)
        assertEquals(123, attribute.value)
    }

    @Test
    fun `test usage output tokens attribute`() {
        val attribute = GenAIAttributes.Usage.OutputTokens(456)
        assertEquals("gen_ai.usage.output_tokens", attribute.key)
        assertEquals(456, attribute.value)
    }

    //endregion Usage
}
