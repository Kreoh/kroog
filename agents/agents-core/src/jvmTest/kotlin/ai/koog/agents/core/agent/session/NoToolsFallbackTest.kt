package ai.koog.agents.core.agent.session

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.context.AIAgentLLMContext
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.llm.choice.ChoiceSelectionStrategy
import ai.koog.agents.ext.llm.choice.PromptExecutorWithChoiceSelection
import ai.koog.agents.testing.tools.MockEnvironment
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.PromptExecutorOperation
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.OllamaModels
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.utils.time.KoogClock
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NoToolsFallbackTest {
    @Test
    fun testChoiceSelectionUsesTheModelResolvedForMultipleChoices() = runTest {
        val requested = OpenAIModels.Chat.GPT6_2Astra
        val resolved = ResolvedModel(OpenAIModels.Chat.GPT4o)
        val prompt = Prompt.build("choice") { user("Finish") }
        val reply = Message.Assistant("Done", ResponseMetaInfo.Empty)
        val nested = mockk<PromptExecutor>()
        coEvery { nested.resolveModel(requested, PromptExecutorOperation.MultipleChoices) } returns resolved
        coEvery { nested.executeMultipleChoices(prompt, resolved, emptyList()) } returns listOf(reply)
        val executor = PromptExecutorWithChoiceSelection(nested, ChoiceSelectionStrategy.Default)
        val destination = executor.resolveModel(requested, PromptExecutorOperation.Execute)
        assertEquals(reply, executor.execute(prompt, destination, emptyList()))
    }

    @Test
    fun testLlamaReceivesNoToolsWhenItReplacesUnavailableAstra() = runTest {
        val http = mockk<KoogHttpClient>()
        val sent = slot<String>()
        coEvery { http.post("api/chat", capture(sent), String::class, String::class, any(), any()) } returns
            """{"model":"fallback","message":{"role":"assistant","content":"done"},"done":true}"""
        val fallback = OllamaModels.Meta.LLAMA_3_2
        val executor = MultiLLMPromptExecutor(
            listOf(OllamaClient(http)),
            MultiLLMPromptExecutor.FallbackPromptExecutorSettings(fallback.provider, fallback),
        )
        val requested = OpenAIModels.Chat.GPT6_2Astra
        val params = LLMParams(additionalProperties = mapOf("tools" to JsonArray(listOf(JsonObject(emptyMap())))))
        val prompt = Prompt.build("fallback", params) { user("Finish") }
        val context = AIAgentLLMContext(
            tools = listOf(ToolDescriptor("lookup", "Look up a record")),
            prompt = prompt,
            model = requested,
            responseProcessor = null,
            promptExecutor = executor,
            environment = MockEnvironment(ToolRegistry.EMPTY, executor, KotlinxSerializer()),
            config = AIAgentConfig(prompt, requested, 2),
            clock = KoogClock.System,
        )
        context.writeSession { requestLLMWithoutTools() }
        val body = Json.parseToJsonElement(sent.captured).jsonObject
        assertEquals(fallback.id, body.getValue("model").jsonPrimitive.content)
        assertFalse("tools" in body)
    }
}
