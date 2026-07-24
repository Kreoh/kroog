package ai.koog.prompt.executor.clients.retry

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.IncompleteStreamException
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlin.jvm.JvmOverloads
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A decorator that adds retry capabilities to any LLMClient implementation.
 *
 * This is a pure decorator - it has no knowledge of specific providers or implementations.
 * It simply wraps any LLMClient and retries operations based on configurable policies.
 *
 * Example usage:
 * ```kotlin
 * val client = AnthropicLLMClient(apiKey)
 * val retryingClient = RetryingLLMClient(client, RetryConfig.CONSERVATIVE)
 * ```
 *
 * @param delegate The LLMClient to wrap with retry logic
 * @param config Configuration for retry behavior
 */
public class RetryingLLMClient private constructor(
    private val delegate: LLMClient,
    internal val config: RetryConfig,
    private val runtime: RetryRuntime,
) : LLMClient() {

    /** Preserves the published retrying-client constructor shape. */
    @JvmOverloads
    public constructor(
        delegate: LLMClient,
        config: RetryConfig = RetryConfig(),
    ) : this(delegate, config, RetryRuntime())

    /** Returns an equivalent client using the supplied runtime hooks. */
    public fun withRuntime(runtime: RetryRuntime): RetryingLLMClient =
        RetryingLLMClient(delegate, config, runtime)

    /** Returns an equivalent client with a redacted retry observer. */
    public fun withObserver(observer: RetryAttemptObserver?): RetryingLLMClient =
        withRuntime(RetryRuntime(observer = observer, jitterSource = runtime.jitterSource))

    /**
     * Retrieves the configured instance of the `LLMProvider` in use.
     *
     * This method returns the `LLMProvider` instance associated with the client,
     * facilitating identification or interaction with the specific provider of
     * large language models (e.g., Google, OpenAI, Meta, etc.).
     *
     * @return the current `LLMProvider` associated with this client.
     */
    override fun llmProvider(): LLMProvider = delegate.llmProvider()

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant = withRetry(RetryOperation.EXECUTE) {
        delegate.execute(prompt, model, tools)
    }

    // Streaming retry: Only retries connection failures before the first token is received.
    // Once streaming starts, errors are passed through to avoid content duplication.
    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> =
        flow {
            repeat(config.maxAttempts) { attempt ->
                var firstFrameReceived = false
                try {
                    delegate.executeStreaming(prompt, model, tools).collect { chunk ->
                        firstFrameReceived = true
                        emit(chunk)
                    }
                    return@flow
                } catch (e: CancellationException) {
                    throw e // Never retry cancellations
                } catch (e: Throwable) {
                    e.cancellationCause()?.let { throw it }
                    // If we already received tokens, don't retry - pass error through
                    if (firstFrameReceived) {
                        throw e
                    }

                    val decision = classifyRetry(e)
                    if (decision == null || attempt >= config.maxAttempts - 1) {
                        throw e
                    }

                    val delay = calculateDelay(attempt, e)
                    observeRetry(RetryOperation.EXECUTE_STREAMING, attempt, delay, decision)
                    delay(delay)
                }
            }
        }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): LLMChoice = withRetry(RetryOperation.EXECUTE_MULTIPLE_CHOICES) {
        delegate.executeMultipleChoices(prompt, model, tools)
    }

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel
    ): ModerationResult = withRetry(RetryOperation.MODERATE) {
        delegate.moderate(prompt, model)
    }

    override suspend fun models(): List<LLModel> = withRetry(RetryOperation.MODELS) {
        delegate.models()
    }

    /**
     * Embeds the given text, retrying on transient failures according to [config].
     *
     * @param text The text to embed.
     * @param model The model to use for embedding.
     * @return A list of floating-point values representing the embedding vector.
     */
    override suspend fun embed(
        text: String,
        model: LLModel
    ): List<Double> = withRetry(RetryOperation.EMBED) {
        delegate.embed(text, model)
    }

    /**
     * Embeds the given inputs, retrying on transient failures according to [config].
     *
     * @param inputs The list of texts to embed.
     * @param model The model to use for embedding.
     * @return A list of embedding vectors, one per input string.
     */
    override suspend fun embed(
        inputs: List<String>,
        model: LLModel
    ): List<List<Double>> = withRetry(RetryOperation.EMBED_BATCH) {
        delegate.embed(inputs, model)
    }

    private suspend fun <T> withRetry(
        operation: RetryOperation,
        block: suspend () -> T
    ): T {
        var lastException: Throwable? = null

        repeat(config.maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e // Never retry cancellations
            } catch (e: Throwable) {
                e.cancellationCause()?.let { throw it }
                lastException = e

                val decision = classifyRetry(e)
                if (decision == null || attempt >= config.maxAttempts - 1) {
                    throw e
                }

                val delay = calculateDelay(attempt, e)
                observeRetry(operation, attempt, delay, decision)
                delay(delay)
            }
        }

        throw lastException!!
    }

    private fun classifyRetry(error: Throwable): RetryDecision? {
        val causes = error.causes()
        if (causes.any { it.isNonRetryableProtocolFailure() }) {
            return null
        }

        causes.firstOrNull { it is IncompleteStreamException }?.let {
            return RetryDecision(
                RetryFailureClassification.INCOMPLETE_STREAM,
                RetryFailureReason.INCOMPLETE_STREAM,
            )
        }

        causes.filterIsInstance<KoogHttpClientException>()
            .firstOrNull { it.statusCode != null }
            ?.let { httpFailure ->
                return when (val status = httpFailure.statusCode) {
                    408 -> RetryDecision(RetryFailureClassification.HTTP_STATUS, RetryFailureReason.HTTP_408)
                    409 -> RetryDecision(RetryFailureClassification.HTTP_STATUS, RetryFailureReason.HTTP_409)
                    429 -> RetryDecision(RetryFailureClassification.HTTP_STATUS, RetryFailureReason.HTTP_429)
                    in 500..599 ->
                        RetryDecision(RetryFailureClassification.HTTP_STATUS, RetryFailureReason.HTTP_5XX)
                    else -> null
                }
            }

        if (causes.any { it.isRecognisedTransientTransport() }) {
            return RetryDecision(
                RetryFailureClassification.TRANSIENT_TRANSPORT,
                RetryFailureReason.TRANSIENT_CONNECTIVITY,
            )
        }

        if (causes.any { it is KoogHttpClientException }) {
            return null
        }

        val message = error.message ?: return null
        return if (config.retryablePatterns.any { pattern -> pattern.matches(message) }) {
            RetryDecision(
                RetryFailureClassification.CONFIGURED_PATTERN,
                RetryFailureReason.CONFIGURED_PATTERN,
            )
        } else {
            null
        }
    }

    private fun observeRetry(
        operation: RetryOperation,
        attempt: Int,
        retryDelay: Duration,
        decision: RetryDecision,
    ) {
        val event = RetryAttempt(
            operation = operation,
            attempt = attempt + 1,
            maxAttempts = config.maxAttempts,
            delay = retryDelay,
            classification = decision.classification,
            reason = decision.reason,
        )
        try {
            runtime.observer?.onRetry(event)
        } catch (_: Throwable) {
            // Observability must never change retry control flow.
        }
        logger.warn {
            "Retry scheduled: operation=${event.operation}, attempt=${event.attempt}/${event.maxAttempts}, " +
                "delayMs=${event.delay.inWholeMilliseconds}, classification=${event.classification}, " +
                "reason=${event.reason}"
        }
    }

    private fun calculateDelay(attempt: Int, error: Throwable? = null): Duration {
        // Check for retry-after hint in error message
        error?.message?.let { message ->
            config.retryAfterExtractor?.extract(message)?.let { retryAfter ->
                return minOf(retryAfter, config.maxDelay)
            }
        }

        // Exponential backoff with jitter
        var exponentialMs = config.initialDelay.inWholeMilliseconds.toDouble()
        repeat(attempt) {
            exponentialMs *= config.backoffMultiplier
        }
        val boundedMs = minOf(exponentialMs, config.maxDelay.inWholeMilliseconds.toDouble())

        // Add jitter (only increases delay, never decreases)
        val jitterSample = runtime.jitterSource.nextDouble()
        require(jitterSample in 0.0..1.0 && jitterSample.isFinite()) {
            "Retry jitter source must return a finite value between 0.0 and 1.0"
        }
        val jitterUpperBound = boundedMs * config.jitterFactor
        val jitterMs = if (jitterUpperBound > 0.0) {
            jitterUpperBound * jitterSample
        } else {
            0.0
        }
        val finalMs = minOf(
            (boundedMs + jitterMs).toLong(),
            config.maxDelay.inWholeMilliseconds,
        )

        return finalMs.milliseconds
    }

    private data class RetryDecision(
        val classification: RetryFailureClassification,
        val reason: RetryFailureReason,
    )

    private fun Throwable.causes(): List<Throwable> {
        val result = mutableListOf<Throwable>()
        var current: Throwable? = this
        while (current != null && result.size < MAX_CAUSE_DEPTH && result.none { it === current }) {
            result += current
            current = current.cause
        }
        return result
    }

    private fun Throwable.cancellationCause(): CancellationException? =
        causes().filterIsInstance<CancellationException>().firstOrNull()

    private fun Throwable.isRecognisedTransientTransport(): Boolean =
        this is ConnectTimeoutException ||
            this is HttpRequestTimeoutException ||
            this is IOException

    private fun Throwable.isNonRetryableProtocolFailure(): Boolean =
        this is SerializationException ||
            this is IllegalArgumentException ||
            this::class.simpleName in NON_RETRYABLE_PROTOCOL_TYPES

    private companion object {
        private val logger = KotlinLogging.logger { }
        private const val MAX_CAUSE_DEPTH = 32

        private val NON_RETRYABLE_PROTOCOL_TYPES = setOf(
            "JsonDecodingException",
            "MissingFieldException",
            "ProtocolException",
        )
    }

    override fun close() {
        delegate.close()
    }

    override fun getStandardJsonSchemaGenerator(): StandardJsonSchemaGenerator {
        return delegate.getStandardJsonSchemaGenerator()
    }

    override fun getBasicJsonSchemaGenerator(): BasicJsonSchemaGenerator {
        return delegate.getBasicJsonSchemaGenerator()
    }
}

/**
 * Converts an instance of [LLMClient] into a retrying client with customizable retry behavior.
 *
 * @param retryConfig Configuration for retry behavior. Defaults to [RetryConfig.DEFAULT].
 * @return A new instance of [RetryingLLMClient] that adds retry logic to the provided client.
 */
public fun LLMClient.toRetryingClient(
    retryConfig: RetryConfig = RetryConfig.DEFAULT
): RetryingLLMClient =
    RetryingLLMClient(
        delegate = this,
        config = retryConfig
    )
