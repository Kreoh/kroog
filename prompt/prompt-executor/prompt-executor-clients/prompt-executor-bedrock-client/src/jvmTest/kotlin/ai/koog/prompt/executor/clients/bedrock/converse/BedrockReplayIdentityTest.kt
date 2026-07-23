package ai.koog.prompt.executor.clients.bedrock.converse

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class BedrockReplayIdentityTest {
    @Test
    fun testMissingFunctionCallIdFailsBeforeConverseTransportRequestExists() {
        val prompt = Prompt(
            messages = listOf(
                Message.Assistant(
                    part = MessagePart.Tool.Call(id = null, tool = "lookup", args = "{}"),
                    metaInfo = ResponseMetaInfo.Empty,
                )
            ),
            id = "missing-call-id",
        )

        assertFailsWith<IllegalArgumentException> {
            BedrockConverseConverters.createConverseRequest(
                prompt,
                BedrockModels.AnthropicClaude4Sonnet,
                emptyList(),
            )
        }
    }
}
