package ai.koog.prompt.tokenizer

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test for the PromptTokenizer implementations.
 */
class PromptTokenizerTest {

    /**
     * A mock tokenizer that tracks the total tokens counted.
     *
     * This implementation counts tokens by simply counting characters and dividing by 4,
     * which is a very rough approximation but sufficient for testing purposes.
     * It also keeps track of the total tokens counted across all calls.
     */
    class MockTokenizer : Tokenizer {
        private var _totalTokens = 0

        var lastText: String? = null
            private set

        /**
         * The total number of tokens counted across all calls to countTokens.
         */
        val totalTokens: Int
            get() = _totalTokens

        /**
         * Counts tokens by simply counting characters and dividing by 4.
         * Also adds to the running total of tokens counted.
         *
         * @param text The text to tokenize
         * @return The estimated number of tokens in the text
         */
        override fun countTokens(text: String): Int {
            // Simple approximation: 1 token ≈ 4 characters
            println("countTokens: $text")
            lastText = text
            val tokens = (text.length / 4) + 1
            _totalTokens += tokens
            return tokens
        }

        /**
         * Resets the total tokens counter to 0.
         */
        fun reset() {
            _totalTokens = 0
        }
    }

    @Test
    fun testPromptTokenizer() {
        // Create a mock tokenizer to track token usage
        val mockTokenizer = MockTokenizer()

        // Create a prompt tokenizer with our mock tokenizer
        val promptTokenizer = OnDemandTokenizer(mockTokenizer)

        // Create a prompt with some messages
        val testPrompt = prompt("test-prompt") {
            system("You are a helpful assistant.")
            user("What is the capital of France?")
            assistant("Paris is the capital of France.")
        }

        // Count tokens in the prompt
        val totalTokens = promptTokenizer.tokenCountFor(testPrompt)

        // Verify that tokens were counted
        assertTrue(totalTokens > 0, "Total tokens should be greater than 0")

        // Verify that the tokenizer was used and counted tokens
        assertTrue(mockTokenizer.totalTokens > 0, "Tokenizer should have counted tokens")

        // Store the tokens counted for the prompt (before counting individual messages)
        val promptTokenCount = mockTokenizer.totalTokens

        // Print the total tokens spent for the prompt
        println("[DEBUG_LOG] Total tokens spent for prompt: $promptTokenCount")

        // Reset tokenizer to count individual messages separately
        mockTokenizer.reset()

        val requestMetainfo = RequestMetaInfo.create(KoogClock.System)
        val responseMetainfo = ResponseMetaInfo.create(KoogClock.System)
        // Count tokens for individual messages
        val systemTokens = promptTokenizer.tokenCountFor(
            Message.System("You are a helpful assistant.", requestMetainfo)
        )
        val userTokens = promptTokenizer.tokenCountFor(Message.User("What is the capital of France?", requestMetainfo))
        val assistantTokens = promptTokenizer.tokenCountFor(
            Message.Assistant("Paris is the capital of France.", responseMetainfo)
        )

        // Print token counts for each message
        println("[DEBUG_LOG] System message tokens: $systemTokens")
        println("[DEBUG_LOG] User message tokens: $userTokens")
        println("[DEBUG_LOG] Assistant message tokens: $assistantTokens")

        // Verify that individual messages have positive token counts
        assertTrue(systemTokens > 0, "System message should have tokens")
        assertTrue(userTokens > 0, "User message should have tokens")
        assertTrue(assistantTokens > 0, "Assistant message should have tokens")

        // Verify that the total tokens from prompt counting equals what we stored
        assertEquals(totalTokens, promptTokenCount, "Total tokens should match the stored prompt token count")
    }

    @Test
    fun testCachingPromptTokenizer() {
        // Create a mock tokenizer to track token usage
        val mockTokenizer = MockTokenizer()

        // Create a prompt tokenizer with our mock tokenizer
        val promptTokenizer = CachingTokenizer(mockTokenizer)

        // Create a prompt with some messages
        val testPrompt = prompt("test-prompt") {
            system("You are a helpful assistant.")
            user("What is the capital of France?")
            assistant("Paris is the capital of France.")
        }

        assertEquals(0, promptTokenizer.cache.size)
        promptTokenizer.tokenCountFor(testPrompt)
        assertEquals(3, promptTokenizer.cache.size)
        promptTokenizer.clearCache()
        assertEquals(0, promptTokenizer.cache.size)
        promptTokenizer.tokenCountFor(testPrompt.messages[1])
        promptTokenizer.tokenCountFor(testPrompt.messages[2])
        assertEquals(2, promptTokenizer.cache.size)
        promptTokenizer.tokenCountFor(testPrompt)
        assertEquals(3, promptTokenizer.cache.size)
    }

