package ai.koog.prompt.executor.clients.retry

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.IncompleteStreamException
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.emitTextDelta
import ai.koog.prompt.streaming.streamFrameFlow
import ai.koog.prompt.streaming.streamFrameFlowOf
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import ai.koog.utils.time.KoogClock
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class RetryingLLMClientTest {

    private val testModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(LLMCapability.Completion),
        contextLength = 4096
    )

    private val testPrompt = prompt("test-prompt") {
        system("Test system message")
        user("Test user message")
    }

    private val testMetaInfo = ResponseMetaInfo.create(KoogClock.System)

    private val testResponse = Message.Assistant(
        content = "Test response",
        metaInfo = testMetaInfo
    )

    @Test
    fun testSucceedOnFirstAttempt() = runTest {
        val mockClient = MockLLMClient(
            executeResponse = testResponse
        )

        val retryingClient = RetryingLLMClient(mockClient)

        val result = retryingClient.execute(testPrompt, testModel, emptyList())

        assertEquals(testResponse, result)
        assertEquals(1, mockClient.executeCalls)
    }

    @Test
    fun testLegacyRetryConfigAndClientSourceShapesRemainAvailable() {
        val configConstructor:
            (Int, Duration, Duration, Double, Double, List<RetryablePattern>, RetryAfterExtractor?) -> RetryConfig =
            ::RetryConfig
        val oneArgumentClient: (LLMClient) -> RetryingLLMClient = ::RetryingLLMClient
        val twoArgumentClient: (LLMClient, RetryConfig) -> RetryingLLMClient = ::RetryingLLMClient
        val config = configConstructor(
            3,
            0.milliseconds,
            10.milliseconds,
            2.0,
            0.0,
            emptyList(),
            null,
        )

        assertEquals(4, config.copy(maxAttempts = 4).maxAttempts)
        assertSame(config, twoArgumentClient(MockLLMClient(), config).config)
        assertEquals(3, oneArgumentClient(MockLLMClient()).config.maxAttempts)
    }

    @Test
    fun testConvertLLMClientToRetryingClientWithDefaultConfig() = runTest {
        val mockClient = MockLLMClient(
            executeResponse = testResponse
        )
        // when
        val retryingClient = mockClient.toRetryingClient()

        // then
        assertSame(actual = retryingClient.config, expected = RetryConfig.DEFAULT)
    }

    @Test
    fun testConvertLLMClientToRetryingClientWithCustomConfig() = runTest {
        // given
        val mockClient = MockLLMClient(
            executeResponse = testResponse
        )
        val retryConfig = RetryConfig(maxAttempts = 100500)
        // when
        val retryingClient = mockClient.toRetryingClient(retryConfig)

        // then
        assertSame(actual = retryingClient.config, expected = retryConfig)
    }

    @Test
    fun testRetryOnRateLimitError() = runTest {
        val mockClient = MockLLMClient(
            executeResponse = testResponse,
            failuresBeforeSuccess = 2,
            failureMessage = "Error from API: 429 Too Many Requests"
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(
                maxAttempts = 3,
                initialDelay = 10.milliseconds, // Fast for testing
                retryablePatterns = RetryConfig.DEFAULT_PATTERNS,
            )
        )

        val result = retryingClient.execute(testPrompt, testModel, emptyList())

        assertEquals(testResponse, result)
        assertEquals(3, mockClient.executeCalls) // 2 failures + 1 success
    }

    @Test
    fun testRetryOnServiceUnavailable() = runTest {
        val mockClient = MockLLMClient(
            executeResponse = testResponse,
            failuresBeforeSuccess = 1,
            failureMessage = "Error: 503 Service Unavailable"
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(
                maxAttempts = 2,
                initialDelay = 10.milliseconds,
                retryablePatterns = RetryConfig.DEFAULT_PATTERNS,
            )
        )

        val result = retryingClient.execute(testPrompt, testModel, emptyList())

        assertEquals(testResponse, result)
        assertEquals(2, mockClient.executeCalls)
    }

    @Test
    fun testRetryOnTimeout() = runTest {
        val mockClient = MockLLMClient(
            executeResponse = testResponse,
            failuresBeforeSuccess = 1,
            failureMessage = "Connection timeout"
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(
                maxAttempts = 2,
                initialDelay = 10.milliseconds,
                retryablePatterns = RetryConfig.DEFAULT_PATTERNS,
            )
        )

        val result = retryingClient.execute(testPrompt, testModel, emptyList())

        assertEquals(testResponse, result)
        assertEquals(2, mockClient.executeCalls)
    }

    @Test
    fun testNoRetryOnClientError() = runTest {
        val mockClient = MockLLMClient(
            executeResponse = testResponse,
            failuresBeforeSuccess = 1,
            failureMessage = "Error: 400 Bad Request"
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(maxAttempts = 3)
        )

        assertFailsWith<RuntimeException> {
            retryingClient.execute(testPrompt, testModel, emptyList())
        }

        assertEquals(1, mockClient.executeCalls) // No retry
    }

    @Test
    fun testThrowAfterMaxAttempts() = runTest {
        val mockClient = MockLLMClient(
            executeResponse = testResponse,
            failuresBeforeSuccess = 5, // Will always fail
            failureMessage = "Error: 503"
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(
                maxAttempts = 3,
                initialDelay = 10.milliseconds,
                retryablePatterns = RetryConfig.DEFAULT_PATTERNS,
            )
        )

        val exception = assertFailsWith<RuntimeException> {
            retryingClient.execute(testPrompt, testModel, emptyList())
        }

        assertEquals(exception.message?.contains("503"), true)
        assertEquals(3, mockClient.executeCalls)
    }

    @Test
    fun testCustomRetryPatterns() = runTest {
        val mockClient = MockLLMClient(
            executeResponse = testResponse,
            failuresBeforeSuccess = 1,
            failureMessage = "CUSTOM_ERROR_123"
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(
                maxAttempts = 2,
                initialDelay = 10.milliseconds,
                retryablePatterns = listOf(
                    RetryablePattern.Regex(Regex("CUSTOM_ERROR_\\d+"))
                )
            )
        )

        val result = retryingClient.execute(testPrompt, testModel, emptyList())

        assertEquals(testResponse, result)
        assertEquals(2, mockClient.executeCalls)
    }

    @Test
    fun testNoRetryOnCancellation() = runTest {
        val mockClient = MockLLMClient(
            executeResponse = testResponse,
            throwCancellation = true
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(maxAttempts = 3)
        )

        assertFailsWith<CancellationException> {
            retryingClient.execute(testPrompt, testModel, emptyList())
        }

        assertEquals(1, mockClient.executeCalls) // No retry on cancellation
    }

    @Test
    fun testDisabledRetryConfig() = runTest {
        val mockClient = MockLLMClient(
            executeResponse = testResponse,
            failuresBeforeSuccess = 1,
            failureMessage = "Error: 503"
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig.DISABLED
        )

        assertFailsWith<RuntimeException> {
            retryingClient.execute(testPrompt, testModel, emptyList())
        }

        assertEquals(1, mockClient.executeCalls) // No retry with DISABLED config
    }

    @Test
    fun testStreamingSucceedOnFirstAttempt() = runTest {
        val mockClient = MockLLMClient(
            streamResponse = streamFrameFlowOf("chunk1", "chunk2"),
            streamFailuresBeforeSuccess = 0
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(
                maxAttempts = 2
            )
        )

        val result = retryingClient.executeStreaming(testPrompt, testModel).toList()

        assertEquals(listOf("chunk1", "chunk2").map(StreamFrame::TextDelta), result)
        assertEquals(1, mockClient.streamCalls)
    }

    @Test
    fun testStreamingWithRetry() = runTest {
        val mockClient = MockLLMClient(
            streamResponse = streamFrameFlowOf("chunk1", "chunk2"),
            streamFailuresBeforeSuccess = 1,
            failureMessage = "Error: 503"
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(
                maxAttempts = 2,
                initialDelay = 10.milliseconds,
                retryablePatterns = RetryConfig.DEFAULT_PATTERNS,
            )
        )

        val result = retryingClient.executeStreaming(testPrompt, testModel).toList()

        assertEquals(listOf("chunk1", "chunk2").map(StreamFrame::TextDelta), result)
        assertEquals(2, mockClient.streamCalls)
    }

    @Test
    fun testStreamingNoRetryAfterFirstToken() = runTest {
        // Mock that emits one token then fails
        val mockClient = MockLLMClient(
            streamResponse = streamFrameFlow {
                emitTextDelta("first-token")
                throw RuntimeException("Connection lost after first token")
            }
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(
                maxAttempts = 3,
            )
        )

        // Should not retry because we already received a token
        val exception = assertFailsWith<RuntimeException> {
            retryingClient.executeStreaming(testPrompt, testModel).collect()
        }

        assertEquals("Connection lost after first token", exception.message)
        assertEquals(1, mockClient.streamCalls) // No retry
    }

    @Test
    fun testRetryMultipleChoices() = runTest {
        val choices = listOf(
            Message.Assistant("Choice 1", testMetaInfo),
            Message.Assistant("Choice 2", testMetaInfo)
        )

        val mockClient = MockLLMClient(
            multipleChoicesResponse = choices,
            failuresBeforeSuccess = 1,
            failureMessage = "Error: 429"
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(
                maxAttempts = 2,
                initialDelay = 10.milliseconds,
                retryablePatterns = RetryConfig.DEFAULT_PATTERNS,
            )
        )

        val result = retryingClient.executeMultipleChoices(testPrompt, testModel, emptyList())

        assertEquals(choices, result)
        assertEquals(2, mockClient.multipleChoicesCalls)
    }

    @Test
    fun testRetryModerate() = runTest {
        val moderationResult = ModerationResult(
            isHarmful = false,
            categories = emptyMap()
        )

        val mockClient = MockLLMClient(
            moderateResponse = moderationResult,
            failuresBeforeSuccess = 1,
            failureMessage = "Error: 503"
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(
                maxAttempts = 2,
                initialDelay = 10.milliseconds,
                retryablePatterns = RetryConfig.DEFAULT_PATTERNS,
            )
        )

        val result = retryingClient.moderate(testPrompt, testModel)

        assertEquals(moderationResult, result)
        assertEquals(2, mockClient.moderateCalls)
    }

    @Test
    fun testRetryEmbed() = runTest {
        val expectedEmbedding = listOf(0.1, 0.2, 0.3)

        val mockClient = MockLLMClient(
            embedResponse = expectedEmbedding,
            failuresBeforeSuccess = 1,
            failureMessage = "Error: 503"
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(
                maxAttempts = 2,
                initialDelay = 10.milliseconds,
                retryablePatterns = RetryConfig.DEFAULT_PATTERNS,
            )
        )

        val result = retryingClient.embed("hello", testModel)

        assertEquals(expectedEmbedding, result)
        assertEquals(2, mockClient.embedCalls)
    }

    @Test
    fun testRetryBatchEmbed() = runTest {
        val expectedEmbeddings = listOf(listOf(0.1, 0.2), listOf(0.3, 0.4))

        val mockClient = MockLLMClient(
            batchEmbedResponse = expectedEmbeddings,
            failuresBeforeSuccess = 1,
            failureMessage = "Error: 429"
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(
                maxAttempts = 2,
                initialDelay = 10.milliseconds,
                retryablePatterns = RetryConfig.DEFAULT_PATTERNS,
            )
        )

        val result = retryingClient.embed(listOf("hello", "world"), testModel)

        assertEquals(expectedEmbeddings, result)
        assertEquals(2, mockClient.batchEmbedCalls)
    }

    @Test
    fun testEmbedNoRetryOnUnsupportedOperation() = runTest {
        val mockClient = MockLLMClient(
            failuresBeforeSuccess = 1,
            failureMessage = "Error: 400 Bad Request"
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(maxAttempts = 3)
        )

        assertFailsWith<RuntimeException> {
            retryingClient.embed("hello", testModel)
        }

        assertEquals(1, mockClient.embedCalls) // No retry on non-retryable error
    }

    @Test
    fun testIncompleteStreamExceptionBeforeFirstFrameTriggersRetry() = runTest {
        var callCount = 0
        val mockClient = MockLLMClient(
            streamResponse = flow {
                callCount++
                if (callCount == 1) {
                    throw IncompleteStreamException()
                }
                emit(StreamFrame.TextDelta("success"))
                emit(StreamFrame.End(finishReason = "stop"))
            }
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(
                maxAttempts = 3,
                initialDelay = 10.milliseconds
            )
        )

        val result = retryingClient.executeStreaming(testPrompt, testModel).toList()

        assertEquals(
            listOf(StreamFrame.TextDelta("success"), StreamFrame.End(finishReason = "stop")),
            result
        )
        assertEquals(2, mockClient.streamCalls)
    }

    @Test
    fun testIncompleteStreamExceptionAfterFirstFramePropagates() = runTest {
        val mockClient = MockLLMClient(
            streamResponse = streamFrameFlow {
                emitTextDelta("first-token")
                throw IncompleteStreamException()
            }
        )

        val retryingClient = RetryingLLMClient(
            mockClient,
            RetryConfig(
                maxAttempts = 3,
                initialDelay = 10.milliseconds
            )
        )

        assertFailsWith<IncompleteStreamException> {
            retryingClient.executeStreaming(testPrompt, testModel).collect()
        }

        assertEquals(1, mockClient.streamCalls) // No retry after first frame
    }

    @Test
    fun testBasicJsonSchemaGeneratorDelegation() = runTest {
        val mockClient = MockLLMClient()

        val retryingClient = RetryingLLMClient(mockClient)

        val result = retryingClient.getBasicJsonSchemaGenerator()

        assertEquals(mockClient.basicJsonSchemaGeneratorDefault, result)
    }

    @Test
    fun testStandardJsonSchemaGeneratorDelegation() = runTest {
        val mockClient = MockLLMClient()

        val retryingClient = RetryingLLMClient(mockClient)

        val result = retryingClient.getStandardJsonSchemaGenerator()

        assertEquals(mockClient.standardJsonSchemaGeneratorDefault, result)
    }

    @Test
    fun testStructuredHttpStatusesRetryThroughWrappedCauseChain() = runTest {
        listOf(408, 409, 429, 500, 599).forEach { status ->
            val mockClient = MockLLMClient(
                executeResponse = testResponse,
                failuresBeforeSuccess = 1,
                failureFactory = {
                    LLMClientException(
                        "test",
                        "redacted wrapper",
                        KoogHttpClientException(statusCode = status, errorBody = "provider-sentinel"),
                    )
                },
            )
            val retryingClient = RetryingLLMClient(
                mockClient,
                RetryConfig(maxAttempts = 2, initialDelay = 0.milliseconds, jitterFactor = 0.0),
            )

            assertEquals(testResponse, retryingClient.execute(testPrompt, testModel, emptyList()))
            assertEquals(2, mockClient.executeCalls)
        }
    }

    @Test
    fun testStructuredNonRetryableStatusCannotBeOverriddenByDeceptiveMessage() = runTest {
        val mockClient = MockLLMClient(
            failuresBeforeSuccess = 1,
            failureFactory = {
                KoogHttpClientException(
                    statusCode = 400,
                    message = "deceptive 503 provider-sentinel",
                )
            },
        )

        assertFailsWith<KoogHttpClientException> {
            RetryingLLMClient(
                mockClient,
                RetryConfig(maxAttempts = 3, initialDelay = 0.milliseconds),
            ).execute(testPrompt, testModel, emptyList())
        }
        assertEquals(1, mockClient.executeCalls)
    }

    @Test
    fun testOnlyRecognisedStatuslessTransportCauseRetries() = runTest {
        val transportClient = MockLLMClient(
            executeResponse = testResponse,
            failuresBeforeSuccess = 1,
            failureFactory = {
                KoogHttpClientException(
                    message = "transport-provider-sentinel",
                    cause = IOException(),
                )
            },
        )
        val retrying = RetryingLLMClient(
            transportClient,
            RetryConfig(maxAttempts = 2, initialDelay = 0.milliseconds),
        )

        assertEquals(testResponse, retrying.execute(testPrompt, testModel, emptyList()))
        assertEquals(2, transportClient.executeCalls)

        listOf(
            SerializationException("malformed-json-sentinel 503"),
            ProtocolException(),
        ).forEach { cause ->
            val malformedClient = MockLLMClient(
                failuresBeforeSuccess = 1,
                failureFactory = { KoogHttpClientException(cause = cause) },
            )
            assertFailsWith<KoogHttpClientException> {
                RetryingLLMClient(
                    malformedClient,
                    RetryConfig(maxAttempts = 3, initialDelay = 0.milliseconds),
                ).execute(testPrompt, testModel, emptyList())
            }
            assertEquals(1, malformedClient.executeCalls)
        }

        val rootSerializationClient = MockLLMClient(
            failuresBeforeSuccess = 1,
            failureFactory = { SerializationException("malformed-json-sentinel 503") },
        )
        assertFailsWith<SerializationException> {
            RetryingLLMClient(
                rootSerializationClient,
                RetryConfig(maxAttempts = 3, initialDelay = 0.milliseconds),
            ).execute(testPrompt, testModel, emptyList())
        }
        assertEquals(1, rootSerializationClient.executeCalls)
    }

    @Test
    fun testRecognisedTransportFailuresRetryDirectlyAndThroughOrdinaryWrappers() = runTest {
        val factories = listOf<() -> Throwable>(
            { IOException("io-sentinel") },
            { ConnectTimeoutException("https://example.test", null) },
            { HttpRequestTimeoutException("https://example.test", 1L, null) },
        )

        factories.forEach { transportFactory ->
            listOf<(Throwable) -> Throwable>(
                { it },
                { RuntimeException("ordinary-wrapper", it) },
            ).forEach { wrapper ->
                val mockClient = MockLLMClient(
                    executeResponse = testResponse,
                    failuresBeforeSuccess = 1,
                    failureFactory = { wrapper(transportFactory()) },
                )

                assertEquals(
                    testResponse,
                    RetryingLLMClient(
                        mockClient,
                        RetryConfig(maxAttempts = 2, initialDelay = 0.milliseconds),
                    ).execute(testPrompt, testModel, emptyList()),
                )
                assertEquals(2, mockClient.executeCalls)
            }
        }
    }

    @Test
    fun testStatusBearingNonRetryableFailureWinsOverRecognisedTransportCause() = runTest {
        listOf<() -> Throwable>(
            { IOException("io-sentinel") },
            { ConnectTimeoutException("https://example.test", null) },
            { HttpRequestTimeoutException("https://example.test", 1L, null) },
        ).forEach { transportFactory ->
            val mockClient = MockLLMClient(
                failuresBeforeSuccess = 1,
                failureFactory = {
                    KoogHttpClientException(
                        statusCode = 400,
                        cause = RuntimeException("ordinary-wrapper", transportFactory()),
                    )
                },
            )

            assertFailsWith<KoogHttpClientException> {
                RetryingLLMClient(
                    mockClient,
                    RetryConfig(maxAttempts = 3, initialDelay = 0.milliseconds),
                ).execute(testPrompt, testModel, emptyList())
            }
            assertEquals(1, mockClient.executeCalls)
        }
    }

    @Test
    fun testRecognisedTransportInCauseCycleRetriesWithoutLooping() = runTest {
        val cycle = CyclicException()
        val transport = HttpRequestTimeoutException("https://example.test", 1L, cycle)
        cycle.next = transport
        val mockClient = MockLLMClient(
            executeResponse = testResponse,
            failuresBeforeSuccess = 1,
            failureFactory = { cycle },
        )

        assertEquals(
            testResponse,
            RetryingLLMClient(
                mockClient,
                RetryConfig(maxAttempts = 2, initialDelay = 0.milliseconds),
            ).execute(testPrompt, testModel, emptyList()),
        )
        assertEquals(2, mockClient.executeCalls)
    }

    @Test
    fun testBoundedBackoffAndRedactedObserverAreDeterministicWithoutJitter() = runTest {
        val observed = mutableListOf<RetryAttempt>()
        val sentinel = "prompt-body-user-chat-credential-sentinel"
        val mockClient = MockLLMClient(
            executeResponse = testResponse,
            failuresBeforeSuccess = 3,
            failureMessage = "503 $sentinel",
        )
        val retrying = RetryingLLMClient(
            mockClient,
            RetryConfig(
                maxAttempts = 4,
                initialDelay = 100.milliseconds,
                maxDelay = 300.milliseconds,
                backoffMultiplier = 2.0,
                jitterFactor = 0.0,
                retryablePatterns = RetryConfig.DEFAULT_PATTERNS,
                retryAfterExtractor = null,
            ),
        ).withRuntime(
            RetryRuntime(
                observer = observed::add,
                jitterSource = RetryJitterSource { 0.0 },
            )
        )

        assertEquals(testResponse, retrying.execute(testPrompt, testModel, emptyList()))
        assertEquals(listOf(100L, 200L, 300L), observed.map { it.delay.inWholeMilliseconds })
        assertEquals(listOf(1, 2, 3), observed.map { it.attempt })
        assertTrue(observed.all { it.operation == RetryOperation.EXECUTE && it.maxAttempts == 4 })
        assertTrue(observed.all { it.classification == RetryFailureClassification.CONFIGURED_PATTERN })
        assertTrue(observed.all { it.reason == RetryFailureReason.CONFIGURED_PATTERN })
        assertTrue(observed.none { it.toString().contains(sentinel) })
    }

    @Test
    fun testObserverFailureCannotChangeRetryOutcome() = runTest {
        val mockClient = MockLLMClient(
            executeResponse = testResponse,
            failuresBeforeSuccess = 1,
            failureMessage = "503",
        )
        val retrying = RetryingLLMClient(
            mockClient,
            RetryConfig(
                maxAttempts = 2,
                initialDelay = 0.milliseconds,
                retryablePatterns = RetryConfig.DEFAULT_PATTERNS,
            ),
        ).withObserver(RetryAttemptObserver { error("observer failure") })

        assertEquals(testResponse, retrying.execute(testPrompt, testModel, emptyList()))
        assertEquals(2, mockClient.executeCalls)
    }

    @Test
    fun testDefaultConfigNeverRetriesMisleadingRawStatusText() = runTest {
        val mockClient = MockLLMClient(
            failuresBeforeSuccess = 1,
            failureMessage = "misleading status 503",
        )

        assertFailsWith<RuntimeException> {
            RetryingLLMClient(
                mockClient,
                RetryConfig(maxAttempts = 3, initialDelay = 0.milliseconds),
            ).execute(testPrompt, testModel, emptyList())
        }
        assertEquals(1, mockClient.executeCalls)
    }

    @Test
    fun testDeterministicJitterMinMaxAndOverflowStayWithinConfiguredBounds() = runTest {
        suspend fun observedDelays(sample: Double): List<Long> {
            val observed = mutableListOf<RetryAttempt>()
            val mockClient = MockLLMClient(
                executeResponse = testResponse,
                failuresBeforeSuccess = 2,
                failureFactory = { KoogHttpClientException(statusCode = 503) },
            )
            val client = RetryingLLMClient(
                mockClient,
                RetryConfig(
                    maxAttempts = 3,
                    initialDelay = 100.milliseconds,
                    maxDelay = 300.milliseconds,
                    backoffMultiplier = Double.MAX_VALUE,
                    jitterFactor = 0.5,
                ),
            ).withRuntime(
                RetryRuntime(
                    observer = observed::add,
                    jitterSource = RetryJitterSource { sample },
                )
            )

            assertEquals(testResponse, client.execute(testPrompt, testModel, emptyList()))
            return observed.map { it.delay.inWholeMilliseconds }
        }

        assertEquals(listOf(100L, 300L), observedDelays(0.0))
        assertEquals(listOf(150L, 300L), observedDelays(1.0))

        val invalidClient = MockLLMClient(
            failuresBeforeSuccess = 1,
            failureFactory = { KoogHttpClientException(statusCode = 503) },
        )
        assertFailsWith<IllegalArgumentException> {
            RetryingLLMClient(
                invalidClient,
                RetryConfig(maxAttempts = 2, initialDelay = 0.milliseconds),
            ).withRuntime(
                RetryRuntime(jitterSource = RetryJitterSource { 1.01 })
            ).execute(testPrompt, testModel, emptyList())
        }
        assertEquals(1, invalidClient.executeCalls)
    }

    @Test
    fun testCauseTraversalStopsAtTwoAndThreeNodeIdentityCycles() = runTest {
        listOf(2, 3).forEach { size ->
            val failures = List(size) { CyclicException() }
            failures.indices.forEach { index ->
                failures[index].next = failures[(index + 1) % failures.size]
            }
            val mockClient = MockLLMClient(
                failuresBeforeSuccess = 1,
                failureFactory = { failures.first() },
            )

            assertFailsWith<CyclicException> {
                RetryingLLMClient(
                    mockClient,
                    RetryConfig(maxAttempts = 3, initialDelay = 0.milliseconds),
                ).execute(testPrompt, testModel, emptyList())
            }
            assertEquals(1, mockClient.executeCalls)
        }
    }

    @Test
    fun testWrappedCancellationPropagatesWithoutRetry() = runTest {
        val cancellation = CancellationException("cancelled")
        val mockClient = MockLLMClient(
            failureFactory = { LLMClientException("test", cause = cancellation) },
            failuresBeforeSuccess = 1,
        )

        val thrown = assertFailsWith<CancellationException> {
            RetryingLLMClient(mockClient, RetryConfig(maxAttempts = 3))
                .execute(testPrompt, testModel, emptyList())
        }

        assertSame(cancellation, thrown)
        assertEquals(1, mockClient.executeCalls)
    }

    // Mock LLMClient for testing
    private class MockLLMClient(
        private val executeResponse: Message.Assistant? = null,
        private val streamResponse: Flow<StreamFrame> = flowOf(),
        private val multipleChoicesResponse: LLMChoice? = null,
        private val moderateResponse: ModerationResult = ModerationResult(false, emptyMap()),
        private val embedResponse: List<Double> = emptyList(),
        private val batchEmbedResponse: List<List<Double>> = emptyList(),
        private var failuresBeforeSuccess: Int = 0,
        private var streamFailuresBeforeSuccess: Int = 0,
        private val failureMessage: String = "Mock failure",
        private val failureFactory: (() -> Throwable)? = null,
        private val throwCancellation: Boolean = false,
        private val llmProvider: LLMProvider = LLMProvider.OpenAI,
    ) : LLMClient() {

        val basicJsonSchemaGeneratorDefault = object : BasicJsonSchemaGenerator() {}
        val standardJsonSchemaGeneratorDefault = object : StandardJsonSchemaGenerator() {}

        var executeCalls = 0
        var streamCalls = 0
        var multipleChoicesCalls = 0
        var moderateCalls = 0
        var embedCalls = 0
        var batchEmbedCalls = 0

        private var executeFailures = 0
        private var streamFailures = 0
        private var multipleChoicesFailures = 0
        private var moderateFailures = 0
        private var embedFailures = 0
        private var batchEmbedFailures = 0

        override fun llmProvider(): LLMProvider = llmProvider

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Message.Assistant {
            executeCalls++

            if (throwCancellation) {
                throw CancellationException("Cancelled")
            }

            if (executeFailures < failuresBeforeSuccess) {
                executeFailures++
                throw failureFactory?.invoke() ?: RuntimeException(failureMessage)
            }

            return executeResponse!!
        }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> = flow {
            streamCalls++

            if (streamFailures < streamFailuresBeforeSuccess) {
                streamFailures++
                throw failureFactory?.invoke() ?: RuntimeException(failureMessage)
            }

            streamResponse.collect { emit(it) }
        }

        override suspend fun executeMultipleChoices(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): LLMChoice {
            multipleChoicesCalls++

            if (multipleChoicesFailures < failuresBeforeSuccess) {
                multipleChoicesFailures++
                throw RuntimeException(failureMessage)
            }

            return multipleChoicesResponse!!
        }

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
            moderateCalls++

            if (moderateFailures < failuresBeforeSuccess) {
                moderateFailures++
                throw RuntimeException(failureMessage)
            }

            return moderateResponse
        }

        override suspend fun embed(
            text: String,
            model: LLModel
        ): List<Double> {
            embedCalls++

            if (embedFailures < failuresBeforeSuccess) {
                embedFailures++
                throw RuntimeException(failureMessage)
            }

            return embedResponse
        }

        override suspend fun embed(
            inputs: List<String>,
            model: LLModel
        ): List<List<Double>> {
            batchEmbedCalls++

            if (batchEmbedFailures < failuresBeforeSuccess) {
                batchEmbedFailures++
                throw RuntimeException(failureMessage)
            }

            return batchEmbedResponse
        }

        override fun close() {
            // No resources to close
        }

        override fun getBasicJsonSchemaGenerator(): BasicJsonSchemaGenerator {
            return basicJsonSchemaGeneratorDefault
        }

        override fun getStandardJsonSchemaGenerator(): StandardJsonSchemaGenerator {
            return standardJsonSchemaGeneratorDefault
        }
    }

    private class ProtocolException : Exception()

    private class CyclicException : Exception() {
        lateinit var next: Throwable
        override val cause: Throwable
            get() = next
    }
}
