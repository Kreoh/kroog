package ai.koog.skills

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.feature.handler.llm.LLMCallStartingContext
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.tokenizer.Tokenizer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Credential-free integration proof for ordinary agent-history carry-forward. */
class LoadSkillToolAgentIntegrationTest {
    @Test
    fun `real load skill result is carried into the next agent turn`() = runTest {
        val source = CountingInMemorySkillSource(
            Skill(
                name = SKILL_NAME,
                description = SKILL_DESCRIPTION,
                instructions = SKILL_INSTRUCTIONS,
            )
        )
        val registry = SkillRegistry.build(listOf(source))
        assertEquals(1, source.loadCount)

        val catalogue = assertNotNull(SkillCatalogueRenderer.render(registry))
        assertEquals("""[{"name":"$SKILL_NAME","description":"$SKILL_DESCRIPTION"}]""", catalogue)
        assertFalse(catalogue.contains(SKILL_INSTRUCTIONS))

        val request = """
            Complete the task using one available skill.
            Skill catalogue: $catalogue
        """.trimIndent()
        val toolRegistry = registry.mergeToolInto(ToolRegistry.EMPTY)
        val loadSkillTool = assertIs<LoadSkillTool>(toolRegistry.getTool(LoadSkillTool.NAME))
        val tokenizer = WordCountingTokenizer()
        val callContexts = mutableListOf<LLMCallStartingContext>()
        val responses = mutableListOf<Message.Assistant>()
        val executor = getMockExecutor(tokenizer = tokenizer) {
            mockLLMToolCall(
                tool = loadSkillTool,
                args = LoadSkillArgs(SKILL_NAME),
                toolCallId = TOOL_CALL_ID,
            ) onRequestEquals request
            mockLLMAnswer(FINAL_ANSWER) onRequestContains SKILL_INSTRUCTIONS
        }

        val answer = AIAgent(
            promptExecutor = executor,
            llmModel = TEST_MODEL,
            systemPrompt = "Use the supplied skill catalogue and load a skill before answering.",
            toolRegistry = toolRegistry,
            temperature = 0.0,
            maxIterations = 5,
        ) {
            install(EventHandler) {
                onLLMCallStarting { callContexts += it }
                onLLMCallCompleted { context -> context.response?.let(responses::add) }
            }
        }.run(request)

        assertEquals(FINAL_ANSWER, answer)
        assertEquals(2, callContexts.size)
        assertEquals(2, responses.size)

        val secondPromptMessages = callContexts[1].prompt.messages
        val toolCall = secondPromptMessages
            .filterIsInstance<Message.Assistant>()
            .flatMap(Message.Assistant::parts)
            .filterIsInstance<MessagePart.Tool.Call>()
            .single()
        val toolResult = secondPromptMessages
            .filterIsInstance<Message.User>()
            .flatMap(Message.User::parts)
            .filterIsInstance<MessagePart.Tool.Result>()
            .single()
        assertEquals(TOOL_CALL_ID, toolCall.id)
        assertEquals(LoadSkillTool.NAME, toolCall.tool)
        assertEquals(TOOL_CALL_ID, toolResult.id)
        assertEquals(LoadSkillTool.NAME, toolResult.tool)

        val encodedResult = Json.parseToJsonElement(toolResult.output).jsonObject
        assertEquals(SKILL_NAME, encodedResult.getValue("name").jsonPrimitive.content)
        assertEquals(SKILL_DESCRIPTION, encodedResult.getValue("description").jsonPrimitive.content)
        assertEquals(SKILL_INSTRUCTIONS, encodedResult.getValue("instructions").jsonPrimitive.content)

        val firstInputTokens = assertNotNull(responses[0].metaInfo.inputTokensCount)
        val secondInputTokens = assertNotNull(responses[1].metaInfo.inputTokensCount)
        assertEquals(tokenizer.count(request), firstInputTokens)
        assertEquals(tokenizer.count(toolResult.output), secondInputTokens)
        assertTrue(secondInputTokens > firstInputTokens)
        assertEquals(1, source.loadCount)
    }

    private class CountingInMemorySkillSource(skill: Skill) : SkillSource {
        private val delegate = InMemorySkillSource(listOf(skill))

        var loadCount: Int = 0
            private set

        override suspend fun load(): SkillSourceResult {
            loadCount++
            return delegate.load()
        }
    }

    private class WordCountingTokenizer : Tokenizer {
        override fun countTokens(text: String): Int = count(text)

        fun count(text: String): Int = text.trim().split(Regex("\\s+")).size + 1
    }

    private companion object {
        const val SKILL_NAME = "incident-summary"
        const val SKILL_DESCRIPTION = "Summarises a supplied incident report"
        const val SKILL_INSTRUCTIONS =
            "Read every incident detail, preserve the timeline, and report the confirmed outcome in concise language."
        const val TOOL_CALL_ID = "load-1"
        const val FINAL_ANSWER = "The incident summary is complete."

        val TEST_MODEL = LLModel(
            provider = LLMProvider(id = "test", display = "Test"),
            id = "deterministic-test-model",
            capabilities = listOf(LLMCapability.Tools),
            contextLength = 1_000,
        )
    }
}
