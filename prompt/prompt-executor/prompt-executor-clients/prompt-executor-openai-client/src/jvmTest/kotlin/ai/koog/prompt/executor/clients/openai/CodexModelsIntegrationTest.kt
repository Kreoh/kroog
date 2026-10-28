package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.java.JavaKoogHttpClient
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.CodexAuthFileSource
import ai.koog.prompt.executor.clients.openai.CodexLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.toMessageResponse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Explicit paid checks. Credentials must be supplied for a separately selected account. */
@Execution(ExecutionMode.SAME_THREAD)
class CodexModelsIntegrationTest {
    @Test
    fun integration_testTransportSmoke() = integration_testSelectableModel("gpt-6.1-sol")

    @ParameterizedTest
    @ValueSource(strings = ["gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"])
    fun integration_testSelectableModel(id: String) = runTest(timeout = 180.seconds) {
        val model = OpenAIModels.models.single { it.id == id }
        client().use { client ->
            val response = client.executeStreaming(prompt("codex-live-minimal", params = params()) {
                user("Reply with only PONG.")
            }, model).toList().toMessageResponse()
            assertTrue(text(response).contains("PONG"))
            assertTrue((response.metaInfo.inputTokensCount ?: 0) > 0)
            usage(id, response)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["gpt-6.1-sol", "gpt-6.1-terra"])
    fun integration_testToolsAndStructuredOutput(id: String) = runTest(timeout = 180.seconds) {
        val model = OpenAIModels.models.single { it.id == id }
        val tool = ToolDescriptor("read_probe", "Read the probe value. Call this tool to obtain the value.")
        client().use { client ->
            val called = client.execute(prompt("codex-live-tool", params = params()) {
                user("Call read_probe, then reply with only the returned value.")
            }, model, listOf(tool))
            usage(id, called)
            val call = called.parts.filterIsInstance<MessagePart.Tool.Call>().single()
            assertEquals("read_probe", call.tool)
            val answer = client.execute(prompt("codex-live-replay", params = params()) {
                user("Call read_probe, then reply with only the returned value.")
                message(called)
                toolResult(tool = call.tool, output = "probe-739", id = call.id)
            }, model, listOf(tool))
            usage(id, answer)
            assertTrue(text(answer).contains("probe-739"))
            val structured = client.execute(prompt("codex-live-json", params = OpenAIResponsesParams(
                reasoning = ReasoningConfig(effort = ReasoningEffort.LOW),
                schema = LLMParams.Schema.JSON.Standard("probe", Json.parseToJsonElement(
                    """{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}"""
                ).jsonObject),
            )) { user("Return JSON with answer equal to PONG.") }, model)
            usage(id, structured)
            assertEquals("PONG", Json.parseToJsonElement(text(structured)).jsonObject.getValue("answer").jsonPrimitive.content)
        }
    }

    private fun client(): CodexLLMClient {
        val path = requireNotNull(System.getenv("CODEX_AUTH_FILE")?.takeIf(String::isNotBlank)) {
            "Set CODEX_AUTH_FILE to the explicitly selected account auth file"
        }
        return CodexLLMClient(CodexAuthFileSource(Path.of(path)), JavaKoogHttpClient.Factory())
    }

    private fun params() = OpenAIResponsesParams(reasoning = ReasoningConfig(effort = ReasoningEffort.LOW))
    private fun text(response: Message.Assistant) = response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
    private fun usage(id: String, response: Message.Assistant) {
        println("Codex $id usage: input=${response.metaInfo.inputTokensCount}, output=${response.metaInfo.outputTokensCount}; monetary cost unavailable")
    }
}
