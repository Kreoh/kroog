package ai.koog.prompt.executor.clients.google

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.google.models.GooglePart
import ai.koog.prompt.message.ClientManagedPresentationReplayException
import ai.koog.prompt.message.ExecutionOrigin
import ai.koog.prompt.message.ManagedExecutionSessionReference
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GeminiReplayTest {
    @Test
    fun testClientManagedPresentationUsesOnlyCustomFunctionTranscript() {
        val client = GoogleLLMClient(apiKey = "test", httpClientFactory = KtorKoogHttpClient.Factory())
        val prompt = Prompt(
            messages = listOf(
                Message.Assistant(
                    parts = listOf(
                        MessagePart.Tool.Call(
                            id = "call-1",
                            tool = "managed_execution",
                            args = """{"executionId":"execution-1","code":"print(1)"}""",
                        ),
                        MessagePart.HostedExecution.Result(
                            output = "presentation-only-output",
                            executionId = "execution-1",
                            origin = ExecutionOrigin.CLIENT_MANAGED,
                            managedSession = ManagedExecutionSessionReference.VertexAgentEngine(
                                project = "project-1",
                                location = "us-central1",
                                reasoningEngineResource = "reasoningEngines/engine-1",
                                sandboxResourceName = "sandbox-secret",
                            ),
                            toolCallId = "call-1",
                        ),
                    ),
                    metaInfo = ResponseMetaInfo.Empty,
                ),
                Message.User(
                    part = MessagePart.Tool.Result(
                        id = "call-1",
                        tool = "managed_execution",
                        output = "ordinary-result",
                    ),
                    metaInfo = RequestMetaInfo.Empty,
                ),
            ),
            id = "managed-replay",
        )

        val request = client.createGoogleRequest(prompt, GoogleModels.Gemini2_5Pro, emptyList())

        request.toString().contains("presentation-only-output") shouldBe false
        request.toString().contains("sandbox-secret") shouldBe false
        request.contents.flatMap { it.parts.orEmpty() }.count { it is GooglePart.FunctionCall } shouldBe 1
        request.contents.flatMap { it.parts.orEmpty() }.count { it is GooglePart.FunctionResponse } shouldBe 1
    }

    @Test
    fun testMalformedManagedTranscriptFailsBeforeGeminiRequestMapping() {
        val client = GoogleLLMClient(apiKey = "test", httpClientFactory = KtorKoogHttpClient.Factory())
        val prompt = Prompt(
            messages = listOf(
                Message.Assistant(
                    parts = listOf(
                        MessagePart.Tool.Call("call-1", "wrong_tool", "{}"),
                        MessagePart.HostedExecution.Result(
                            output = "done",
                            executionId = "execution-1",
                            origin = ExecutionOrigin.CLIENT_MANAGED,
                            managedSession = ManagedExecutionSessionReference.VertexAgentEngine(
                                "project-1",
                                "us-central1",
                                "reasoningEngines/engine-1",
                                "sandbox-1",
                            ),
                            toolCallId = "call-1",
                        ),
                    ),
                    metaInfo = ResponseMetaInfo.Empty,
                ),
                Message.User(
                    MessagePart.Tool.Result("call-1", "wrong_tool", "done"),
                    RequestMetaInfo.Empty,
                ),
            ),
            id = "malformed-managed-replay",
        )

        assertFailsWith<ClientManagedPresentationReplayException> {
            client.createGoogleRequest(prompt, GoogleModels.Gemini2_5Pro, emptyList())
        }
    }

    @Test
    fun testLegacyLogOnlyCodeExecutionReplaysAsProviderOrderedParts() {
        val client = GoogleLLMClient(apiKey = "test", httpClientFactory = KtorKoogHttpClient.Factory())
        val prompt = Prompt(
            messages = listOf(
                Message.Assistant(
                    parts = listOf(
                        MessagePart.CodeExecution(
                            id = "legacy-execution",
                            code = "print('legacy')",
                            containerId = null,
                            outputs = listOf(MessagePart.CodeExecution.Output.Logs("legacy\n")),
                        )
                    ),
                    metaInfo = ResponseMetaInfo.Empty,
                )
            ),
            id = "legacy-replay",
        )

        val parts = client.createGoogleRequest(prompt, GoogleModels.Gemini2_5Pro, emptyList())
            .contents.single().parts.orEmpty()

        parts shouldHaveSize 2
        val code = parts[0].shouldBeInstanceOf<GooglePart.ExecutableCode>()
        code.executableCode.id shouldBe null
        code.executableCode.code shouldBe "print('legacy')"
        val result = parts[1].shouldBeInstanceOf<GooglePart.CodeExecutionResult>()
        result.codeExecutionResult.id shouldBe null
        result.codeExecutionResult.output shouldBe "legacy\n"
    }

    @Test
    fun testCodeAndResultReplayInProviderOrderWithoutContainer() {
        val client = GoogleLLMClient(apiKey = "test", httpClientFactory = KtorKoogHttpClient.Factory())
        val prompt = Prompt(
            messages = listOf(
                Message.Assistant(
                    parts = listOf(
                        MessagePart.Reasoning(content = emptyList(), encrypted = "code-signature"),
                        MessagePart.HostedExecution.Request(
                            code = "print('hello')",
                            executionId = "local-execution",
                            providerItemId = "provider-execution",
                        ),
                        MessagePart.Reasoning(content = emptyList(), encrypted = "result-signature"),
                        MessagePart.HostedExecution.Result(
                            output = "hello\n",
                            exitCode = 0,
                            executionId = "local-execution",
                            providerItemId = "provider-execution",
                        ),
                    ),
                    metaInfo = ResponseMetaInfo.Empty,
                )
            ),
            id = "replay",
        )

        val parts = client.createGoogleRequest(prompt, GoogleModels.Gemini2_5Pro, emptyList())
            .contents.single().parts.orEmpty()

        parts shouldHaveSize 2
        val code = parts[0].shouldBeInstanceOf<GooglePart.ExecutableCode>()
        code.executableCode.id shouldBe "provider-execution"
        code.executableCode.code shouldBe "print('hello')"
        code.thoughtSignature shouldBe "code-signature"
        val result = parts[1].shouldBeInstanceOf<GooglePart.CodeExecutionResult>()
        result.codeExecutionResult.id shouldBe "provider-execution"
        result.codeExecutionResult.output shouldBe "hello\n"
        result.thoughtSignature shouldBe "result-signature"
    }
}
