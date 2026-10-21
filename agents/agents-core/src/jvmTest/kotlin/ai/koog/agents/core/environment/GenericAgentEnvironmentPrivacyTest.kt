package ai.koog.agents.core.environment

import ai.koog.agents.core.agent.tools.ManagedExecutionTool
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.managed.ManagedExecutionEvent
import ai.koog.prompt.executor.managed.ManagedExecutionSessionReference
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.typeToken
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KLoggingEventBuilder
import io.github.oshai.kotlinlogging.Level
import io.github.oshai.kotlinlogging.Marker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GenericAgentEnvironmentPrivacyTest {
    @Serializable
    private data class Args(val source: String)

    private enum class Failure { NONE, EXECUTE, STRING, JSON, PARTS, PROTOCOL, CANCEL }

    private val secret = "private-marker"

    private fun failure(): IllegalStateException = IllegalStateException(
        "$secret-message", IllegalArgumentException("$secret-cause"),
    ).apply { addSuppressed(IllegalStateException("$secret-suppressed")) }

    private inner class Ordinary(private val mode: Failure) : SimpleTool<Args>(
        argsType = typeToken<Args>(), name = "ordinary", description = "Privacy fixture",
    ) {
        override suspend fun execute(args: Args): String {
            if (mode == Failure.EXECUTE) throw failure()
            if (mode == Failure.CANCEL) throw CancellationException(secret)
            return args.source + "-result"
        }
        override fun encodeResultToString(result: String, serializer: JSONSerializer): String {
            if (mode == Failure.STRING) throw failure()
            return result
        }
        override fun encodeResult(result: String, serializer: JSONSerializer): JSONElement {
            if (mode == Failure.JSON) throw failure()
            return super.encodeResult(result, serializer)
        }
        override fun encodeResultToParts(result: String, serializer: JSONSerializer): List<MessagePart.ContentPart> {
            if (mode == Failure.PARTS) throw failure()
            return super.encodeResultToParts(result, serializer)
        }
    }

    private inner class Managed(private val mode: Failure) : ManagedExecutionTool<Args, String>(
        argsType = typeToken<Args>(), resultType = typeToken<String>(),
        name = "managed", description = "Privacy fixture",
    ) {
        override fun executeStreaming(args: Args, metadata: ToolCallMetadata): Flow<ManagedExecutionEvent> = flow {
            if (mode == Failure.EXECUTE) throw failure()
            if (mode == Failure.CANCEL) throw CancellationException(secret)
            val session = ManagedExecutionSessionReference.VertexAgentEngine(
                project = secret, location = secret, reasoningEngineResource = secret, sandboxResourceName = secret,
            )
            emit(ManagedExecutionEvent.Request(0, secret, session, args.source))
            emit(ManagedExecutionEvent.Result(if (mode == Failure.PROTOCOL) 0 else 1, secret, session, output = "$secret-result", exitCode = 0))
        }
        override fun decodeResult(result: ManagedExecutionEvent.Result): String = requireNotNull(result.output)
        override fun encodeResultToString(result: String, serializer: JSONSerializer): String {
            if (mode == Failure.STRING) throw failure()
            return result
        }
        override fun encodeResult(result: String, serializer: JSONSerializer): JSONElement {
            if (mode == Failure.JSON) throw failure()
            return super.encodeResult(result, serializer)
        }
        override fun encodeResultToParts(result: String, serializer: JSONSerializer): List<MessagePart.ContentPart> {
            if (mode == Failure.PARTS) throw failure()
            return super.encodeResultToParts(result, serializer)
        }
    }

    private fun environment(tool: ToolBase<*, *>, logger: RecordingLogger) = GenericAgentEnvironment(
        agentId = "$secret-agent\ninjected", logger = logger,
        toolRegistry = ToolRegistry { tool(tool) }, serializer = KotlinxSerializer(),
    )

    private fun call(name: String, args: String = """{"source":"$secret-source"}""") = MessagePart.Tool.Call(
        id = "$secret-call\ninjected", tool = name, args = args,
    )

    @Test
    fun testSuccessPreservesResultsAndOpaqueCorrelation() = runTest {
        for (tool in listOf(Ordinary(Failure.NONE), Managed(Failure.NONE))) {
            val logger = RecordingLogger()
            val environment = environment(tool, logger)
            val result = environment.executeTool(call(tool.name))
            assertEquals(ToolResultKind.Success, result.resultKind)
            assertTrue(result.output.orEmpty().contains(secret))
            logger.assertPrivate()
            logger.assertCategory(Level.INFO, "execution_started")
            logger.assertCategory(Level.DEBUG, "result_received")
            logger.assertCategory(Level.TRACE, "execution_completed")
            val firstIds = logger.executionIds()
            assertEquals(1, firstIds.size)
            environment.executeTool(call(tool.name))
            assertEquals(2, logger.executionIds().size)
            logger.assertPrivate()
        }
    }

    @Test
    fun testMalformedAndUndecodableArgumentsRemainPrivate() = runTest {
        for (tool in listOf(Ordinary(Failure.NONE), Managed(Failure.NONE))) {
            for (args in listOf("$secret-invalid-json", """{"wrong":"$secret"}""")) {
                val logger = RecordingLogger()
                val result = environment(tool, logger).executeTool(call(tool.name, args))
                assertTrue(result.resultKind is ToolResultKind.Failure)
                logger.assertPrivate()
                if (args.startsWith("{")) logger.assertCategory(Level.ERROR, "argument_decode_failed")
            }
        }
    }

    @Test
    fun testExecutionAndEveryEncodingFailureRemainPrivate() = runTest {
        for (mode in listOf(Failure.EXECUTE, Failure.STRING, Failure.JSON, Failure.PARTS)) {
            for (tool in listOf(Ordinary(mode), Managed(mode))) {
                val logger = RecordingLogger()
                val result = environment(tool, logger).executeTool(call(tool.name))
                assertTrue(result.resultKind is ToolResultKind.Failure)
                assertTrue(result.output.orEmpty().contains("$secret-message"))
                logger.assertCategory(Level.ERROR, if (mode == Failure.EXECUTE) "execution_failed" else "result_encode_failed")
                logger.assertPrivate()
            }
        }
    }

    @Test
    fun testManagedProtocolFailureRemainsPrivate() = runTest {
        val logger = RecordingLogger()
        val result = environment(Managed(Failure.PROTOCOL), logger).executeTool(call("managed"))
        assertTrue(result.resultKind is ToolResultKind.Failure)
        logger.assertCategory(Level.ERROR, "execution_protocol_failed")
        logger.assertPrivate()
    }

    @Test
    fun testUnknownToolDoesNotLogUntrustedName() = runTest {
        val logger = RecordingLogger()
        val result = environment(Ordinary(Failure.NONE), logger).executeTool(call("$secret-unknown\ninjected"))
        assertTrue(result.resultKind is ToolResultKind.Failure)
        logger.assertCategory(Level.ERROR, "tool_not_found")
        logger.assertPrivate()
    }

    @Test
    fun testReportProblemPreservesExceptionWithoutLoggingIt() = runTest {
        val logger = RecordingLogger()
        val expected = failure()
        val actual = assertFailsWith<IllegalStateException> {
            environment(Ordinary(Failure.NONE), logger).reportProblem(expected)
        }
        assertSame(expected, actual)
        logger.assertCategory(Level.ERROR, "agent_problem")
        logger.assertPrivate()
    }

    @Test
    fun testCancellationStillPropagatesWithoutFailureDiagnostics() = runTest {
        for (tool in listOf(Ordinary(Failure.CANCEL), Managed(Failure.CANCEL))) {
            val logger = RecordingLogger()
            assertFailsWith<CancellationException> { environment(tool, logger).executeTool(call(tool.name)) }
            assertFalse(logger.events.any { it.first == Level.ERROR })
            logger.assertPrivate()
        }
    }

    private inner class RecordingLogger : KLogger {
        override val name = "GenericAgentEnvironmentPrivacyTest"
        val events = mutableListOf<Pair<Level, KLoggingEventBuilder>>()
        private val markers = mutableListOf<Marker?>()
        override fun isLoggingEnabledFor(level: Level, marker: Marker?) = true
        override fun at(level: Level, marker: Marker?, block: KLoggingEventBuilder.() -> Unit) {
            markers += marker
            events += level to KLoggingEventBuilder().apply(block)
        }
        fun assertCategory(level: Level, category: String) {
            assertTrue(events.any { it.first == level && it.second.message.orEmpty().endsWith("category=$category") })
        }
        fun executionIds(): Set<String> = events.mapNotNull {
            Regex("execution_id=([0-9a-f-]{36})").find(it.second.message.orEmpty())?.groupValues?.get(1)
        }.toSet()
        fun assertPrivate() {
            assertTrue(events.isNotEmpty())
            for ((_, event) in events) {
                assertFalse(event.message.orEmpty().contains(secret))
                assertFalse(event.message.orEmpty().contains('\n'))
                assertTrue(event.cause == null, "Diagnostics must not attach arbitrary throwables")
                assertTrue(event.payload.isNullOrEmpty(), "Diagnostics must not attach payload fields")
                assertTrue(event.arguments.isNullOrEmpty(), "Diagnostics must not attach formatting arguments")
            }
            assertTrue(markers.all { it == null })
        }
    }
}
