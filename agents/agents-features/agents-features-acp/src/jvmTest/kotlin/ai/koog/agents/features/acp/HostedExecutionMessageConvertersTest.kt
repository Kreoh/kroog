package ai.koog.agents.features.acp

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HostedExecutionMessageConvertersTest {

    @Test
    fun testHostedExecutionLifecycleRetainsIdContentAndStatus() {
        val message = Message.Assistant(
            parts = listOf(
                MessagePart.HostedExecution.Request(
                    code = "print('hello')",
                    language = "python",
                    executionId = "exec_123",
                    toolCallId = "tool_123",
                ),
                MessagePart.HostedExecution.Progress(
                    message = "Starting container",
                    sequence = 1,
                    executionId = "exec_123",
                    toolCallId = "tool_123",
                ),
                MessagePart.HostedExecution.CumulativeOutput(
                    output = "hello",
                    sequence = 2,
                    executionId = "exec_123",
                    toolCallId = "tool_123",
                ),
                MessagePart.HostedExecution.Result(
                    output = "done",
                    exitCode = 0,
                    generatedFiles = listOf(
                        MessagePart.GeneratedFile(
                            providerFileId = "provider_file_123",
                            filename = "result.txt",
                            fileId = "file_123",
                        )
                    ),
                    executionId = "exec_123",
                    toolCallId = "tool_123",
                ),
                MessagePart.HostedExecution.Error(
                    message = "Timed out",
                    code = "timeout",
                    executionId = "exec_456",
                    toolCallId = "tool_456",
                ),
            ),
            metaInfo = ResponseMetaInfo.Empty,
        )

        val updates = message.toAcpEvents().map { it.update }
        val request = assertIs<SessionUpdate.ToolCall>(updates[0])
        val progress = assertIs<SessionUpdate.ToolCallUpdate>(updates[1])
        val output = assertIs<SessionUpdate.ToolCallUpdate>(updates[2])
        val result = assertIs<SessionUpdate.ToolCallUpdate>(updates[3])
        val error = assertIs<SessionUpdate.ToolCallUpdate>(updates[4])

        assertUpdate(request.toolCallId, request.status, request.content, "tool_123", ToolCallStatus.PENDING)
        assertEquals("Execute python:\nprint('hello')", request.contentText())

        assertUpdate(progress.toolCallId, progress.status, progress.content, "tool_123", ToolCallStatus.IN_PROGRESS)
        assertEquals("Progress 1: Starting container", progress.contentText())

        assertUpdate(output.toolCallId, output.status, output.content, "tool_123", ToolCallStatus.IN_PROGRESS)
        assertEquals("Output 2:\nhello", output.contentText())

        assertUpdate(result.toolCallId, result.status, result.content, "tool_123", ToolCallStatus.COMPLETED)
        assertEquals("Result (exit code 0):\ndone", result.contentText(0))
        assertEquals(
            "Generated file result.txt, provider file id provider_file_123, file id file_123",
            result.contentText(1),
        )

        assertUpdate(error.toolCallId, error.status, error.content, "tool_456", ToolCallStatus.FAILED)
        assertEquals("Error (timeout): Timed out", error.contentText())
    }

    @Test
    fun testHostedExecutionNonZeroResultIsFailed() {
        val message = Message.Assistant(
            parts = listOf(
                MessagePart.HostedExecution.Result(
                    output = "failure output",
                    exitCode = 7,
                    executionId = "exec_failed",
                )
            ),
            metaInfo = ResponseMetaInfo.Empty,
        )

        val update = assertIs<SessionUpdate.ToolCallUpdate>(message.toAcpEvents().single().update)

        assertEquals(ToolCallId("exec_failed"), update.toolCallId)
        assertEquals(ToolCallStatus.FAILED, update.status)
        assertEquals("Result (exit code 7):\nfailure output", update.contentText())
    }

    private fun assertUpdate(
        id: ToolCallId,
        status: ToolCallStatus?,
        content: List<ToolCallContent>?,
        expectedId: String,
        expectedStatus: ToolCallStatus,
    ) {
        assertEquals(ToolCallId(expectedId), id)
        assertEquals(expectedStatus, status)
        assertEquals(true, content?.isNotEmpty())
    }

    private fun SessionUpdate.ToolCall.contentText(index: Int = 0): String {
        return content.orEmpty()[index].text()
    }

    private fun SessionUpdate.ToolCallUpdate.contentText(index: Int = 0): String {
        return content.orEmpty()[index].text()
    }

    private fun ToolCallContent.text(): String {
        val content = assertIs<ToolCallContent.Content>(this)
        return assertIs<ContentBlock.Text>(content.content).text
    }
}
