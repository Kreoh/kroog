package ai.koog.agents.core.dsl.extension

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.context.AIAgentLLMContext
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.ToolResultKind
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.tools.MockEnvironment
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.openai.OpenAICodeInterpreterConfig
import ai.koog.prompt.executor.clients.openai.OpenAIPromptCacheIdentity
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.tokenizer.PromptTokenizer
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class TieredHistoryCompressionStrategyTest {
    private val handoverPrefix = "[Kroog conversation handover]\n"
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
            listOf("System", assertHandover(result.messages[1], "TLDR"), "User 2", "Assistant 2", "User 3", "Assistant 3"),
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
        assertHandover(result.messages[1], "TLDR")
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
        assertEquals(1, second.requestMessages.joinToString("\n", transform = Message::textContent).split("First portable summary").size - 1)
        assertTrue(firstRecentTurns.take(2).all { it in second.requestMessages })
        assertTrue(expectedTail.none { it in second.requestMessages })
        assertEquals(
            listOf("System", assertHandover(second.messages[1], "Replacement portable summary")) + expectedTail.map(Message::textContent),
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
        assertHandover(result.messages[2], "TLDR")

        assertEquals(
            listOf(messages.first(), memory, result.messages[2], messages.last()),
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
        assertHandover(summary, "Portable summary")
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

        assertEquals(listOf("System", assertHandover(result.messages[1], "TLDR"), "New"), result.messages.map(Message::textContent))
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

    @Test
    fun testRequestsAConciseContinuationWithAttributedFactsAndUnfinishedWork() = runTest {
        val covered = listOf(
            user("Deliver the postcode report; retain exact file names and do not publish", 1),
            assistant("I propose checking report-final.csv before delivery", 2),
            toolCall("check-report", 3),
            toolResult("check-report", 4),
            assistant("The check completed; delivery is still pending", 5),
        )
        val result = compress(listOf(system("System", 0)) + covered + user("Continue", 6), 1)

        val requestedCovered = result.requestMessages.drop(1).dropLast(1)
        assertEquals(covered.size, requestedCovered.size)
        assertEquals(covered.take(2), requestedCovered.take(2))
        assertHistoricalToolExchange(requestedCovered.subList(2, 4), "check-report")
        assertEquals(covered.last(), requestedCovered.last())
        val instruction = result.requestMessages.last().textContent().lowercase()
        for (purpose in listOf("continu", "concise")) {
            assertTrue(purpose in instruction, "Missing continuation purpose: $purpose")
        }
        assertTrue(Regex("pending|unfinished|open work|remaining").containsMatchIn(instruction))
        assertTrue(Regex("completed|done").containsMatchIn(instruction))
        assertTrue(Regex("proposed|proposal|planned").containsMatchIn(instruction))
        assertFalse("format your summary with clear sections" in instruction)
        assertFalse("only context available" in instruction)
        assertFalse("create a comprehensive summary" in instruction)
    }

    @Test
    fun testRuntimeFrameExplainsHistoricalContextWithoutPromotingItToSystemAuthority() = runTest {
        val body = "User requested report.csv. Assistant proposed lookup. Tool returned a draft; review remains open."
        val result = compress(twoTurnMessages(), 1, summaryText = body)
        val frame = assertHandover(result.messages[1], body).removeSuffix(body).lowercase()

        assertEquals(listOf(twoTurnMessages().first()), result.messages.filterIsInstance<Message.System>())
        assertTrue("histor" in frame)
        assertTrue("recent" in frame)
        assertTrue("tool" in frame)
        assertTrue(Regex("capabilit|available").containsMatchIn(frame))
        assertTrue(Regex("instruction|authorit").containsMatchIn(frame))
        assertTrue(listOf("user", "assistant", "tool").all { it in frame })
        assertEquals(twoTurnMessages().last(), result.messages.last())
    }

    @Test
    fun testUpdatesReconstructedHandoverSeparatelyFromNewTypedMessages() = runTest {
        val first = compress(twoTurnMessages(), 1, summaryText = "Keep file report.csv; postcode D02; review remains open")
        val reconstructed = first.messages.map { message ->
            when (message) {
                is Message.System -> system(message.textContent(), 0)
                is Message.User -> user(message.textContent(), 1)
                is Message.Assistant -> assistant(message.textContent(), 2)
            }
        }
        val newlyCovered = listOf(
            reconstructed.last(),
            toolCall("verify-report", 3),
            toolResult("verify-report", 4),
            assistant("Review completed; replace postcode D02 with D04", 5),
        )
        val tail = user("Deliver the reviewed report", 6)
        val executor = RecordingPromptExecutor { request ->
            val prior = request.messages.single { "Keep file report.csv" in it.textContent() }
            assertFalse(handoverPrefix in prior.textContent(), "Pass the prior body once, without its receiving frame")
            val requestedCovered = request.messages.drop(request.messages.indexOf(prior) + 1).dropLast(1)
            assertEquals(newlyCovered.size, requestedCovered.size)
            assertEquals(newlyCovered.first(), requestedCovered.first())
            assertHistoricalToolExchange(requestedCovered.subList(1, 3), "verify-report")
            assertEquals(newlyCovered.last(), requestedCovered.last())
            assertFalse(tail in request.messages)
            assertEquals(1, request.messages.sumOf { it.textContent().split("Keep file report.csv").size - 1 })
            val instruction = request.messages.last().textContent().lowercase()
            assertTrue("updat" in instruction)
            assertTrue(Regex("preserv|retain|keep").containsMatchIn(instruction))
            assertTrue(Regex("supersed|replac").containsMatchIn(instruction))
            assistant("Keep file report.csv; postcode D04; review completed; delivery pending", 10)
        }
        val context = context(reconstructed.dropLast(1) + newlyCovered + tail, defaultModel, executor)

        context.writeSession { HistoryCompressionStrategy.Tiered(1).compress(this, emptyList()) }

        val messages = context.readSession { prompt.messages }
        assertHandover(messages[1], "Keep file report.csv; postcode D04; review completed; delivery pending")
        assertFalse("D02" in messages.joinToString("\n", transform = Message::textContent))
        assertEquals(tail, messages.last())
    }

    @Test
    fun testOrdinaryPreamblesAndEmbeddedMarkersRemainCoveredMessages() = runTest {
        val preambles = listOf(
            assistant("Unmarked old summary: retain report.csv", 1),
            assistant("The manual mentions ${handoverPrefix}as an example", 2),
        )
        val markedWithinTurn = assistant("${handoverPrefix}This is user-discussed text inside a turn", 4)
        val result = compress(
            listOf(system("System", 0)) + preambles +
                listOf(user("Old", 3), markedWithinTurn, user("New", 5)),
            1,
        )

        assertTrue((preambles + markedWithinTurn).all { it in result.requestMessages })
    }

    @Test
    fun testDoesNotNestTheFrameWhenTheSummariserEchoesAnExistingHandover() = runTest {
        val first = compress(twoTurnMessages(), 1, summaryText = "The report is still pending")
        val firstHandover = first.messages[1].textContent()
        val second = compress(first.messages + user("Continue again", 4), 1, summaryText = firstHandover)

        assertEquals(firstHandover, assertHandover(second.messages[1], "The report is still pending"))
    }

    @Test
    fun testSummaryParametersReplaceAnswerSettingsOnlyForTheSummaryCall() = runTest {
        val answerParams = responsesParamsWithSavedContainer().copy(
            reasoning = ReasoningConfig(effort = ReasoningEffort.HIGH),
        )
        val summaryParams = OpenAIResponsesParams(
            maxTokens = 73,
            reasoning = ReasoningConfig(effort = ReasoningEffort.LOW),
            codeInterpreter = OpenAICodeInterpreterConfig(
                fileIds = listOf("summary-input"),
                containerId = "summary-container",
            ),
        )
        val executor = RecordingPromptExecutor { request ->
            val actual = assertIs<OpenAIResponsesParams>(request.params)
            assertEquals(summaryParams.withCodeInterpreter(summaryParams.codeInterpreter?.copy(containerId = null)), actual)
            assertEquals(73, actual.maxTokens)
            assertEquals(ReasoningEffort.LOW, actual.reasoning?.effort)
            assertNull(actual.temperature, "A full replacement must not inherit answer temperature")
            assertEquals(listOf("summary-input"), actual.codeInterpreter?.fileIds)
            assistant("Summary", 10)
        }
        val context = context(twoTurnMessages(), defaultModel, executor, answerParams)

        context.writeSession { HistoryCompressionStrategy.Tiered(1, summaryParams).compress(this, emptyList()) }

        val finalParams = context.readSession { prompt.params }
        assertPortableExecutionParams(answerParams, finalParams)
        assertEquals(ReasoningEffort.HIGH, assertIs<OpenAIResponsesParams>(finalParams).reasoning?.effort)
        assertEquals(listOf(defaultModel), executor.models)
        assertEquals("summary-container", summaryParams.codeInterpreter?.containerId)
        assertEquals("saved-container", answerParams.codeInterpreter?.containerId)
    }

    @Test
    fun testNullSummaryParametersInheritTypedSettingsWithoutInventingReasoningDefaults() = runTest {
        val params = responsesParamsWithSavedContainer()
        val executor = RecordingPromptExecutor { request ->
            assertPortableExecutionParams(params, request.params)
            assertNull(assertIs<OpenAIResponsesParams>(request.params).reasoning)
            assistant("Summary", 10)
        }
        val context = context(twoTurnMessages(), defaultModel, executor, params)

        context.writeSession { HistoryCompressionStrategy.Tiered(1, null).compress(this, emptyList()) }

        assertPortableExecutionParams(params, context.readSession { prompt.params })
    }

    @Test
    fun testTokenMetricsUseTheExactOriginalAndFinalPromptsEvenWhenCompressionGrows() = runTest {
        val privateAnswer = Message.Assistant(
            parts = listOf(MessagePart.Text("Old answer"), MessagePart.Reasoning("private", encrypted = "signature")),
            metaInfo = responseMeta(2),
            id = "provider-id",
        )
        val context = context(
            listOf(system("System", 0), user("Old", 1), privateAnswer, user("New", 3)),
            defaultModel,
            executorReturning(assistant("A longer handover", 10)),
            responsesParamsWithSavedContainer(),
        )
        val original = context.readSession { prompt }
        val tokenizer = RecordingTokenizer { if (it === original) 11 else 29 }
        val metrics = mutableListOf<Pair<Int, Int>>()
        val strategy = HistoryCompressionStrategy.Tiered(1, null, tokenizer) { before, after -> metrics += before to after }

        context.writeSession { strategy.compress(this, emptyList()) }

        val finalPrompt = context.readSession { prompt }
        assertEquals(2, tokenizer.prompts.size)
        assertSame(original, tokenizer.prompts[0])
        assertSame(finalPrompt, tokenizer.prompts[1])
        assertEquals(listOf(11 to 29), metrics)
        assertHandover(finalPrompt.messages[1], "A longer handover")
        assertNull(assertIs<OpenAIResponsesParams>(finalPrompt.params).codeInterpreter?.containerId)
        assertTrue(finalPrompt.messages.flatMap(Message::parts).none { it is MessagePart.Reasoning })
    }

    @Test
    fun testNoOpSkipsSummaryTokenizerAndCallback() = runTest {
        val executor = RecordingPromptExecutor { error("A no-op must not request a summary") }
        val context = context(listOf(user("Only turn", 1)), defaultModel, executor, responsesParamsWithSavedContainer())
        val original = context.readSession { prompt }
        val tokenizer = RecordingTokenizer { error("A no-op must not count tokens") }
        val strategy = HistoryCompressionStrategy.Tiered(1, null, tokenizer) { _, _ -> error("A no-op must not report metrics") }

        context.writeSession { strategy.compress(this, emptyList()) }

        assertSame(original, context.readSession { prompt })
        assertTrue(tokenizer.prompts.isEmpty())
    }

    @Test
    fun testTokenizerFailureAtEitherCountRestoresExactOriginalPrompt() = runTest {
        for (failingCall in 1..2) {
            val failure = IllegalStateException("Tokenizer failed on call $failingCall")
            var calls = 0
            var callbacks = 0
            val tokenizer = RecordingTokenizer {
                calls += 1
                if (calls == failingCall) throw failure
                100
            }
            val context = context(
                twoTurnMessages(),
                defaultModel,
                executorReturning(assistant("Summary", 10)),
                responsesParamsWithSavedContainer(),
            )
            val original = context.readSession { prompt }
            val strategy = HistoryCompressionStrategy.Tiered(1, null, tokenizer) { _, _ -> callbacks += 1 }

            val thrown = assertFailsWith<IllegalStateException> {
                context.writeSession { strategy.compress(this, emptyList()) }
            }

            assertSame(failure, thrown)
            assertSame(original, context.readSession { prompt })
            assertEquals(failingCall, calls)
            assertEquals(0, callbacks)
        }
    }

    @Test
    fun testCallbackFailureRestoresExactOriginalPromptAndPropagatesTheSameException() = runTest {
        val failure = IllegalStateException("Metrics callback failed")
        var callbacks = 0
        val tokenizer = RecordingTokenizer { 100 }
        val context = context(
            twoTurnMessages(),
            defaultModel,
            executorReturning(assistant("Summary", 10)),
            responsesParamsWithSavedContainer(),
        )
        val original = context.readSession { prompt }
        val strategy = HistoryCompressionStrategy.Tiered(1, null, tokenizer) { _, _ ->
            callbacks += 1
            throw failure
        }

        val thrown = assertFailsWith<IllegalStateException> {
            context.writeSession { strategy.compress(this, emptyList()) }
        }

        assertSame(failure, thrown)
        assertSame(original, context.readSession { prompt })
        assertEquals(1, callbacks)
        assertEquals(2, tokenizer.prompts.size)
    }

    @Test
    fun testSummaryFailureDoesNotReportCompressionMetrics() = runTest {
        val failure = IllegalStateException("Summary failed")
        var callbacks = 0
        val context = context(twoTurnMessages(), defaultModel, RecordingPromptExecutor { throw failure })
        val original = context.readSession { prompt }
        val strategy = HistoryCompressionStrategy.Tiered(1, null, RecordingTokenizer { 100 }) { _, _ -> callbacks += 1 }

        val thrown = assertFailsWith<IllegalStateException> {
            context.writeSession { strategy.compress(this, emptyList()) }
        }

        assertSame(failure, thrown)
        assertSame(original, context.readSession { prompt })
        assertEquals(0, callbacks)
    }

    @Test
    fun testConfiguredFactoryRejectsNonPositiveTurnsAndCallbackWithoutTokenizer() {
        assertFailsWith<IllegalArgumentException> { HistoryCompressionStrategy.Tiered(0, null) }
        assertFailsWith<IllegalArgumentException> { HistoryCompressionStrategy.Tiered(-1, LLMParams()) }
        assertFailsWith<IllegalArgumentException> {
            HistoryCompressionStrategy.Tiered(1, null, onCompression = { _, _ -> })
        }
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

    @Test
    fun testBudgetedRetentionShrinksToFitIncludingSummaryOnly() = runTest {
        for (expected in 0..3) {
            val size = when (expected) {
                3 -> 500
                2 -> 1000
                1 -> 1800
                else -> 4000
            }
            val messages = listOf(system("System", 0)) + (1..5).map { user("turn$it " + "x".repeat(size), it) }
            val requests = mutableListOf<Prompt>()
            val executor = RecordingPromptExecutor {
                requests += it
                assistant("Summary", 10)
            }
            val context = context(messages, defaultModel, executor)
            var retained = -1
            context.writeSession {
                HistoryCompressionStrategy.Budgeted(3, 3000, characterTokenizer(), onCompression = { _, after, turns ->
                    assertTrue(after <= 3000)
                    retained = turns
                }).compress(this, emptyList())
            }
            assertEquals(expected, retained)
            val result = context.readSession { prompt }
            assertEquals(messages.takeLast(expected), result.messages.drop(2))
            assertHandover(result.messages[1], "Summary")
            assertTrue(requests.all { characterTokenizer().tokenCountFor(it) <= 3000 })
            assertTrue(requests.isNotEmpty())
        }
    }

    @Test
    fun testBudgetedCompressionSplitsHugeSingleToolTurnAndPreservesAllText() = runTest {
        val toolText = (1..6000).joinToString(" ") { "observation$it" }
        val messages = listOf(
            system("System", 0),
            user("Read file", 1),
            toolCall("call-1", 2),
            Message.User(MessagePart.Tool.Result("call-1", "lookup", toolText), requestMeta(3))
        )
        val requests = mutableListOf<Prompt>()
        val context = context(
            messages,
            defaultModel,
            RecordingPromptExecutor {
                requests += it
                assistant("Summary", 10)
            }
        )
        context.writeSession { HistoryCompressionStrategy.Budgeted(3, 3000, characterTokenizer()).compress(this, emptyList()) }
        assertTrue(requests.size > 2)
        assertTrue(requests.all { characterTokenizer().tokenCountFor(it) <= 3000 })
        assertTrue(requests.all { request -> request.messages.none { message -> message.parts.any { it is MessagePart.Tool } } })
        val historicalText = requests.flatMap { it.messages }.filterIsInstance<Message.User>()
            .filterNot { it.textContent().contains("Write a concise handover") }.joinToString("") { it.textContent().substringAfter("\n") }
        assertTrue(historicalText.contains(toolText))
        assertEquals(2, context.readSession { prompt.messages.size })
    }

    @Test
    fun testBudgetedFailureAndCancellationRestoreOriginalPrompt() = runTest {
        for (cancel in listOf(false, true)) {
            var requests = 0
            val context = context(
                listOf(system("System", 0), user("x".repeat(8000), 1)),
                defaultModel,
                RecordingPromptExecutor {
                    requests += 1
                    if (requests > 1 && cancel) throw CancellationException("cancel")
                    assistant(if (requests > 1) "" else "Summary", 10).copy(finishReason = "content_filtered")
                }
            )
            val original = context.readSession { prompt }
            if (cancel) {
                assertFailsWith<CancellationException> {
                    context.writeSession { HistoryCompressionStrategy.Budgeted(3, 3000, characterTokenizer()).compress(this, emptyList()) }
                }
            } else {
                val failure = assertFailsWith<IllegalStateException> {
                    context.writeSession { HistoryCompressionStrategy.Budgeted(3, 3000, characterTokenizer()).compress(this, emptyList()) }
                }
                assertTrue(failure.message.orEmpty().contains("finishReason=content_filtered"))
            }
            assertEquals(original, context.readSession { prompt })
        }
    }

    @Test
    fun testBudgetedRejectsUnavoidableOversizedSystemWithoutCallingProvider() = runTest {
        var calls = 0
        val context = context(
            listOf(system("x".repeat(4000), 0), user("question", 1)),
            defaultModel,
            RecordingPromptExecutor {
                calls += 1
                assistant("Summary", 10)
            }
        )
        assertFailsWith<IllegalStateException> {
            context.writeSession { HistoryCompressionStrategy.Budgeted(0, 3000, characterTokenizer()).compress(this, emptyList()) }
        }
        assertEquals(0, calls)
    }

    @Test
    fun testStreamingPreparationSeesToolResultsAndCanStopTheRequest() = runTest {
        for (fail in listOf(false, true)) {
            var preparationCalls = 0
            val executor = RecordingPromptExecutor { assistant("unused", 10) }
            val graph = strategy<String, String>("prepare-stream") {
                val results by node<String, ReceivedToolResults> {
                    llm.writeSession { appendPrompt { message(toolCall("call-1", 2)) } }
                    ReceivedToolResults(
                        listOf(
                            ReceivedToolResult(
                                id = "call-1",
                                tool = "lookup",
                                toolArgs = ai.koog.serialization.JSONObject(emptyMap()),
                                toolDescription = null,
                                output = "large tool result",
                                resultKind = ToolResultKind.Success,
                                result = null,
                            )
                        )
                    )
                }
                val sendResults by nodeLLMSendToolResultsStreaming(beforeRequest = {
                    preparationCalls += 1
                    assertEquals("large tool result", prompt.messages.last().parts.filterIsInstance<MessagePart.Tool.Result>().single().output)
                    if (fail) error("Preparation failed")
                    prompt = prompt.copy(messages = listOf(system("System", 0), user("Prepared", 1)))
                })
                val collect by node<Flow<StreamFrame>, String> {
                    it.toList()
                    "done"
                }
                edge(nodeStart forwardTo results)
                edge(results forwardTo sendResults)
                edge(sendResults forwardTo collect)
                edge(collect forwardTo nodeFinish)
            }
            val agent = AIAgent(promptExecutor = executor, strategy = graph, llmModel = defaultModel, systemPrompt = "System")
            if (fail) {
                assertFailsWith<IllegalStateException> { agent.run("Question") }
            } else {
                assertEquals("done", agent.run("Question"))
            }
            assertEquals(1, preparationCalls)
            assertEquals(if (fail) 0 else 1, executor.streamingPrompts.size)
            if (!fail) assertEquals("Prepared", executor.streamingPrompts.single().messages.last().textContent())
            agent.close()
        }
    }

    @Test
    fun testBudgetedFragmentsKeepTheirSourceAndToolFailureStatus() = runTest {
        for (source in listOf("user", "assistant", "failed", "succeeded")) {
            val content = "observation ".repeat(1200)
            val record = when (source) {
                "user" -> user(content, 1)
                "assistant" -> assistant(content, 2)
                else -> Message.User(
                    MessagePart.Tool.Result(
                        "read-7",
                        "read_file",
                        content,
                        isError = source == "failed",
                    ),
                    requestMeta(3)
                )
            }
            val requests = mutableListOf<Prompt>()
            val context = context(
                listOf(system("System", 0), record),
                defaultModel,
                RecordingPromptExecutor {
                    requests += it
                    assistant("Summary", 10)
                }
            )
            context.writeSession { HistoryCompressionStrategy.Budgeted(0, 3000, characterTokenizer()).compress(this, emptyList()) }
            assertTrue(requests.size > 2)
            val fragments = requests.flatMap { it.messages }.filterIsInstance<Message.User>()
                .filterNot { it.textContent().contains("Write a concise handover") }
            assertEquals(content, fragments.joinToString("") { it.textContent().substringAfter("\n") })
            fragments.forEach {
                val header = it.textContent().substringBefore("\n")
                if (source in listOf("failed", "succeeded")) {
                    assertTrue(header.contains("tool result read_file (read-7), status=$source"))
                } else {
                    assertTrue(header.lowercase().contains("historical $source text"))
                }
            }
        }
    }

    @Test
    fun testBudgetedMemoryAppearsOnceAndRetainedMemoryKeepsItsPosition() = runTest {
        val oldMemory = assistant("Keep this old fact", 1)
        val recent = user("Keep this recent request", 4)
        val messages = listOf(
            system("System", 0),
            oldMemory,
            user("Older request", 2),
            assistant("Older answer", 3),
            recent,
            assistant("Recent answer", 5)
        )
        val context = context(messages, defaultModel, executorReturning(assistant("Summary", 10)))
        context.writeSession {
            HistoryCompressionStrategy.Budgeted(1, 3000, characterTokenizer()).compress(this, listOf(oldMemory, recent, oldMemory))
        }
        val result = context.readSession { prompt.messages }
        assertEquals(1, result.count { it == oldMemory })
        assertEquals(1, result.count { it == recent })
        assertEquals(messages.takeLast(2), result.takeLast(2))
        assertEquals(oldMemory, result[1])
    }

    @Test
    fun testBudgetedMemoryOnlyPrefixNeedsNoRedundantSummary() = runTest {
        val memory = user("Preserved request", 1)
        val messages = listOf(system("System", 0), memory, user("New request", 2))
        var calls = 0
        val context = context(
            messages,
            defaultModel,
            RecordingPromptExecutor {
                calls++
                assistant("Unused", 10)
            }
        )
        context.writeSession { HistoryCompressionStrategy.Budgeted(1, 3000, characterTokenizer()).compress(this, listOf(memory)) }
        assertEquals(messages, context.readSession { prompt.messages })
        assertEquals(0, calls)
    }

    private fun characterTokenizer(): PromptTokenizer = RecordingTokenizer { prompt ->
        prompt.messages.sumOf { it.textContent().length }
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

    private fun assertHistoricalToolExchange(messages: List<Message>, callId: String) {
        assertEquals(2, messages.size)
        val call = assertIs<Message.Assistant>(messages[0])
        val result = assertIs<Message.User>(messages[1])
        val callDetails = Json.parseToJsonElement(assertIs<MessagePart.Text>(call.parts.single()).text).jsonObject
        val resultDetails = Json.parseToJsonElement(assertIs<MessagePart.Text>(result.parts.single()).text).jsonObject
        for (details in listOf(callDetails, resultDetails)) {
            assertEquals(callId, details.getValue("tool_call_id").jsonPrimitive.content)
            assertEquals("lookup", details.getValue("tool_name").jsonPrimitive.content)
        }
        assertEquals(JsonObject(emptyMap()), callDetails.getValue("tool_args"))
        assertEquals("result", resultDetails.getValue("tool_result").jsonPrimitive.content)
    }

    private fun assertHandover(message: Message, body: String): String {
        val text = assertIs<Message.Assistant>(message).textContent()
        assertTrue(text.startsWith(handoverPrefix), "The runtime must frame the summary as a handover")
        assertTrue(text.endsWith(body), "The generated summary body must survive verbatim")
        assertEquals(1, text.split(handoverPrefix).size - 1)
        assertTrue(message.parts.all { it is MessagePart.Text })
        return text
    }

    private class RecordingTokenizer(
        private val count: (Prompt) -> Int,
    ) : PromptTokenizer {
        val prompts = mutableListOf<Prompt>()

        override fun tokenCountFor(message: Message): Int = error("Count the complete prompt")

        override fun tokenCountFor(prompt: Prompt): Int {
            prompts += prompt
            return count(prompt)
        }
    }

    private class RecordingPromptExecutor(

        private val response: (Prompt) -> Message.Assistant,
    ) : PromptExecutor() {
        val models = mutableListOf<LLModel>()
        val streamingPrompts = mutableListOf<Prompt>()

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant {
            models += model
            return response(prompt)
        }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Flow<StreamFrame> {
            streamingPrompts += prompt
            return emptyFlow()
        }

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            throw UnsupportedOperationException("Moderation is not needed for this test")

        override fun close() {}
    }
}
