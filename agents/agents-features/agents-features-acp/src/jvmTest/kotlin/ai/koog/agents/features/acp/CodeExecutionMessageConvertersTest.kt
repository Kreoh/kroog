package ai.koog.agents.features.acp

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CodeExecutionMessageConvertersTest {

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
