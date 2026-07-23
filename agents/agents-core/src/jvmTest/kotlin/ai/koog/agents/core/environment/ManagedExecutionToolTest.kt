@file:OptIn(DetachedPromptExecutorAPI::class, InternalAgentsApi::class)

package ai.koog.agents.core.environment

import ai.koog.agents.core.agent.context.AIAgentLLMContext
import ai.koog.agents.core.agent.context.AgentTestBase
import ai.koog.agents.core.agent.context.DetachedPromptExecutorAPI
import ai.koog.agents.core.agent.tools.ManagedExecutionFailedException
import ai.koog.agents.core.agent.tools.ManagedExecutionProtocolException
import ai.koog.agents.core.agent.tools.ManagedExecutionTool
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.managed.ManagedExecutionErrorKind
import ai.koog.prompt.executor.managed.ManagedExecutionEvent
import ai.koog.prompt.executor.managed.ManagedExecutionFileReference
import ai.koog.prompt.executor.managed.ManagedExecutionSessionReference
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.typeToken
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KLoggingEventBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import io.github.oshai.kotlinlogging.Marker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManagedExecutionToolTest : AgentTestBase() {
    private companion object {
        private const val REDACTED = "<managed-execution-data-redacted>"
    }

    private val serializer = KotlinxSerializer()

    @Serializable
    private data class Args(val code: String)

    @Test
    fun testOrderedEventsBecomeOneFinalToolResult() = runTest {
        val events = successfulEvents()
        var flowCompleted = false
        val tool = TestManagedTool {
            flow {
                events.forEach { emit(it) }
                flowCompleted = true
            }
        }
        val observations = mutableListOf<ManagedExecutionObservation>()
        val environment = environment(tool)
        val metadata = ToolCallMetadata.EMPTY.withManagedExecutionEventObserver {
            if (it.event is ManagedExecutionEvent.Terminal) {
                assertTrue(flowCompleted)
            }
            observations += it
        }

        val result = environment.executeTool(toolCall(), metadata)

        assertEquals(events, observations.map { it.event })
        assertEquals(events.indices.map(Int::toLong), observations.map { it.eventIndex })
        assertEquals(ToolResultKind.Success, result.resultKind)
        assertEquals("done", result.output)
        val finalPart = result.toMessagePart()
        assertEquals("managed_execution", finalPart.tool)
        assertEquals(1, finalPart.parts.size)
        assertEquals("done", assertIs<MessagePart.Text>(finalPart.parts.single()).text)
    }

    @Test
    fun testStreamingAndOrdinaryCustomToolsCoexist() = runTest {
        val managed = TestManagedTool { flowOf(*successfulEvents().toTypedArray()) }
        val ordinary = OrdinaryTool()
        val registry = ToolRegistry {
            tool(managed)
            tool(ordinary)
        }
        val environment = environment(registry)

        assertEquals(
            listOf("managed_execution", "ordinary"),
            registry.tools.map { it.descriptor.name },
        )
        assertEquals("done", environment.executeTool(toolCall()).output)
        assertEquals(
            "ordinary:value",
            environment.executeTool(
                MessagePart.Tool.Call(
                    id = "ordinary-call",
                    tool = "ordinary",
                    args = """{"code":"value"}""",
                )
            ).output,
        )
    }

    @Test
    fun testCancellationPropagatesWithoutTerminalOrFailureResult() = runTest {
        val requestObserved = CompletableDeferred<Unit>()
        val observations = mutableListOf<ManagedExecutionObservation>()
        val tool = TestManagedTool {
            flow {
                emit(request())
                awaitCancellation()
            }
        }
        val environment = environment(tool)
        val metadata = ToolCallMetadata.EMPTY.withManagedExecutionEventObserver {
            observations += it
            requestObserved.complete(Unit)
        }
        val execution = backgroundScope.async {
            environment.executeTool(toolCall(), metadata)
        }

        requestObserved.await()
        execution.cancel(CancellationException("test cancellation"))
        assertFailsWith<CancellationException> { execution.await() }
        execution.cancelAndJoin()

        assertEquals(1, observations.size)
        assertIs<ManagedExecutionEvent.Request>(observations.single().event)
    }

    @Test
    fun testCancellationAfterHeldTerminalDoesNotObserveItOrReturnOrdinaryResult() = runTest {
        val observations = mutableListOf<ManagedExecutionObservation>()
        val tool = TestManagedTool {
            flow {
                emit(request())
                emit(result())
                throw CancellationException("provider collection cancelled")
            }
        }
        val metadata = ToolCallMetadata.EMPTY.withManagedExecutionEventObserver { observations += it }

        assertFailsWith<CancellationException> {
            environment(tool).executeTool(toolCall(), metadata)
        }

        assertEquals(1, observations.size)
        assertIs<ManagedExecutionEvent.Request>(observations.single().event)
    }

    @Test
    fun testInvalidFirstEventUsesSafeProtocolFailure() = runTest {
        val observations = mutableListOf<ManagedExecutionObservation>()
        val received = executeMalformed(
            events = listOf(
                ManagedExecutionEvent.Stdout(
                    sequence = 0,
                    executionId = "execution-1",
                    session = session(),
                    text = "stdout-secret",
                )
            ),
            observations = observations,
        )

        assertProtocolFailure(received, "must begin with a request event", "stdout-secret")
        assertTrue(observations.isEmpty())
    }

    @Test
    fun testMissingTerminalUsesSafeProtocolFailure() = runTest {
        val observations = mutableListOf<ManagedExecutionObservation>()
        val received = executeMalformed(
            events = listOf(
                request(),
                ManagedExecutionEvent.Stdout(
                    sequence = 1,
                    executionId = "execution-1",
                    session = session(),
                    text = "stdout-secret",
                ),
            ),
            observations = observations,
        )

        assertProtocolFailure(received, "ended without a terminal event", "print('hello')", "stdout-secret")
        assertEquals(2, observations.size)
        assertFalse(observations.any { it.event is ManagedExecutionEvent.Terminal })
    }

    @Test
    fun testDuplicateTerminalIsRejectedBeforeEitherTerminalIsObserved() = runTest {
        val terminal = result()
        val observations = mutableListOf<ManagedExecutionObservation>()
        val received = executeMalformed(
            events = listOf(request(), terminal, terminal.copy(sequence = 3, output = "result-secret")),
            observations = observations,
        )

        assertProtocolFailure(received, "more than one terminal event", "result-secret")
        assertFalse(observations.any { it.event is ManagedExecutionEvent.Terminal })
    }

    @Test
    fun testNonTerminalAfterTerminalIsRejectedBeforeTerminalIsObserved() = runTest {
        val observations = mutableListOf<ManagedExecutionObservation>()
        val received = executeMalformed(
            events = listOf(
                request(),
                result(),
                ManagedExecutionEvent.Stderr(
                    sequence = 3,
                    executionId = "execution-1",
                    session = session(),
                    text = "stderr-secret",
                ),
            ),
            observations = observations,
        )

        assertProtocolFailure(received, "non-terminal event after its terminal event", "stderr-secret")
        assertFalse(observations.any { it.event is ManagedExecutionEvent.Terminal })
    }

    @Test
    fun testNonIncreasingSequenceUsesProtocolFailure() = runTest {
        val observations = mutableListOf<ManagedExecutionObservation>()
        val received = executeMalformed(
            events = listOf(
                request(),
                ManagedExecutionEvent.Stdout(
                    sequence = 0,
                    executionId = "execution-1",
                    session = session(),
                    text = "stdout-secret",
                ),
            ),
            observations = observations,
        )

        assertProtocolFailure(received, "event sequence must increase strictly", "stdout-secret")
        assertEquals(listOf(0L), observations.map { it.eventIndex })
    }

    @Test
    fun testMismatchedExecutionIdentityUsesProtocolFailure() = runTest {
        val observations = mutableListOf<ManagedExecutionObservation>()
        val received = executeMalformed(
            events = listOf(
                request(),
                ManagedExecutionEvent.Stdout(
                    sequence = 1,
                    executionId = "different-execution",
                    session = session(),
                    text = "stdout-secret",
                ),
            ),
            observations = observations,
        )

        assertProtocolFailure(received, "changed execution identity", "stdout-secret")
        assertEquals(listOf(0L), observations.map { it.eventIndex })
    }

    @Test
    fun testMismatchedSessionIdentityUsesProtocolFailure() = runTest {
        val observations = mutableListOf<ManagedExecutionObservation>()
        val received = executeMalformed(
            events = listOf(
                request(),
                ManagedExecutionEvent.Stdout(
                    sequence = 1,
                    executionId = "execution-1",
                    session = session().copy(sandboxResourceName = "secret-sandbox"),
                    text = "stdout-secret",
                ),
            ),
            observations = observations,
        )

        assertProtocolFailure(received, "changed session identity", "secret-sandbox", "stdout-secret")
        assertEquals(listOf(0L), observations.map { it.eventIndex })
    }

    @Test
    fun testProviderErrorIsObservedOnceAndReturnsOneOrdinaryFailure() = runTest {
        var flowCompleted = false
        val providerError = ManagedExecutionEvent.Error(
            sequence = 1,
            executionId = "execution-1",
            session = session(),
            kind = ManagedExecutionErrorKind.EXECUTION_FAILED,
            message = "provider execution failed",
            providerCode = "provider-code",
        )
        val observations = mutableListOf<ManagedExecutionObservation>()
        val tool = TestManagedTool {
            flow {
                emit(request())
                emit(providerError)
                flowCompleted = true
            }
        }
        val metadata = ToolCallMetadata.EMPTY.withManagedExecutionEventObserver {
            if (it.event is ManagedExecutionEvent.Terminal) {
                assertTrue(flowCompleted)
            }
            observations += it
        }

        val received = environment(tool).executeTool(toolCall(), metadata)

        val failure = assertIs<ToolResultKind.Failure>(received.resultKind)
        assertIs<ManagedExecutionFailedException>(failure.error)
        assertTrue(received.output.contains("provider execution failed"))
        assertTrue(received.toMessagePart().isError)
        assertEquals(listOf(request(), providerError), observations.map { it.event })
        assertEquals(1, observations.count { it.event is ManagedExecutionEvent.Terminal })
    }

    @Test
    fun testObserverBackpressureStopsUpstreamEventProduction() = runTest {
        val produced = mutableListOf<Long>()
        val releaseRequest = CompletableDeferred<Unit>()
        val tool = TestManagedTool {
            flow {
                successfulEvents().forEach { event ->
                    produced += event.sequence
                    emit(event)
                }
            }
        }
        val metadata = ToolCallMetadata.EMPTY.withManagedExecutionEventObserver {
            if (it.eventIndex == 0L) {
                releaseRequest.await()
            }
        }
        val execution = backgroundScope.async {
            environment(tool).executeTool(toolCall(), metadata)
        }

        runCurrent()
        assertEquals(listOf(0L), produced)

        releaseRequest.complete(Unit)
        assertEquals("done", execution.await().output)
        assertEquals(listOf(0L, 1L, 2L), produced)
    }

    @Test
    fun testSuccessfulManagedExecutionLogsRedactArgumentsOutputAndFileBytesAcrossEnvironments() = runTest {
        val codeSecret = "success-code-sentinel"
        val stdoutSecret = "success-stdout-sentinel"
        val stderrSecret = "success-stderr-sentinel"
        val fileBytesSecret = "success-file-bytes-sentinel"
        val resultSecret = "success-result-sentinel"
        val events = listOf(
            request(code = codeSecret),
            ManagedExecutionEvent.Stdout(1, "execution-1", session(), stdoutSecret),
            ManagedExecutionEvent.Stderr(2, "execution-1", session(), stderrSecret),
            ManagedExecutionEvent.GeneratedFileChunk(
                sequence = 3,
                executionId = "execution-1",
                session = session(),
                fileId = "file-1",
                reference = ManagedExecutionFileReference.VertexAgentEngine(
                    sandboxResourceName = "sandbox-1",
                    path = "/tmp/result.bin",
                ),
                offset = 0,
                bytes = fileBytesSecret.encodeToByteArray(),
            ),
            result(sequence = 4, output = resultSecret),
        )

        val logs = executeAndCaptureLogs(
            tool = TestManagedTool { flowOf(*events.toTypedArray()) },
            inputCode = codeSecret,
        )

        assertManagedLogsRedacted(
            logs,
            codeSecret,
            stdoutSecret,
            stderrSecret,
            fileBytesSecret,
            resultSecret,
        )
    }

    @Test
    fun testManagedProtocolFailureLogsRedactCodeAcrossEnvironments() = runTest {
        val codeSecret = "protocol-code-sentinel"
        val stdoutSecret = "protocol-stdout-sentinel"
        val logs = executeAndCaptureLogs(
            tool = TestManagedTool {
                flowOf(
                    request(code = codeSecret),
                    ManagedExecutionEvent.Stdout(0, "execution-1", session(), stdoutSecret),
                )
            },
            inputCode = codeSecret,
        )

        assertManagedLogsRedacted(logs, codeSecret, stdoutSecret)
    }

    @Test
    fun testManagedProviderErrorLogsRedactProviderDetailAcrossEnvironments() = runTest {
        val codeSecret = "provider-code-input-sentinel"
        val providerDetailSecret = "provider-detail-sentinel"
        val providerCodeSecret = "provider-code-sentinel"
        val logs = executeAndCaptureLogs(
            tool = TestManagedTool {
                flowOf(
                    request(code = codeSecret),
                    ManagedExecutionEvent.Error(
                        sequence = 1,
                        executionId = "execution-1",
                        session = session(),
                        kind = ManagedExecutionErrorKind.PROVIDER_FAILURE,
                        message = providerDetailSecret,
                        providerCode = providerCodeSecret,
                    ),
                )
            },
            inputCode = codeSecret,
        )

        assertManagedLogsRedacted(logs, codeSecret, providerDetailSecret, providerCodeSecret)
    }

    @Test
    fun testOrdinaryToolRetainsArgumentAndResultDiagnostics() = runTest {
        val logger = RecordingLogger()
        val registry = ToolRegistry { tool(OrdinaryTool()) }
        val call = MessagePart.Tool.Call(
            id = "ordinary-call",
            tool = "ordinary",
            args = """{"code":"ordinary-argument-sentinel"}""",
        )

        environment(registry, logger).executeTool(call)

        val logs = logger.rendered()
        assertTrue(logs.contains("ordinary-argument-sentinel"))
        assertTrue(logs.contains("ordinary:ordinary-argument-sentinel"))
        assertFalse(logs.contains(REDACTED))
    }

    private suspend fun executeAndCaptureLogs(
        tool: TestManagedTool,
        inputCode: String,
    ): String {
        val logger = RecordingLogger()
        val registry = ToolRegistry { tool(tool) }
        val generic = environment(registry, logger)
        val templateContext = createTestLLMContext()
        val llmContext = AIAgentLLMContext(
            tools = registry.tools.map { it.descriptor },
            toolRegistry = registry,
            prompt = templateContext.prompt,
            model = templateContext.model,
            responseProcessor = templateContext.responseProcessor,
            promptExecutor = templateContext.promptExecutor,
            environment = generic,
            config = templateContext.config,
            clock = templateContext.clock,
        )
        val context = createTestContext(environment = generic, llmContext = llmContext)
        val contextual = ContextualAgentEnvironment(generic, context)
        val jvmLogs = JvmLogCapture(
            "managed-call-id",
            "ai.koog.agents.core.environment.ContextualAgentEnvironment"
        )

        try {
            contextual.executeTool(
                MessagePart.Tool.Call(
                    id = "managed-call-id",
                    tool = "managed_execution",
                    args = """{"code":"$inputCode"}""",
                )
            )
        } finally {
            jvmLogs.close()
        }

        return logger.rendered() + "\n" + jvmLogs.rendered()
    }

    private fun assertManagedLogsRedacted(logs: String, vararg secrets: String) {
        assertTrue(logs.contains(REDACTED), logs)
        assertTrue(logs.contains("managed_execution"), logs)
        assertTrue(logs.contains("managed-call-id"), logs)
        secrets.forEach { secret -> assertFalse(logs.contains(secret), logs) }
    }

    private fun environment(tool: ManagedExecutionTool<Args, String>): GenericAgentEnvironment =
        environment(ToolRegistry { tool(tool) })

    private fun environment(
        registry: ToolRegistry,
        logger: KLogger = KotlinLogging.logger { },
    ): GenericAgentEnvironment =
        GenericAgentEnvironment(
            agentId = "agent-1",
            logger = logger,
            toolRegistry = registry,
            serializer = serializer,
        )

    private suspend fun executeMalformed(
        events: List<ManagedExecutionEvent>,
        observations: MutableList<ManagedExecutionObservation>,
    ): ReceivedToolResult {
        val tool = TestManagedTool { flowOf(*events.toTypedArray()) }
        val metadata = ToolCallMetadata.EMPTY.withManagedExecutionEventObserver { observations += it }
        return environment(tool).executeTool(toolCall(), metadata)
    }

    private fun assertProtocolFailure(
        received: ReceivedToolResult,
        expectedMessage: String,
        vararg secrets: String,
    ) {
        val failure = assertIs<ToolResultKind.Failure>(received.resultKind)
        val protocolFailure = assertIs<ManagedExecutionProtocolException>(failure.error)
        assertTrue(requireNotNull(protocolFailure.message).contains(expectedMessage))
        secrets.forEach { secret ->
            assertFalse(requireNotNull(protocolFailure.message).contains(secret))
            assertFalse(received.output.contains(secret))
        }
    }

    private fun toolCall(): MessagePart.Tool.Call = MessagePart.Tool.Call(
        id = "call-1",
        tool = "managed_execution",
        args = """{"code":"print('hello')"}""",
    )

    private fun successfulEvents(): List<ManagedExecutionEvent> = listOf(
        request(),
        ManagedExecutionEvent.Stdout(
            sequence = 1,
            executionId = "execution-1",
            session = session(),
            text = "hello",
        ),
        result(),
    )

    private fun request(code: String = "print('hello')"): ManagedExecutionEvent.Request = ManagedExecutionEvent.Request(
        sequence = 0,
        executionId = "execution-1",
        session = session(),
        code = code,
    )

    private fun result(
        sequence: Long = 2,
        output: String = "done",
    ): ManagedExecutionEvent.Result = ManagedExecutionEvent.Result(
        sequence = sequence,
        executionId = "execution-1",
        session = session(),
        output = output,
        exitCode = 0,
    )

    private fun session(): ManagedExecutionSessionReference.VertexAgentEngine =
        ManagedExecutionSessionReference.VertexAgentEngine(
            project = "project-1",
            location = "us-central1",
            reasoningEngineResource = "reasoningEngines/engine-1",
            sandboxResourceName = "reasoningEngines/engine-1/sandboxes/box-1",
        )

    private class TestManagedTool(
        private val events: (Args) -> Flow<ManagedExecutionEvent>,
    ) : ManagedExecutionTool<Args, String>(
        argsType = typeToken<Args>(),
        resultType = typeToken<String>(),
        name = "managed_execution",
        description = "Executes code in a provider-managed session.",
    ) {
        override fun executeStreaming(
            args: Args,
            metadata: ToolCallMetadata,
        ): Flow<ManagedExecutionEvent> = events(args)

        override fun decodeResult(result: ManagedExecutionEvent.Result): String =
            requireNotNull(result.output)

        override fun encodeResultToString(result: String, serializer: JSONSerializer): String = result
    }

    private class OrdinaryTool : SimpleTool<Args>(
        argsType = typeToken<Args>(),
        name = "ordinary",
        description = "An ordinary custom tool.",
    ) {
        override suspend fun execute(args: Args): String = "ordinary:${args.code}"
    }

    private class RecordingLogger : KLogger {
        override val name: String = "ManagedExecutionToolTest"
        private val messages = mutableListOf<String>()

        override fun at(
            level: Level,
            marker: Marker?,
            block: KLoggingEventBuilder.() -> Unit,
        ) {
            messages += "[${level.name}] ${KLoggingEventBuilder().apply(block).message}"
        }

        override fun isLoggingEnabledFor(level: Level, marker: Marker?): Boolean = true

        fun rendered(): String = messages.joinToString("\n")
    }
}
