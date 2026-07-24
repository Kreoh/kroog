package ai.koog.prompt.executor.clients.retry

import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for retry behavior in LLM client operations.
 *
 * @property maxAttempts Maximum number of attempts (including initial)
 * @property initialDelay Initial delay before first retry
 * @property maxDelay Maximum delay between retries
 * @property backoffMultiplier Multiplier for exponential backoff
 * @property jitterFactor Random jitter factor (0.0 to 1.0)
 * @property retryablePatterns Caller-provided legacy patterns used to classify exception messages. Empty by default.
 * @property retryAfterExtractor Optional extractor for retry-after hints
 */
public data class RetryConfig @JvmOverloads constructor(
    val maxAttempts: Int = 3,
    val initialDelay: Duration = 1.seconds,
    val maxDelay: Duration = 30.seconds,
    val backoffMultiplier: Double = 2.0,
    val jitterFactor: Double = 0.1,
    val retryablePatterns: List<RetryablePattern> = emptyList(),
    val retryAfterExtractor: RetryAfterExtractor? = DefaultRetryAfterExtractor,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be at least 1.0" }
        require(jitterFactor in 0.0..1.0) { "jitterFactor must be between 0.0 and 1.0" }
        require(initialDelay <= maxDelay) {
            "initialDelay ($initialDelay) must not be greater than maxDelay ($maxDelay)"
        }
    }

    /**
     * Companion object for providing predefined retry configurations and patterns.
     * Contains default retry logic settings that can be used across different use cases,
     * along with scoped configurations for conservative, aggressive, and production environments.
     */
    public companion object {
        /**
         * Legacy message patterns available for explicit caller opt-in.
         *
         * These patterns inspect unstructured exception messages and are therefore excluded from the default
         * configuration. Prefer typed HTTP or transport failures where possible.
         */
        @JvmField
        public val DEFAULT_PATTERNS: List<RetryablePattern> = listOf(
            // HTTP status codes
            RetryablePattern.Status(429), // Rate limit
            RetryablePattern.Status(500), // Internal server error
            RetryablePattern.Status(502), // Bad gateway
            RetryablePattern.Status(503), // Service unavailable
            RetryablePattern.Status(504), // Gateway timeout
            RetryablePattern.Status(529), // Anthropic overloaded

            // Error keywords
            RetryablePattern.Keyword("rate limit"),
            RetryablePattern.Keyword("too many requests"),
            RetryablePattern.Keyword("overloaded"),
            RetryablePattern.Keyword("request timeout"),
            RetryablePattern.Keyword("connection timeout"),
            RetryablePattern.Keyword("read timeout"),
            RetryablePattern.Keyword("write timeout"),
            RetryablePattern.Keyword("connection reset by peer"),
            RetryablePattern.Keyword("connection refused"),
            RetryablePattern.Keyword("temporarily unavailable"),
            RetryablePattern.Keyword("service unavailable")
        )

        /**
         * Conservative configuration - fewer retries, longer delays.
         */
        @JvmField
        public val CONSERVATIVE: RetryConfig = RetryConfig(
            maxAttempts = 3,
            initialDelay = 2.seconds,
            maxDelay = 30.seconds
        )

        /**
         * Aggressive configuration - more retries, shorter delays.
         */
        @JvmField
        public val AGGRESSIVE: RetryConfig = RetryConfig(
            maxAttempts = 5,
            initialDelay = 500.milliseconds,
            maxDelay = 20.seconds,
            backoffMultiplier = 1.5
        )

        /**
         * Production configuration - balanced for production use.
         */
        @JvmField
        public val PRODUCTION: RetryConfig = RetryConfig(
            maxAttempts = 3,
            initialDelay = 1.seconds,
            maxDelay = 20.seconds,
            backoffMultiplier = 2.0,
            jitterFactor = 0.2
        )

        /**
         * No retry - effectively disables retry logic.
         */
        @JvmField
        public val DISABLED: RetryConfig = RetryConfig(maxAttempts = 1)

        /**
         * The default retry configuration used by clients implementing retry logic.
         *
         * Suitable for general-purpose use cases where standard retry behavior is required.
         */
        @JvmField
        public val DEFAULT: RetryConfig = RetryConfig()
    }
}

/** Closed operation names exposed to a retry-attempt observer. */
public enum class RetryOperation {
    EXECUTE,
    EXECUTE_STREAMING,
    EXECUTE_MULTIPLE_CHOICES,
    MODERATE,
    MODELS,
    EMBED,
    EMBED_BATCH,
}

