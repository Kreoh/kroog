package ai.koog.prompt.executor.clients.openai

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.test.utils.CapturingKoogHttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

internal class OpenAIReplayIdentityTest {
    @Test
    fun testMissingRequiredReasoningProviderItemIdFailsBeforeTransport() = runTest {
        var transported = false
        val client = OpenAILLMClient(
            httpClient = CapturingKoogHttpClient("capture") {
                transported = true
                error("unexpected transport")
            },
        )
        val prompt = Prompt(
            messages = listOf(
                Message.Assistant(
                    part = MessagePart.Reasoning(content = emptyList(), encrypted = "opaque"),
                    metaInfo = ResponseMetaInfo.Empty,
                )
            ),
            id = "missing-reasoning-id",
            params = OpenAIResponsesParams(stateless = true),
        )

        assertFailsWith<IllegalArgumentException> {
            client.execute(prompt, OpenAIModels.Chat.GPT5_5Pro)
        }
        assertFalse(transported)
    }

    @Test
    fun testMissingRequiredCodeExecutionProviderItemIdFailsBeforeTransport() = runTest {
        var transported = false
        val client = OpenAILLMClient(
            httpClient = CapturingKoogHttpClient("capture") {
                transported = true
                error("unexpected transport")
            },
        )
        val prompt = Prompt(
            messages = listOf(
                Message.Assistant(
                    part = MessagePart.CodeExecution(
                        id = "ui-execution",
                        code = "print(1)",
                        containerId = "container",
                    ),
                    metaInfo = ResponseMetaInfo.Empty,
                )
            ),
            id = "missing-code-id",
            params = OpenAIResponsesParams(stateless = true),
        )

        assertFailsWith<IllegalArgumentException> {
            client.execute(prompt, OpenAIModels.Chat.GPT5_5Pro)
        }
        assertFalse(transported)
    }
}
