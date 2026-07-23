package ai.koog.prompt.executor.clients.google

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.google.models.GooglePart
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class GeminiReplayTest {
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
