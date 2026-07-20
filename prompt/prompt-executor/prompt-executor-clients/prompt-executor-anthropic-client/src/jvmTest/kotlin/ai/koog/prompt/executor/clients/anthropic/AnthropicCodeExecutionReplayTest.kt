package ai.koog.prompt.executor.clients.anthropic

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.test.utils.CapturingKoogHttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AnthropicCodeExecutionReplayTest {

    @Test
    fun testCodeExecutionReplayFailsBeforeTransportInvocation() = runTest {
        val transport = CapturingKoogHttpClient(clientName = "UnusedAnthropicTransport") {
            error("Transport must not be invoked")
        }
        val client = AnthropicLLMClient(
            settings = AnthropicClientSettings(baseUrl = "https://unused.test"),
            httpClient = transport,
        )
        val prompt = Prompt(
            messages = listOf(
                Message.Assistant(
                    parts = listOf(
                        MessagePart.CodeExecution(
                            id = "ci_failed",
                            code = "raise RuntimeError()",
                            containerId = "cntr_123",
                            outputs = listOf(
                                MessagePart.CodeExecution.Output.Logs("before failure"),
                                MessagePart.CodeExecution.Output.Image("https://example.test/failure.png"),
                            ),
                            failure = MessagePart.CodeExecution.Failure.FAILED,
                        )
                    ),
                    metaInfo = ResponseMetaInfo.Empty,
                )
            ),
            id = "code-replay",
        )

        val failure = try {
            client.execute(prompt, AnthropicModels.Sonnet_4)
            null
        } catch (cause: IllegalArgumentException) {
            cause
        }

        assertNotNull(failure)
        assertEquals(
            "Anthropic cannot replay provider-hosted code execution items",
            failure.message,
        )
        assertNull(transport.lastPath)
        assertNull(transport.lastRequest)
    }
}