/** High-level retry failure category, free of provider payloads and exception text. */
public enum class RetryFailureClassification {
    HTTP_STATUS,
    TRANSIENT_TRANSPORT,
    INCOMPLETE_STREAM,
    CONFIGURED_PATTERN,
}

/** Closed reason for scheduling another attempt. */
public enum class RetryFailureReason {
    HTTP_408,
    HTTP_409,
    HTTP_429,
    HTTP_5XX,
    TRANSIENT_CONNECTIVITY,
    INCOMPLETE_STREAM,
    CONFIGURED_PATTERN,
}

/** Redacted information supplied immediately before a retry delay. */
public data class RetryAttempt(
    public val operation: RetryOperation,
    public val attempt: Int,
    public val maxAttempts: Int,
    public val delay: Duration,
    public val classification: RetryFailureClassification,
    public val reason: RetryFailureReason,
)

/** Receives redacted retry scheduling information. Observer failures are ignored. */
public fun interface RetryAttemptObserver {
    public fun onRetry(attempt: RetryAttempt)
}

/** Supplies a normalised jitter sample in the inclusive range from zero to one. */
public fun interface RetryJitterSource {
    public fun nextDouble(): Double
}

/**
 * Runtime retry hooks kept separate from [RetryConfig] so configuration retains its published data-class ABI.
 *
 * [jitterSource] is evaluated once per scheduled retry. Values outside zero to one are rejected before a delay is
 * calculated. Observer failures are ignored and cannot alter retry or cancellation control flow.
 */
public class RetryRuntime @JvmOverloads constructor(
    public val observer: RetryAttemptObserver? = null,
    public val jitterSource: RetryJitterSource = RetryJitterSource { Random.nextDouble() },
)

/**
 * Pattern for identifying retryable errors.
 */
public sealed class RetryablePattern {
    /**
     * Evaluates whether the given message matches the criteria defined by the implementing class.
     *
     * @param message The message to evaluate against the matching criteria.
     * @return `true` if the message matches the criteria, otherwise `false`.
     */
    public abstract fun matches(message: String): Boolean

    /**
     * Matches HTTP status codes in error messages.
     */
    public data class Status(val code: Int) : RetryablePattern() {
        private val patterns = listOf(
            Regex("\\b$code\\b"),
            Regex("status:?\\s*$code"),
            Regex("error:?\\s*$code", RegexOption.IGNORE_CASE)
        )

        override fun matches(message: String): Boolean =
            patterns.any { it.containsMatchIn(message) }
    }

    /**
     * Matches keywords in error messages.
     */
    public data class Keyword(val keyword: String) : RetryablePattern() {
        override fun matches(message: String): Boolean =
            keyword.lowercase() in message.lowercase()
    }

    /**
     * Matches using a custom regex.
     */
    public data class Regex(val pattern: kotlin.text.Regex) : RetryablePattern() {
        override fun matches(message: String): Boolean =
            pattern.containsMatchIn(message)
    }

    /**
     * Custom matching logic.
     */
    public class Custom(private val matcher: (String) -> Boolean) : RetryablePattern() {
        override fun matches(message: String): Boolean = matcher(message)
    }
}

/**
 * Extracts retry-after hints from error messages.
 */
public fun interface RetryAfterExtractor {
    /**
     * Extracts a retry-after duration from the provided error message.
     *
     * @param message The error message from which to extract the retry-after duration.
     * @return The extracted retry-after duration, or null if no valid duration could be determined.
     */
    public fun extract(message: String): Duration?
}

/**
 * Default implementation that extracts common retry-after patterns.
 */
public object DefaultRetryAfterExtractor : RetryAfterExtractor {
    private val patterns = listOf(
        Regex("retry\\s+after\\s+(\\d+)\\s+second", RegexOption.IGNORE_CASE),
        Regex("retry-after:\\s*(\\d+)", RegexOption.IGNORE_CASE),
        Regex("wait\\s+(\\d+)\\s+second", RegexOption.IGNORE_CASE),
        Regex("try again in\\s+(\\d+)(\\.\\d{1,3})?s", RegexOption.IGNORE_CASE)
    )

    override fun extract(message: String): Duration? {
        for (pattern in patterns) {
            pattern.find(message)?.let { match ->
                match.groupValues.getOrNull(1)?.toLongOrNull()?.let { seconds ->
                    return seconds.seconds
                }
            }
        }
        return null
    }
}