    @Test
    fun testCodeExecutionRetainsCodeOrderedOutputsAndFailureForTokenization() {
        val tokenizer = MockTokenizer()
        val promptTokenizer = OnDemandTokenizer(tokenizer)
        val message = Message.Assistant(
            parts = listOf(
                MessagePart.CodeExecution(
                    id = "ci_123",
                    code = "print('first')",
                    containerId = "cntr_123",
                    outputs = listOf(
                        MessagePart.CodeExecution.Output.Logs("first output"),
                        MessagePart.CodeExecution.Output.Image("https://example.test/second.png"),
                        MessagePart.CodeExecution.Output.Logs("third output"),
                    ),
                    failure = MessagePart.CodeExecution.Failure.INCOMPLETE,
                )
            ),
            metaInfo = ResponseMetaInfo.Empty,
        )

        promptTokenizer.tokenCountFor(message)

        assertEquals(
            """
            print('first')
            first output
            https://example.test/second.png
            third output
            Code execution incomplete
            """.trimIndent(),
            tokenizer.lastText,
        )
    }

    @Test
    fun testGeneratedFilesAndHostedExecutionRetainVisibleTextInPartOrder() {
        val tokenizer = MockTokenizer()
        val promptTokenizer = OnDemandTokenizer(tokenizer)
        val message = Message.Assistant(
            parts = listOf(
                MessagePart.HostedExecution.Request(
                    code = "print('report')",
                    language = "python",
                    executionId = "execution-request",
                    containerId = "container-request",
                    providerItemId = "provider-request",
                ),
                MessagePart.HostedExecution.Progress(
                    message = "Running analysis",
                    sequence = 1,
                    executionId = "execution-progress",
                    containerId = "container-progress",
                    providerItemId = "provider-progress",
                ),
                MessagePart.HostedExecution.CumulativeOutput(
                    output = "first line\nsecond line",
                    sequence = 2,
                    executionId = "execution-output",
                    containerId = "container-output",
                    providerItemId = "provider-output",
                ),
                MessagePart.HostedExecution.Result(
                    output = "Report complete",
                    exitCode = 0,
                    generatedFiles = listOf(
                        MessagePart.GeneratedFile(
                            providerFileId = "provider-file-nested",
                            containerId = "container-file-nested",
                            filename = "report.csv",
                            mediaType = "text/csv",
                            sizeBytes = 4096,
                            producingExecutionId = "execution-result",
                            providerItemId = "provider-file-item-nested",
                        )
                    ),
                    executionId = "execution-result",
                    containerId = "container-result",
                    providerItemId = "provider-result",
                ),
                MessagePart.HostedExecution.Error(
                    message = "Execution timed out",
                    code = "timeout",
                    executionId = "execution-error",
                    containerId = "container-error",
                    providerItemId = "provider-error",
                ),
                MessagePart.GeneratedFile(
                    providerFileId = "provider-file-top-level",
                    containerId = "container-file-top-level",
                    filename = "chart.png",
                    mediaType = "image/png",
                    sizeBytes = 8192,
                    producingExecutionId = "execution-file",
                    providerItemId = "provider-file-item-top-level",
                ),
            ),
            metaInfo = ResponseMetaInfo.Empty,
        )

        promptTokenizer.tokenCountFor(message)

        assertEquals(
            """
            print('report')
            python
            Running analysis
            first line
            second line
            Report complete
            report.csv
            text/csv
            Execution timed out
            timeout
            chart.png
            image/png
            """.trimIndent(),
            tokenizer.lastText,
        )
    }

    @Test
    fun testHostedExecutionOmitsEmptyOptionalTextAndOpaqueIdentifiers() {
        val tokenizer = MockTokenizer()
        val promptTokenizer = OnDemandTokenizer(tokenizer)
        val message = Message.Assistant(
            parts = listOf(
                MessagePart.Text("before"),
                MessagePart.HostedExecution.Progress(
                    message = "",
                    sequence = 1,
                    executionId = "execution-progress",
                    containerId = "container-progress",
                    providerItemId = "provider-progress",
                ),
                MessagePart.HostedExecution.Progress(
                    sequence = 2,
                    executionId = "execution-progress-empty",
                    containerId = "container-progress-empty",
                    providerItemId = "provider-progress-empty",
                ),
                MessagePart.HostedExecution.Result(
                    output = "",
                    exitCode = 0,
                    generatedFiles = listOf(
                        MessagePart.GeneratedFile(
                            providerFileId = "provider-file-nested",
                            containerId = "container-file-nested",
                            filename = "",
                            mediaType = "",
                            sizeBytes = 4096,
                            producingExecutionId = "execution-result",
                            providerItemId = "provider-file-item-nested",
                        )
                    ),
                    executionId = "execution-result",
                    containerId = "container-result",
                    providerItemId = "provider-result",
                ),
                MessagePart.HostedExecution.Result(
                    executionId = "execution-result-empty",
                    containerId = "container-result-empty",
                    providerItemId = "provider-result-empty",
                ),
                MessagePart.HostedExecution.Error(
                    message = "Error without code",
                    executionId = "execution-error",
                    containerId = "container-error",
                    providerItemId = "provider-error",
                ),
                MessagePart.GeneratedFile(
                    providerFileId = "provider-file-top-level",
                    containerId = "container-file-top-level",
                    sizeBytes = 8192,
                    producingExecutionId = "execution-file",
                    providerItemId = "provider-file-item-top-level",
                ),
                MessagePart.Text("after"),
            ),
            metaInfo = ResponseMetaInfo.Empty,
        )

        promptTokenizer.tokenCountFor(message)

        assertEquals("before\nError without code\nafter", tokenizer.lastText)
    }
}
