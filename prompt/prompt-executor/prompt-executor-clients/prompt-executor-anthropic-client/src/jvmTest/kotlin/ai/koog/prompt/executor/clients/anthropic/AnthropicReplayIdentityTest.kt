package ai.koog.prompt.executor.clients.anthropic

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class AnthropicReplayIdentityTest {
    @Test
    fun testMissingFunctionCallIdFailsBeforeTransportRequestExists() {
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
            AnthropicLLMClient(apiKey = "unused").createAnthropicRequest(
                prompt,
                emptyList(),
                AnthropicModels.Sonnet_4,
                false,
            )
        }
    }
}
