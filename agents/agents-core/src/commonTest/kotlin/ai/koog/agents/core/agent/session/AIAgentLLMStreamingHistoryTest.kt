@file:OptIn(ai.koog.agents.core.annotation.InternalAgentsApi::class)

package ai.koog.agents.core.agent.session

import ai.koog.agents.core.CalculatorChatExecutor.testClock
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.MissingToolsConversionStrategy
import ai.koog.agents.core.agent.config.ToolCallDescriber
import ai.koog.agents.core.agent.context.AgentTestBase
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStreaming
import ai.koog.agents.core.dsl.extension.nodeLLMSendMessageStreaming
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResultsStreaming
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.IncompleteStreamException
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import ai.koog.prompt.structure.markdown.MarkdownStructureDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

abstract class StreamingHistoryTestBase : AgentTestBase() {
    protected val frames = listOf(
        StreamFrame.ReasoningComplete(id = "reasoning", content = listOf("Think"), index = 0, encrypted = "signature"),
        StreamFrame.TextDelta("Answer", index = 1),
        StreamFrame.TextComplete("Answer", index = 1),
        StreamFrame.ToolCallComplete(id = "call", name = "tool", content = "{}", index = 2),
        StreamFrame.End(
            "tool_calls",
            ResponseMetaInfo.create(testClock, inputTokensCount = 7, outputTokensCount = 3),
            "message",
        ),
    )

    protected class RecordingExecutor(
        private val frames: List<StreamFrame>,
        private val afterFrames: suspend FlowCollector<StreamFrame>.() -> Unit = {},
    ) : PromptExecutor() {
        var streamRequests = 0
        var collections = 0
        val prompts = mutableListOf<Prompt>()

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
            streamRequests++
            return flow {
                collections++
                prompts.add(prompt)
                frames.forEach { emit(it) }
                afterFrames()
            }
        }

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            prompts.add(prompt)
            return Message.Assistant("Follow-up", ResponseMetaInfo.Empty)
        }

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Unexpected moderation")
        override fun close() = Unit
    }

    protected fun createSession(executor: PromptExecutor): AIAgentLLMWriteSession {
        val config = createTestConfig().copy(
            missingToolsConversionStrategy = MissingToolsConversionStrategy.Missing(ToolCallDescriber.JSON),
        )
        return AIAgentLLMWriteSession(
            environment = createTestEnvironment(),
            executor = executor,
            tools = listOf(ToolDescriptor("tool", "Test tool")),
            toolRegistry = ToolRegistry {},
            prompt = config.prompt,
            model = config.model,
            responseProcessor = null,
            config = config,
            clock = testClock,
        )
    }
}

class AIAgentLLMStreamingHistoryTest : StreamingHistoryTestBase() {
    @Test
    fun testCompleteResponseAppendsOnceAfterCollectionWithMetadata() = runTest {
        val executor = RecordingExecutor(frames)
        val session = createSession(executor)
        val initial = session.prompt.messages
        session.requestLLMStreaming().collect {
            assertEquals(initial, session.prompt.messages)
        }
        assertEquals(initial + frames.toMessageResponse(), session.prompt.messages)
        session.requestLLM()
        assertEquals(initial + frames.toMessageResponse(), executor.prompts.last().messages)
    }

    @Test
    fun testStructuredStreamingAppendsResponseOnce() = runTest {
        val session = createSession(RecordingExecutor(frames))
        val definition = MarkdownStructureDefinition("answer", schema = { +"Answer" })
        session.requestLLMStreaming(definition).toList()
        assertEquals(1, session.prompt.messages.filterIsInstance<Message.Assistant>().size)
        assertEquals(frames.toMessageResponse(), session.prompt.messages.last())
    }

    @Test
    fun testEachSuccessfulColdCollectionAppendsOneResponse() = runTest {
        val executor = RecordingExecutor(frames)
        val session = createSession(executor)
        val stream = session.requestLLMStreaming()
        stream.toList()
        stream.toList()
        assertEquals(2, executor.collections)
        assertEquals(listOf(frames.toMessageResponse(), frames.toMessageResponse()), session.prompt.messages)
    }

    @Test
    fun testMissingEndDoesNotAppendHistory() = runTest {
        val session = createSession(RecordingExecutor(frames.dropLast(1)))
        assertFailsWith<IncompleteStreamException> { session.requestLLMStreaming().toList() }
        assertEquals(emptyList(), session.prompt.messages)
    }

