package ai.koog.prompt.executor.clients.bedrock.converse

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.bedrock.BedrockModelFamilies
import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import ai.koog.prompt.streaming.toMessageResponse
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockDelta
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamOutput
import aws.sdk.kotlin.services.bedrockruntime.model.ReasoningContentBlockDelta
import aws.smithy.kotlin.runtime.retries.StandardRetryStrategy
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Live regression for signatures split across Bedrock Converse reasoning deltas. */
@EnabledIfEnvironmentVariable(named = "KROOG_BEDROCK_REASONING_LIVE", matches = "1")
class BedrockConverseReasoningLiveTest {
    @Test
    fun integration_testStreamedOpusReasoningCompletes() = runBlocking {
        val model = BedrockModels.AnthropicClaude55Opus.copy(
            id = System.getenv("BEDROCK_OPUS_MODEL_ID") ?: BedrockModels.AnthropicClaude55Opus.id,
        )
        val params = BedrockConverseParams(maxTokens = 16384).withThinking(
            BedrockThinkingConfig.Adaptive(BedrockReasoningEffort.XHIGH, BedrockThinkingDisplay.SUMMARIZED),
        )
        val request = BedrockConverseConverters.createConverseStreamRequest(
            prompt("bedrock-streamed-reasoning", params) {
                user(
                    System.getenv("KROOG_BEDROCK_REASONING_PROMPT") ?: (
                        "Solve this Sudoku without code or external tools. Rows are separated by semicolons " +
                            "and 0 denotes an empty cell: 100007090;030020008;009600500;005300900;010080002;" +
                            "600004000;300000010;040000007;007000300. Work through the constraints carefully, " +
                            "tracking candidates and any necessary branches. Check all 27 row, column and box " +
                            "constraints and the original clues independently before giving the solved grid. " +
                            "Explain the difficult deductions and prove whether the solution is unique."
                        ),
                )
            },
            model,
            emptyList(),
            fallbackModelFamily = BedrockModelFamilies.AnthropicClaude,
        )
        val lengths = mutableMapOf<Int, MutableList<Int>>()
        val previous = mutableMapOf<Int, String>()
        val started = System.nanoTime()
        BedrockRuntimeClient {
            region = requireNotNull(System.getenv("AWS_REGION")) { "AWS_REGION is required" }
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = requireNotNull(System.getenv("AWS_ACCESS_KEY_ID"))
                secretAccessKey = requireNotNull(System.getenv("AWS_SECRET_ACCESS_KEY"))
                sessionToken = System.getenv("AWS_SESSION_TOKEN")
            }
            retryStrategy = StandardRetryStrategy { maxAttempts = 1 }
            callTimeout = 5.minutes
            httpClient {
                connectTimeout = 10.seconds
                socketReadTimeout = 5.minutes
                socketWriteTimeout = 30.seconds
            }
        }.use { client ->
            try {
                withTimeout(5.minutes) {
                    client.converseStream(request) { response ->
                        val observed = requireNotNull(response.stream).onEach { event ->
                            val delta = (event as? ConverseStreamOutput.ContentBlockDelta)?.value
                            val reasoning = (delta?.delta as? ContentBlockDelta.ReasoningContent)?.value
                            if (reasoning is ReasoningContentBlockDelta.Signature) {
                                val index = delta.contentBlockIndex
                                val last = previous.put(index, reasoning.value)
                                lengths.getOrPut(index) { mutableListOf() }.add(reasoning.value.length)
                                println(
                                    "SIGNATURE block=$index chunk=${lengths.getValue(index).size} " +
                                        "length=${reasoning.value.length} equalPrevious=${last == reasoning.value} " +
                                        "extendsPrevious=${last != null && reasoning.value.startsWith(last)}",
                                )
                            }
                        }
                        val frames = BedrockConverseConverters.transformConverseStreamChunks(observed).toList()
                        frames.toMessageResponse()
                        assertTrue(lengths.isNotEmpty(), "No signed reasoning was exercised by this live request")
                        println("COMPLETED frames=${frames.size}")
                    }
                }
            } finally {
                // Only structural diagnostics: never log signatures, reasoning text or request credentials.
                println("SIGNATURE_LENGTHS $lengths elapsedSeconds=${(System.nanoTime() - started) / 1_000_000_000}")
            }
        }
    }
}
