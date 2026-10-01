@file:OptIn(ai.koog.agents.core.annotation.InternalAgentsApi::class)

package ai.koog.agents.core.agent.session

import ai.koog.agents.core.CalculatorChatExecutor.testClock
import ai.koog.agents.core.agent.context.AIAgentLLMContext
import ai.koog.agents.core.agent.entity.AIAgentNode
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import ai.koog.prompt.structure.markdown.MarkdownStructureDefinition
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.jdk9.asFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class AIAgentLLMStreamingPublisherHistoryTest : StreamingHistoryTestBase() {
    @Test
    fun testPublisherCollectedInActiveSessionAppendsHistory() = runTest {
        val session = createSession(RecordingExecutor(frames))
        session.requestLLMStreamingBlocking().asFlow().toList()
        assertEquals(listOf(frames.toMessageResponse()), session.prompt.messages)
    }

    @Test
    fun testPublisherSubscribedAfterCloseFailsBeforeExecutorCall() = runTest {
        val executor = RecordingExecutor(frames)
        val session = createSession(executor)
        val publisher = session.requestLLMStreamingBlocking()
        session.close()
        assertFailsWith<IllegalStateException> { publisher.asFlow().toList() }
        assertEquals(0, executor.streamRequests)
        assertEquals(0, executor.collections)
    }

    @Test
    fun testStructuredPublisherSubscribedAfterCloseFailsBeforeExecutorCall() = runTest {
        val executor = RecordingExecutor(frames)
        val session = createSession(executor)
        val publisher = session.requestLLMStreamingBlocking(MarkdownStructureDefinition("answer", schema = { +"Answer" }))
        session.close()
        assertFailsWith<IllegalStateException> { publisher.asFlow().toList() }
        assertEquals(0, executor.streamRequests)
        assertEquals(0, executor.collections)
    }

    @Test
    fun testJvmMessageStreamingNodeKeepsSessionOpenAndPersistsHistory() = runTest {
        val config = createTestConfig()
        val executor = RecordingExecutor(frames)
        val llm = AIAgentLLMContext(
            tools = emptyList(),
            prompt = config.prompt,
            model = config.model,
            responseProcessor = null,
            promptExecutor = executor,
            environment = createTestEnvironment(),
            config = config,
            clock = testClock,
        )
        val context = createTestContext(llmContext = llm)
        val node = AIAgentNode.llmSendMessageStreaming(
            transformStreamData = { it },
            outputClass = StreamFrame::class.java,
        )
        assertNotNull(node.execute(context, Message.User("Question", RequestMetaInfo.Empty))).asFlow().toList()
        llm.readSession {
            assertEquals(frames.toMessageResponse(), prompt.messages.filterIsInstance<Message.Assistant>().single())
        }
    }

    @Test
    fun testJvmTextStreamingNodeKeepsSessionOpenAndPersistsHistory() = runTest {
        val config = createTestConfig()
        val executor = RecordingExecutor(frames)
        val llm = AIAgentLLMContext(
            tools = emptyList(),
            prompt = config.prompt,
            model = config.model,
            responseProcessor = null,
            promptExecutor = executor,
            environment = createTestEnvironment(),
            config = config,
            clock = testClock,
        )
        val context = createTestContext(llmContext = llm)
        val node = AIAgentNode.llmRequestStreaming(
            transformStreamData = { it },
            outputClass = StreamFrame::class.java,
        )
        assertNotNull(node.execute(context, "Question")).asFlow().toList()
        llm.readSession {
            assertEquals(frames.toMessageResponse(), prompt.messages.filterIsInstance<Message.Assistant>().single())
        }
    }
}
