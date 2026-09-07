package ai.koog.agents.core.dsl.extension

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.context.AIAgentLLMContext
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.tools.MockEnvironment
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.openai.OpenAICodeInterpreterConfig
import ai.koog.prompt.executor.clients.openai.OpenAIPromptCacheIdentity
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class TieredHistoryCompressionStrategyTest {
    private val serializer = KotlinxSerializer()
    private val clock = KoogClock { Instant.parse("2026-01-01T00:00:00Z") }
    private val defaultModel = LLModel(LLMProvider.OpenAI, "test-openai")

    @Test
    fun testCompressesOlderTurnsAndPreservesRecentTurnsVerbatim() = runTest {
        val messages =
            listOf(
                system("System", 0),
                user("User 1", 1),
                assistant("Assistant 1", 2),
                toolCall("call-1", 3),
                toolResult("call-1", 4),
                assistant("Tool conclusion", 5),
                user("User 2", 6),
                assistant("Assistant 2", 7),
                user("User 3", 8),
                assistant("Assistant 3", 9),
            )
        val result = compress(messages, preserveRecentTurns = 2)

        assertEquals(1, result.executorRequests)
        assertEquals(
            listOf("System", "TLDR", "User 2", "Assistant 2", "User 3", "Assistant 3"),
            result.messages.map(Message::textContent),
        )
        assertEquals(messages.drop(6), result.messages.takeLast(4))
        assertTrue(
            result.requestMessages.none {
                it.textContent() in setOf("User 2", "Assistant 2", "User 3", "Assistant 3")
            }
        )
        assertTrue(result.messages.none { message -> message.parts.any { it is MessagePart.Tool } })
    }

    @Test
    fun testPreservesExactlyEightRecentUserLedTurnsIncludingToolResults() = runTest {
        val olderTurns =
            listOf(user("User 1", 1), assistant("Answer 1", 2), user("User 2", 3), assistant("Answer 2", 4))
        val recentTurns =
            listOf(
                user("User 3", 5),
                toolCall("retained-call", 6),
                toolResult("retained-call", 7),
                assistant("Answer 3", 8),
            ) + (4..10).flatMap { turn ->
                listOf(user("User $turn", turn * 3), assistant("Answer $turn", turn * 3 + 1))
            }
        val systemMessage = system("System", 0)

        val result = compress(listOf(systemMessage) + olderTurns + recentTurns, preserveRecentTurns = 8)

        assertEquals(1, result.executorRequests)
        assertEquals(
            (3..10).map { "User $it" },
            result.messages.filterIsInstance<Message.User>()
                .filter { message -> message.parts.none { it is MessagePart.Tool.Result } }
                .map(Message::textContent),
        )
        assertEquals(recentTurns, result.messages.drop(2))
        assertEquals(systemMessage, result.messages.first())
        assertEquals("TLDR", assertIs<Message.Assistant>(result.messages[1]).textContent())
        assertTrue(olderTurns.all { it in result.requestMessages })
        assertTrue(recentTurns.none { it in result.requestMessages })
    }

    @Test
    fun testRetainsCompleteRecentToolInteraction() = runTest {
        val recentTurn =
            listOf(
                user("Use the tool", 3),
                toolCall("call-2", 4),
                toolResult("call-2", 5),
                assistant("Finished", 6),
            )
        val messages = listOf(system("System", 0), user("Old", 1), assistant("Old answer", 2)) + recentTurn

        val result = compress(messages, preserveRecentTurns = 1)

        assertEquals(recentTurn, result.messages.takeLast(recentTurn.size))
        val retainedToolParts = result.messages.flatMap(Message::parts).filterIsInstance<MessagePart.Tool>()
        assertEquals(listOf("call-2", "call-2"), retainedToolParts.map { part -> (part as? MessagePart.Tool.Call)?.id ?: (part as MessagePart.Tool.Result).id })
    }

    @Test
    fun testDoesNothingWhenThereIsNoOlderCompleteTurn() = runTest {
        val messages = listOf(system("System", 0), user("Only turn", 1), assistant("Answer", 2))

        val params = responsesParamsWithSavedContainer()
        val result = compress(messages, preserveRecentTurns = 1, params = params)

        assertSame(params, result.prompt.params)
        assertEquals(0, result.executorRequests)
        assertEquals(messages, result.messages)
    }

    @Test
    fun testFoldsPreviousSummaryIntoReplacementSummary() = runTest {
        val systemMessage = system("System", 0)
        val firstRecentTurns =
            (2..9).flatMap { turn ->
                listOf(user("User $turn", turn * 2), assistant("Answer $turn", turn * 2 + 1))
            }
        val first = compress(
            messages = listOf(systemMessage, user("User 1", 1), assistant("Answer 1", 2)) + firstRecentTurns,
            preserveRecentTurns = 8,
            summaryText = "First portable summary",
        )
        val newestTurn = listOf(user("User 10", 20), assistant("Answer 10", 21))
        val expectedTail = firstRecentTurns.drop(2) + newestTurn

        val second = compress(
            messages = first.messages + newestTurn,
            preserveRecentTurns = 8,
            summaryText = "Replacement portable summary",
        )

        assertEquals(1, first.executorRequests)
        assertEquals(1, second.executorRequests)
        assertEquals(1, second.requestMessages.count { it.textContent() == "First portable summary" })
        assertTrue(firstRecentTurns.take(2).all { it in second.requestMessages })
        assertTrue(expectedTail.none { it in second.requestMessages })
        assertEquals(
            listOf("System", "Replacement portable summary") + expectedTail.map(Message::textContent),
            second.messages.map(Message::textContent),
        )
        assertEquals(systemMessage, second.messages.first())
        assertEquals(expectedTail, second.messages.drop(2))
        assertEquals(8, second.messages.filterIsInstance<Message.User>().size)
    }

    @Test
    fun testPreservesMemoryFromCompressedTier() = runTest {
        val memory = assistant("Here are the relevant facts from memory", 2)
        val messages =
            listOf(
                system("System", 0),
                user("Old turn", 1),
                memory,
                assistant("Old answer", 3),
                user("Recent turn", 4),
            )

        val result = compress(messages, preserveRecentTurns = 1, memoryMessages = listOf(memory))

        assertEquals(
            listOf(messages.first(), memory, assistant("TLDR", 10), messages.last()),
            result.messages,
        )
    }

    @Test
    fun testProducesTheSamePortableShapeAcrossProviders() = runTest {
        val messages =
            listOf(
                system("System", 0),
                user("Old turn", 1),
                assistant("Old answer", 2),
                user("Recent turn", 3),
                assistant("Recent answer", 4),
            )
        val providers =
            listOf(
                LLMProvider.OpenAI,
                LLMProvider.Anthropic,
                LLMProvider.Google,
                LLMProvider.Meta,
                LLMProvider.Alibaba,
                LLMProvider.OpenRouter,
                LLMProvider.Ollama,
                LLMProvider.Bedrock,
                LLMProvider.DeepSeek,
                LLMProvider.MistralAI,
                LLMProvider.OCI,
                LLMProvider.MiniMax,
                LLMProvider.ZhipuAI,
                LLMProvider.HuggingFace,
                LLMProvider.Azure,
                LLMProvider.Vertex,
                LLMProvider("custom", "Custom"),
            )

        val histories =
            providers.map { provider ->
                compress(
                    messages = messages,
                    preserveRecentTurns = 1,
                    model = LLModel(provider, "test-${provider.id}"),
                ).messages.map { message -> message.role to message.parts }
            }

        assertTrue(histories.all { it == histories.first() })
    }

    @Test
    fun testRemovesProviderReplayStateFromBothHistoryTiers() = runTest {
        val olderResponse =
            Message.Assistant(
                parts =
                listOf(
                    MessagePart.Reasoning(
                        content = "older private reasoning",
                        encrypted = "older-provider-signature",
                        providerItemId = "older-reasoning-item",
                    ),
                    MessagePart.Text("Older answer", providerItemId = "older-text-item"),
                    MessagePart.HostedExecution.Progress(
                        message = "older provider progress",
                        providerItemId = "older-execution-item",
                    ),
                ),
                metaInfo = responseMeta(2),
                rawResponse = JsonObject(emptyMap()),
                id = "older-provider-message",
            )
        val retainedResponse =
            Message.Assistant(
                parts =
                listOf(
                    MessagePart.Reasoning(
                        content = "recent private reasoning",
                        replay =
                        listOf(
                            MessagePart.ReasoningReplay.Signed(
                                text = "recent private reasoning",
                                signature = "recent-provider-signature",
                            )
                        ),
                    ),
                    MessagePart.Text("Recent answer", providerItemId = "recent-text-item"),
                    MessagePart.HostedExecution.Progress(
                        message = "recent provider progress",
                        providerItemId = "recent-execution-item",
                    ),
                ),
                metaInfo = responseMeta(4),
                rawResponse = JsonObject(emptyMap()),
                id = "recent-provider-message",
            )
        val messages =
            listOf(system("System", 0), user("Older turn", 1), olderResponse, user("Recent turn", 3), retainedResponse)

        val result = compress(messages, preserveRecentTurns = 1)

        val olderRequest = result.requestMessages.filterIsInstance<Message.Assistant>().single()
        assertEquals(listOf(MessagePart.Text("Older answer")), olderRequest.parts)
        assertNull(olderRequest.rawResponse)
        assertNull(olderRequest.id)

        val retained = result.messages.filterIsInstance<Message.Assistant>().single { it.textContent() == "Recent answer" }
        assertEquals(listOf(MessagePart.Text("Recent answer")), retained.parts)
        assertNull(retained.rawResponse)
        assertNull(retained.id)
    }

    @Test
    fun testRemovesProviderReplayStateFromGeneratedSummary() = runTest {
        val summaryResponse =
            Message.Assistant(
                parts =
                listOf(
                    MessagePart.Reasoning(
                        content = "private reasoning",
                        encrypted = "provider-encrypted",
                        providerItemId = "reasoning-item",
                    ),
                    MessagePart.Text("Portable summary", providerItemId = "text-item"),
                ),
                metaInfo = responseMeta(10),
                finishReason = "stop",
                id = "provider-message",
            )
        val executor = executorReturning(summaryResponse)
        val original = listOf(system("System", 0), user("Old", 1), assistant("Old answer", 2), user("New", 3))
        val context = context(original, defaultModel, executor)

        context.writeSession {
            TieredHistoryCompressionStrategy(1).compress(this, memoryMessages = emptyList())
        }

        val summary = context.readSession { prompt.messages.filterIsInstance<Message.Assistant>().single() }
        assertEquals("Portable summary", summary.textContent())
        assertEquals(1, summary.parts.size)
        assertNull(assertIs<MessagePart.Text>(summary.parts.single()).providerItemId)
        assertNull(summary.rawResponse)
        assertNull(summary.id)
    }

    @Test
    fun testRestoresOriginalPromptWhenSummaryHasNoText() = runTest {
        val summaryResponse =
            Message.Assistant(
                part = MessagePart.Reasoning("reasoning only", encrypted = "provider-encrypted"),
                metaInfo = responseMeta(10),
            )
        val executor = executorReturning(summaryResponse)
        val original = listOf(system("System", 0), user("Old", 1), assistant("Old answer", 2), user("New", 3))
        val context = context(original, defaultModel, executor, responsesParamsWithSavedContainer())
        val originalPrompt = context.readSession { prompt }

        assertFailsWith<IllegalStateException> {
            context.writeSession {
                TieredHistoryCompressionStrategy(1).compress(this, memoryMessages = emptyList())
            }
        }

        assertSame(originalPrompt, context.readSession { prompt })
    }

    @Test
    fun testRemovesSavedContainerFromSummaryRequestAndPreservesExecutionSettings() = runTest {
        val params = responsesParamsWithSavedContainer()
        val result = compress(twoTurnMessages(), preserveRecentTurns = 1, params = params)

        assertEquals(1, result.executorRequests)
        assertPortableExecutionParams(params, assertNotNull(result.requestPrompt).params)
        assertEquals("saved-container", params.codeInterpreter?.containerId)
    }

    @Test
    fun testRemovesSavedContainerFromFinalPromptAndPreservesExecutionSettings() = runTest {
        val params = responsesParamsWithSavedContainer().copy(temperature = null, topP = 0.8)
        val result = compress(twoTurnMessages(), preserveRecentTurns = 1, params = params)

        assertEquals(listOf("System", "TLDR", "New"), result.messages.map(Message::textContent))
        assertPortableExecutionParams(params, result.prompt.params)
        assertEquals("saved-container", params.codeInterpreter?.containerId)
    }

    @Test
    fun testKeepsCodeInterpreterDisabledDuringCompression() = runTest {
        val params = OpenAIResponsesParams(temperature = 0.3, maxTokens = 400, stateless = true)
        val result = compress(twoTurnMessages(), preserveRecentTurns = 1, params = params)

        for (prompt in listOf(assertNotNull(result.requestPrompt), result.prompt)) {
            val actual = assertIs<OpenAIResponsesParams>(prompt.params)
            assertNull(actual.codeInterpreter)
            assertEquals(params, actual)
        }
    }

    @Test
    fun testPreservesNonOpenAIParameterTypeAndValuesDuringCompression() = runTest {
        val params = GoogleParams(temperature = 0.4, maxTokens = 500, topP = 0.8, topK = 12)
        val result = compress(
            twoTurnMessages(),
            preserveRecentTurns = 1,
            model = LLModel(LLMProvider.Google, "test-google"),
            params = params,
        )

        for (prompt in listOf(assertNotNull(result.requestPrompt), result.prompt)) {
            val actual = assertIs<GoogleParams>(prompt.params)
            assertEquals(params, actual)
            assertEquals(0.4, actual.temperature)
            assertEquals(500, actual.maxTokens)
            assertEquals(0.8, actual.topP)
            assertEquals(12, actual.topK)
        }
    }

    @Test
    fun testRestoresOriginalPromptAndContainerWhenSummaryFails() = runTest {
        val failure = IllegalStateException("Summary failed")
        val executor = RecordingPromptExecutor { throw failure }
        val context = context(twoTurnMessages(), defaultModel, executor, responsesParamsWithSavedContainer())
        val originalPrompt = context.readSession { prompt }

        val thrown = assertFailsWith<IllegalStateException> {
            context.writeSession {
                HistoryCompressionStrategy.Tiered(1).compress(this, memoryMessages = emptyList())
            }
        }

        assertSame(failure, thrown)
        assertSame(originalPrompt, context.readSession { prompt })
    }

    @Test
    fun testRestoresOriginalPromptAndContainerWhenSummaryIsCancelled() = runTest {
        val cancellation = CancellationException("Summary cancelled")
        val executor = RecordingPromptExecutor { throw cancellation }
        val context = context(twoTurnMessages(), defaultModel, executor, responsesParamsWithSavedContainer())
        val originalPrompt = context.readSession { prompt }

        val thrown = assertFailsWith<CancellationException> {
            context.writeSession {
                HistoryCompressionStrategy.Tiered(1).compress(this, memoryMessages = emptyList())
            }
        }

        assertSame(cancellation, thrown)
        assertSame(originalPrompt, context.readSession { prompt })
    }

    private fun responsesParamsWithSavedContainer(): OpenAIResponsesParams =
        OpenAIResponsesParams(
            temperature = 0.3,
            maxTokens = 400,
            parallelToolCalls = false,
            store = false,
            stateless = true,
            codeInterpreter = OpenAICodeInterpreterConfig(
                fileIds = listOf("file-input"),
                containerId = "saved-container",
            ),
        ).withPromptCacheIdentity(OpenAIPromptCacheIdentity("test-user", "test-chat"))

    private fun assertPortableExecutionParams(original: OpenAIResponsesParams, actual: LLMParams) {
        val responses = assertIs<OpenAIResponsesParams>(actual)
        val codeInterpreter = assertNotNull(responses.codeInterpreter)
        assertNull(codeInterpreter.containerId)
        assertEquals(listOf("file-input"), codeInterpreter.fileIds)
        assertTrue(responses.stateless)
        assertEquals(false, responses.store)
        assertEquals(original.promptCacheIdentity, responses.promptCacheIdentity)
        assertEquals(original.temperature, responses.temperature)
        assertEquals(original.maxTokens, responses.maxTokens)
        assertEquals(original.topP, responses.topP)
        assertEquals(original.parallelToolCalls, responses.parallelToolCalls)
    }

    private fun twoTurnMessages(): List<Message> =
        listOf(system("System", 0), user("Old", 1), assistant("Old answer", 2), user("New", 3))

    @Test
    fun testRejectsNonPositiveRecentTurnCount() {
        assertFailsWith<IllegalArgumentException> { TieredHistoryCompressionStrategy(0) }
        assertFailsWith<IllegalArgumentException> { HistoryCompressionStrategy.Tiered(-1) }
    }

    private suspend fun compress(
        messages: List<Message>,
        preserveRecentTurns: Int,
        model: LLModel = defaultModel,
        memoryMessages: List<Message> = emptyList(),
        summaryText: String = "TLDR",
        params: LLMParams = LLMParams(),
    ): CompressionResult {
        var executorRequests = 0
        var requestPrompt: Prompt? = null
        val executor = RecordingPromptExecutor { prompt ->
            executorRequests += 1
            requestPrompt = prompt
            assistant(summaryText, 10)
        }
        val context = context(messages, model, executor, params)
        context.writeSession {
            HistoryCompressionStrategy.Tiered(preserveRecentTurns).compress(this, memoryMessages)
        }
        return CompressionResult(
            messages = context.readSession { prompt.messages },
            executorRequests = executorRequests,
            requestMessages = requestPrompt?.messages.orEmpty(),
            prompt = context.readSession { prompt },
            requestPrompt = requestPrompt,
        )
    }

    private fun context(
        messages: List<Message>,
        model: LLModel,
        executor: PromptExecutor,
        params: LLMParams = LLMParams(),
    ): AIAgentLLMContext =
        AIAgentLLMContext(
            tools = emptyList(),
            prompt = Prompt.build("tiered-compression-test", params = params) { messages.forEach { message(it) } },
            model = model,
            responseProcessor = null,
            promptExecutor = executor,
            environment = MockEnvironment(ToolRegistry.EMPTY, executor, serializer),
            config = AIAgentConfig(Prompt.Empty, model, 10),
            clock = clock,
        )

    private fun executorReturning(response: Message.Assistant): PromptExecutor = RecordingPromptExecutor { response }

    private fun system(text: String, minute: Int): Message.System =
        Message.System(text, requestMeta(minute))

    private fun user(text: String, minute: Int): Message.User =
        Message.User(text, requestMeta(minute))

    private fun assistant(text: String, minute: Int): Message.Assistant =
        Message.Assistant(text, responseMeta(minute))

    private fun toolCall(id: String, minute: Int): Message.Assistant =
        Message.Assistant(
            part = MessagePart.Tool.Call(id = id, tool = "lookup", args = "{}"),
            metaInfo = responseMeta(minute),
        )

    private fun toolResult(id: String, minute: Int): Message.User =
        Message.User(
            part = MessagePart.Tool.Result(id = id, tool = "lookup", output = "result"),
            metaInfo = requestMeta(minute),
        )

    private fun requestMeta(minute: Int): RequestMetaInfo =
        RequestMetaInfo.create(KoogClock { clock.now().plus(minute.minutes) })

    private fun responseMeta(minute: Int): ResponseMetaInfo =
        ResponseMetaInfo.create(KoogClock { clock.now().plus(minute.minutes) })

    private data class CompressionResult(
        val messages: List<Message>,
        val executorRequests: Int,
        val requestMessages: List<Message>,
        val prompt: Prompt,
        val requestPrompt: Prompt?,
    )

    private class RecordingPromptExecutor(
        private val response: (Prompt) -> Message.Assistant,
    ) : PromptExecutor() {
        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant = response(prompt)

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Flow<StreamFrame> = emptyFlow()

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            throw UnsupportedOperationException("Moderation is not needed for this test")

        override fun close() {}
    }
}
