package ai.koog.prompt.executor.clients.anthropic

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.ClientManagedPresentationReplayException
import ai.koog.prompt.message.ExecutionOrigin
import ai.koog.prompt.message.ManagedExecutionSessionReference
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.test.utils.CapturingKoogHttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicCodeExecutionReplayTest {
    @Test
    fun testClientManagedPresentationUsesOnlyOrdinaryCustomToolTranscript() {
        val client = AnthropicLLMClient(
            settings = AnthropicClientSettings(baseUrl = "https://unused.test"),
            httpClient = CapturingKoogHttpClient(clientName = "UnusedAnthropicTransport") {
                error("Transport must not be invoked")
            },
        )

        val request = client.createAnthropicRequest(
            prompt = managedPrompt(),
            tools = emptyList(),
            model = AnthropicModels.Sonnet_4,
            stream = false,
        )

        assertTrue(request.contains(""""type":"tool_use""""))
        assertTrue(request.contains(""""type":"tool_result""""))
        assertTrue(request.contains("managed_execution"))
        assertFalse(request.contains("presentation-only-output"))
        assertFalse(request.contains("sandbox-secret"))
        assertFalse(request.contains("provider-item-secret"))
    }

    @Test
    fun testMalformedManagedTranscriptFailsBeforeAnthropicTransport() = runTest {
        val transport = CapturingKoogHttpClient(clientName = "UnusedAnthropicTransport") {
            error("Transport must not be invoked")
        }
        val client = AnthropicLLMClient(
            settings = AnthropicClientSettings(baseUrl = "https://unused.test"),
            httpClient = transport,
        )

        assertFailsWith<ClientManagedPresentationReplayException> {
            client.execute(managedPrompt(resultTool = "wrong_tool"), AnthropicModels.Sonnet_4)
        }
        assertNull(transport.lastPath)
        assertNull(transport.lastRequest)
    }

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

    private fun managedPrompt(resultTool: String = "managed_execution"): Prompt {
        val session = ManagedExecutionSessionReference.VertexAgentEngine(
            project = "project-1",
            location = "us-central1",
            reasoningEngineResource = "reasoningEngines/engine-1",
            sandboxResourceName = "sandbox-secret",
        )
        return Prompt(
            messages = listOf(
                Message.Assistant(
                    parts = listOf(
                        MessagePart.Tool.Call(
                            id = "call-1",
                            tool = resultTool,
                            args = """{"executionId":"execution-1","code":"print(1)"}""",
                        ),
                        MessagePart.HostedExecution.Result(
                            output = "presentation-only-output",
                            executionId = "execution-1",
                            providerItemId = "provider-item-secret",
                            origin = ExecutionOrigin.CLIENT_MANAGED,
                            managedSession = session,
                            toolCallId = "call-1",
                        ),
                    ),
                    metaInfo = ResponseMetaInfo.Empty,
                ),
                Message.User(
                    part = MessagePart.Tool.Result(
                        id = "call-1",
                        tool = resultTool,
                        output = "ordinary-result",
                    ),
                    metaInfo = RequestMetaInfo.Empty,
                ),
            ),
            id = "managed-replay",
        )
    }
}