    @Test
    fun testFailureAfterEndDoesNotAppendHistory() = runTest {
        val session = createSession(RecordingExecutor(frames) { error("Connection failed after End") })
        assertFailsWith<IllegalStateException> { session.requestLLMStreaming().toList() }
        assertEquals(emptyList(), session.prompt.messages)
    }

    @Test
    fun testCancellationAfterEndDoesNotAppendHistory() = runTest {
        val session = createSession(RecordingExecutor(frames) { throw CancellationException("Cancelled") })
        assertFailsWith<CancellationException> { session.requestLLMStreaming().toList() }
        assertEquals(emptyList(), session.prompt.messages)
    }

    @Test
    fun testTruncatedCollectionDoesNotAppendHistory() = runTest {
        val session = createSession(RecordingExecutor(frames))
        session.requestLLMStreaming().take(1).toList()
        assertEquals(emptyList(), session.prompt.messages)
    }

    @Test
    fun testFlowCollectedAfterSessionClosesFailsBeforeExecutorCall() = runTest {
        val executor = RecordingExecutor(frames)
        val session = createSession(executor)
        val stream = session.requestLLMStreaming()
        session.close()
        assertFailsWith<IllegalStateException> { stream.toList() }
        assertEquals(0, executor.streamRequests)
        assertEquals(0, executor.collections)
    }

    @Test
    fun testFunctionalStreamingPersistsHistoryForNextRequest() = runTest {
        val textFrames = frames.filterNot { it is StreamFrame.ToolCallComplete }
        val executor = RecordingExecutor(textFrames)
        val agent = AIAgent(
            promptExecutor = executor,
            llmModel = createTestConfig().model,
            strategy = functionalStrategy<String, String> { input ->
                requestLLMStreaming(input).toList()
                requestLLM("Next").textContent()
            },
        )
        agent.run("Question")
        assertEquals(1, executor.prompts.last().messages.filterIsInstance<Message.Assistant>().size)
        assertEquals(
            textFrames.toMessageResponse(),
            executor.prompts.last().messages.filterIsInstance<Message.Assistant>().single(),
        )
    }

    @Test
    fun testGraphStreamingNodePersistsHistoryForNextRequest() = runTest {
        val executor = RecordingExecutor(frames)
        val graph = strategy<String, String>("stream-history") {
            val stream by nodeLLMRequestStreaming()
            val followUp by node<Flow<StreamFrame>, String> { response ->
                response.toList()
                llm.writeSession { requestLLM().textContent() }
            }
            edge(nodeStart forwardTo stream)
            edge(stream forwardTo followUp)
            edge(followUp forwardTo nodeFinish)
        }
        AIAgent(promptExecutor = executor, llmModel = createTestConfig().model, strategy = graph).run("Question")
        assertEquals(1, executor.prompts.last().messages.filterIsInstance<Message.Assistant>().size)
    }

    @Test
    fun testTypedMessageStreamingNodePersistsHistory() = runTest {
        val executor = RecordingExecutor(frames)
        val graph = strategy<String, String>("typed-stream-history") {
            val stream by nodeLLMSendMessageStreaming()
            val collect by node<Flow<StreamFrame>, String> { response ->
                response.toList()
                llm.readSession { prompt.messages.filterIsInstance<Message.Assistant>().size.toString() }
            }
            edge(nodeStart forwardTo stream transformed { Message.User(it, RequestMetaInfo.Empty) })
            edge(stream forwardTo collect)
            edge(collect forwardTo nodeFinish)
        }
        val agent = AIAgent(promptExecutor = executor, llmModel = createTestConfig().model, strategy = graph)
        assertEquals("1", agent.run("Question"))
    }

    @Test
    fun testToolResultStreamingNodePersistsHistory() = runTest {
        val executor = RecordingExecutor(frames)
        val graph = strategy<String, String>("tool-result-stream-history") {
            val stream by nodeLLMSendToolResultsStreaming()
            val collect by node<Flow<StreamFrame>, String> { response ->
                response.toList()
                llm.readSession { prompt.messages.filterIsInstance<Message.Assistant>().size.toString() }
            }
            edge(nodeStart forwardTo stream transformed { ReceivedToolResults(emptyList()) })
            edge(stream forwardTo collect)
            edge(collect forwardTo nodeFinish)
        }
        val agent = AIAgent(promptExecutor = executor, llmModel = createTestConfig().model, strategy = graph)
        assertEquals("1", agent.run("Question"))
    }
}
