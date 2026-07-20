package ai.koog.prompt.executor.clients.google

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

class GoogleCodeExecutionReplayTest {

    @Test
    fun testCodeExecutionReplayFailsBeforeTransportInvocation() = runTest {
        val transport = CapturingKoogHttpClient(clientName = "UnusedGoogleTransport") {
            error("Transport must not be invoked")
        }
        val client = GoogleLLMClient(
            settings = GoogleClientSettings(baseUrl = "https://unused.test"),
            httpClient = transport,
        )
        val prompt = Prompt(
            messages = listOf(
                Message.Assistant(
                    parts = listOf(
                        MessagePart.CodeExecution(
                            id = "ci_incomplete",
                            code = "while True: pass",
                            containerId = "cntr_123",
                            outputs = listOf(
                                MessagePart.CodeExecution.Output.Logs("partial output"),
                                MessagePart.CodeExecution.Output.Image("https://example.test/partial.png"),
                            ),
                            failure = MessagePart.CodeExecution.Failure.INCOMPLETE,
                        )
                    ),
                    metaInfo = ResponseMetaInfo.Empty,
                )
            ),
            id = "code-replay",
        )

        val failure = try {
            client.execute(prompt, GoogleModels.Gemini2_5Pro)
            null
        } catch (cause: IllegalArgumentException) {
            cause
        }

        assertNotNull(failure)
        assertEquals(
            "Google cannot replay provider-hosted code execution items",
            failure.message,
        )
        assertNull(transport.lastPath)
        assertNull(transport.lastRequest)
    }
}
