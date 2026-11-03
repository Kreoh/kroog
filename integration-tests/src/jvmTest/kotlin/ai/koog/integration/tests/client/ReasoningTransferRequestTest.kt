package ai.koog.integration.tests.client

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.test.utils.CapturingKoogHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

internal class ReasoningTransferRequestTest {
    @Test
    fun testClaudeReceivesExecutedCodeAndPrintedOutputAsText() = runTest {
        val claudeHttp = CapturingKoogHttpClient("claude") {
            """{"id":"msg","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                "content":[{"type":"text","text":"answer"}],"stop_reason":"end_turn"}"""
        }
        val native = Message.Assistant(
            MessagePart.CodeExecution("run", "print(17)", "private-container", listOf(MessagePart.CodeExecution.Output.Logs("17"))),
            ResponseMetaInfo.Empty,
        )
        val transcript = executionTranscript(native, "17")
        val followUp = Message.User("What was printed?", RequestMetaInfo.Empty)
        AnthropicLLMClient(httpClient = claudeHttp).execute(
            Prompt(listOf(Message.User("Run the code", RequestMetaInfo.Empty), native.copy(parts = listOf(transcript)), followUp),
                "code-transcript", AnthropicParams()), AnthropicModels.Sonnet_5_5,
        )
        val request = Json.parseToJsonElement(claudeHttp.lastRequest as String).jsonObject
        val block = request.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonArray.single().jsonObject
        assertEquals("text", block.getValue("type").jsonPrimitive.content)
        assertEquals("Recorded code execution from a previous workspace.\nCode:\nprint(17)\nOutput:\n17", block.getValue("text").jsonPrimitive.content)
        assertFalse(request.toString().contains("private-container"))
    }

    @Test
    fun testGlmToClaudeAndBackBuildsRequestsFromUnmodifiedHistory() = runTest {
        val glmHttp = CapturingKoogHttpClient("glm") {
            """{"id":"cmpl","object":"chat.completion","created":1,"model":"glm","choices":[{"index":0,
                "message":{"role":"assistant","content":"GLM answer","reasoning_content":"GLM private",
                "tool_calls":[{"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{}"}}]},
                "finish_reason":"tool_calls"}]}"""
        }
        val claudeHttp = CapturingKoogHttpClient("claude") {
            """{"id":"msg","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                "content":[{"type":"text","text":"Claude answer"}],"stop_reason":"end_turn"}"""
        }
        val glmClient = OpenAILLMClient(httpClient = glmHttp)
        val glm = LLModel(LLMProvider.OpenAI, "z-ai/glm-5.3-flash", listOf(
            LLMCapability.Completion, LLMCapability.Tools, LLMCapability.OpenAIEndpoint.Completions,
        ))
        val user = Message.User("question", RequestMetaInfo.Empty)
        val saved = glmClient.execute(Prompt(listOf(user), "first", OpenAIChatParams()), glm)
        val result = Message.User(listOf(MessagePart.Tool.Result("call_1", "lookup", "result")), RequestMetaInfo.Empty)
        val history = listOf(user, saved, result)
        val claude = AnthropicLLMClient(httpClient = claudeHttp).execute(
            Prompt(history, "switch", AnthropicParams()), AnthropicModels.Sonnet_5_5,
        )
        val outbound = Json.parseToJsonElement(claudeHttp.lastRequest as String).jsonObject
        val assistant = outbound.getValue("messages").jsonArray[1].jsonObject
        val blocks = assistant.getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "tool_use"), blocks.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("call_1", blocks.last().getValue("id").jsonPrimitive.content)
        val part = blocks.first()
        assertEquals("text", part.getValue("type").jsonPrimitive.content)
        assertEquals("GLM answer", part.getValue("text").jsonPrimitive.content)
        assertFalse(outbound.toString().contains("GLM private"))
        assertEquals(listOf("GLM private"), saved.parts.filterIsInstance<MessagePart.Reasoning>().single().content)

        glmClient.execute(Prompt(history + claude + user, "return", OpenAIChatParams()), glm)
        val returned = Json.parseToJsonElement(glmHttp.lastRequest as String).jsonObject
        val replay = returned.getValue("messages").jsonArray[1].jsonObject
        assertEquals("GLM private", replay.getValue("reasoning_content").jsonPrimitive.content)
        assertEquals("call_1", replay.getValue("tool_calls").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)

        glmClient.execute(Prompt(history, "foreign", OpenAIChatParams()), glm.copy(id = "moonshotai/kimi-k3"))
        val foreign = Json.parseToJsonElement(glmHttp.lastRequest as String).jsonObject
        assertFalse(foreign.getValue("messages").jsonArray[1].jsonObject.containsKey("reasoning_content"))
    }
}
