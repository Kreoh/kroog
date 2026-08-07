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

class CodeExecutionMessageConvertersTest {

    @Test
    fun testGeneratedFileAcpEventRetainsIdentifiersAndMetadata() {
        val message = Message.Assistant(
            parts = listOf(
                MessagePart.GeneratedFile(
                    providerFileId = "provider_file_123",
                    containerId = "cntr_123",
                    filename = "report.txt",
                    mediaType = "text/plain",
                    sizeBytes = 42,
                    producingExecutionId = "exec_123",
                    providerItemId = "provider_item_123",
                    fileId = "file_123",
                    toolCallId = "tool_123",
                )
            ),
            metaInfo = ResponseMetaInfo.Empty,
        )

        val event = message.toAcpEvents().single()
        val update = assertIs<SessionUpdate.ToolCallUpdate>(event.update)
        val content = assertIs<ToolCallContent.Content>(update.content?.single())
        val text = assertIs<ContentBlock.Text>(content.content)

        assertEquals(ToolCallId("tool_123"), update.toolCallId)
        assertEquals(ToolCallStatus.COMPLETED, update.status)
        assertEquals(
            "Generated file report.txt (text/plain), 42 bytes, tool call id tool_123, " +
                "provider file id provider_file_123, file id file_123, producing execution id exec_123, " +
                "provider item id provider_item_123, container id cntr_123",
            text.text,
        )
    }

    @Test
    fun testCodeExecutionAcpEventRetainsCodeOrderedOutputsAndFailure() {
        val message = Message.Assistant(
            parts = listOf(
                MessagePart.CodeExecution(
                    id = "ci_123",
                    code = "print('acp')",
                    containerId = "cntr_123",
                    outputs = listOf(
                        MessagePart.CodeExecution.Output.Logs("first output"),
                        MessagePart.CodeExecution.Output.Image("https://example.test/second.png"),
                        MessagePart.CodeExecution.Output.Logs("third output"),
                    ),
                    failure = MessagePart.CodeExecution.Failure.FAILED,
                )
            ),
            metaInfo = ResponseMetaInfo.Empty,
        )

        val event = message.toAcpEvents().single()
        val update = assertIs<SessionUpdate.AgentMessageChunk>(event.update)
        val content = assertIs<ContentBlock.Text>(update.content)

        assertEquals(
            """
            Code execution ci_123 in container cntr_123:
            print('acp')
            Outputs:
            first output
            https://example.test/second.png
            third output
            Status: failed
            """.trimIndent(),
            content.text,
        )
    }
}
