package ai.koog.agents.core.environment

import ai.koog.agents.core.agent.tools.ManagedExecutionBindingException
import ai.koog.agents.core.agent.tools.ManagedExecutionBindingFailure
import ai.koog.agents.core.agent.tools.ManagedExecutionReleasePolicy
import ai.koog.agents.core.agent.tools.ManagedExecutionServiceBinding
import ai.koog.agents.core.agent.tools.ManagedExecutionToolResult
import ai.koog.agents.core.agent.tools.createManagedExecutionServiceBinding
import ai.koog.prompt.executor.managed.ManagedExecutionEvent
import ai.koog.prompt.executor.managed.ManagedExecutionRequest
import ai.koog.prompt.executor.managed.ManagedExecutionService
import ai.koog.prompt.executor.managed.ManagedExecutionSession
import ai.koog.prompt.executor.managed.ManagedExecutionSessionOwnership
import ai.koog.prompt.executor.managed.ManagedExecutionSessionReference
import ai.koog.prompt.provider.HostedExecutionAcceptance
import ai.koog.prompt.provider.HostedExecutionAcceptanceUnsupportedReason
import ai.koog.prompt.provider.ManagedExecutionServiceKind
import ai.koog.prompt.provider.ProviderApi
import ai.koog.prompt.provider.ProviderCapabilityMatrix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceBackedManagedExecutionToolTest {
    private val reference = ManagedExecutionSessionReference.VertexAgentEngine(
        project = "project-1",
        location = "us-central1",
        reasoningEngineResource = "reasoningEngines/engine-1",
        sandboxResourceName = "reasoningEngines/engine-1/sandboxes/box-1",
    )
    private val request = ManagedExecutionRequest("execution-1", "print('secret')")

    @Test
    fun testOwnedSessionExecutesOnceAndReleasesOnce() = runTest {
        val service = RecordingService(reference, successfulEvents())
        val binding = ManagedExecutionServiceBinding(service, request)

        assertEquals(successfulEvents(), binding.events().toList())
        assertEquals(1, service.acquireCount)
        assertEquals(1, service.executeCount)
        assertEquals(1, service.releaseCount)
        assertTrue(binding.ownsSession)
        assertEquals(ManagedExecutionSessionOwnership.OWNED, binding.acquiredSession?.ownership)
    }

    @Test
    fun testBorrowedSessionSurvivesSuccessAndFailure() = runTest {
        val success = RecordingService(reference, successfulEvents())
        val successfulBinding = ManagedExecutionServiceBinding(success, request, persistedSession = reference)
        successfulBinding.events().toList()

        assertEquals(ManagedExecutionSessionOwnership.BORROWED, successfulBinding.acquiredSession?.ownership)
        assertEquals(0, success.releaseCount)

        val failure = RecordingService(
            reference = reference,
            events = flow { error("provider failure") },
        )
        val failedBinding = ManagedExecutionServiceBinding(failure, request, persistedSession = reference)
        runCatching { failedBinding.events().toList() }

        assertEquals(0, failure.releaseCount)
    }

    @Test
    fun testOwnedCancellationReleasesExactlyOnce() = runTest {
        val started = CompletableDeferred<Unit>()
        val service = RecordingService(
            reference = reference,
            events = flow {
                started.complete(Unit)
                awaitCancellation()
            },
        )
        val binding = ManagedExecutionServiceBinding(service, request)
        val collection = backgroundScope.launch { binding.events().toList() }

        started.await()
        collection.cancelAndJoin()
        runCurrent()

        assertEquals(1, service.releaseCount)
    }

    @Test
    fun testConcurrentCollectorsPermitExactlyOneAcquisition() = runTest {
        val service = RecordingService(reference, successfulEvents())
        val binding = ManagedExecutionServiceBinding(service, request)

        val outcomes = List(64) {
            async {
                runCatching { binding.events().toList() }
            }
        }.awaitAll()

        assertEquals(1, outcomes.count(Result<List<ManagedExecutionEvent>>::isSuccess))
        assertEquals(
            63,
            outcomes.count { outcome ->
                val failure = outcome.exceptionOrNull() as? ManagedExecutionBindingException
                failure?.reason == ManagedExecutionBindingFailure.ALREADY_COLLECTED
            },
        )
        assertEquals(1, service.acquireCount)
        assertEquals(1, service.executeCount)
        assertEquals(1, service.releaseCount)
    }

    @Test
    fun testRetainPolicyDoesNotReleaseOwnedSession() = runTest {
        val service = RecordingService(reference, successfulEvents())
        val binding = ManagedExecutionServiceBinding(
            service,
            request,
            releasePolicy = ManagedExecutionReleasePolicy.RETAIN_OWNED,
        )

        binding.events().toList()

        assertTrue(binding.ownsSession)
        assertEquals(0, service.releaseCount)
    }

    @Test
    fun testProcessRoundTripBorrowsSessionOnSecondExecutionWithoutStoppingIt() = runTest {
        val persisted = ManagedExecutionToolResult(
            executionId = "execution-1",
            session = reference,
            output = "done",
            exitCode = 0,
        )
        val restored = Json.decodeFromString<ManagedExecutionToolResult>(Json.encodeToString(persisted))
        val service = RecordingService(reference, successfulEvents())
        val secondTurn = ManagedExecutionServiceBinding(
            service = service,
            request = request.copy(executionId = "execution-2"),
            persistedSession = restored.session,
        )

        secondTurn.events().toList()

        assertEquals(reference, service.lastExisting)
        assertEquals(ManagedExecutionSessionOwnership.BORROWED, secondTurn.acquiredSession?.ownership)
        assertEquals(0, service.releaseCount)
        assertFalse(secondTurn.ownsSession)
    }

    @Test
    fun testAcceptanceToBindingPathSupportsVertexAndBedrockClaudeRoutesWithoutTraffic() = runTest {
        val routes = listOf(
            Triple(
                ProviderApi.VERTEX_ANTHROPIC_MESSAGES,
                reference,
                ManagedExecutionServiceKind.VERTEX_AGENT_ENGINE,
            ),
            Triple(
                ProviderApi.BEDROCK_ANTHROPIC_MESSAGES,
                bedrockReference(),
                ManagedExecutionServiceKind.BEDROCK_AGENT_CORE,
            ),
            Triple(
                ProviderApi.BEDROCK_CONVERSE,
                bedrockReference(),
                ManagedExecutionServiceKind.BEDROCK_AGENT_CORE,
            ),
        )

        routes.forEach { (api, session, expectedKind) ->
            val acceptance = ProviderCapabilityMatrix.acceptHostedExecution(api, "claude-4.6-sonnet")
            val service = RecordingService(session, successfulEvents(session))
            val binding = createManagedExecutionServiceBinding(acceptance, service, request, session)
            val events = binding.events().toList()

            assertEquals(expectedKind, service.serviceKind)
            assertEquals(session, binding.persistedSession)
            assertEquals(successfulEvents(session), events)
            assertEquals(1, service.acquireCount)
            assertEquals(1, service.executeCount)
            assertEquals(0, service.releaseCount)
        }
    }

    @Test
    fun testBindingFactoryRejectsKindSessionAndNonManagedAcceptanceBeforeAcquisition() {
        val vertexService = RecordingService(reference, emptyList())
        val bedrockAcceptance = HostedExecutionAcceptance.ClientManaged(
            ManagedExecutionServiceKind.BEDROCK_AGENT_CORE
        )
        assertBindingFailure(ManagedExecutionBindingFailure.SERVICE_KIND_MISMATCH) {
            createManagedExecutionServiceBinding(bedrockAcceptance, vertexService, request)
        }
        assertBindingFailure(ManagedExecutionBindingFailure.SESSION_KIND_MISMATCH) {
            createManagedExecutionServiceBinding(
                HostedExecutionAcceptance.ClientManaged(ManagedExecutionServiceKind.VERTEX_AGENT_ENGINE),
                vertexService,
                request,
                bedrockReference(),
            )
        }
        listOf<HostedExecutionAcceptance>(
            HostedExecutionAcceptance.NativeInline("code_interpreter"),
            HostedExecutionAcceptance.Unsupported(HostedExecutionAcceptanceUnsupportedReason.UNKNOWN_MODEL),
        ).forEach { acceptance ->
            assertBindingFailure(ManagedExecutionBindingFailure.ACCEPTANCE_NOT_CLIENT_MANAGED) {
                createManagedExecutionServiceBinding(acceptance, vertexService, request)
            }
        }
        assertEquals(0, vertexService.acquireCount)
    }

    private fun assertBindingFailure(
        expected: ManagedExecutionBindingFailure,
        block: () -> Unit,
    ) {
        val failure = assertFailsWith<ManagedExecutionBindingException>(block = block)
        assertEquals(expected, failure.reason)
    }

    private fun bedrockReference(): ManagedExecutionSessionReference.BedrockAgentCore =
        ManagedExecutionSessionReference.BedrockAgentCore(
            region = "eu-west-1",
            codeInterpreterIdentifier = "aws.codeinterpreter.v1",
            sessionId = "session-1",
            createdAtEpochMilliseconds = 1_700_000_000_000,
            timeoutSeconds = 900,
        )

    private fun successfulEvents(
        session: ManagedExecutionSessionReference = reference,
    ): List<ManagedExecutionEvent> = listOf(
        ManagedExecutionEvent.Request(0, "execution-1", session, "print('secret')"),
        ManagedExecutionEvent.Result(1, "execution-1", session, "done", 0),
    )

    private class RecordingService(
        private val reference: ManagedExecutionSessionReference,
        private val events: Flow<ManagedExecutionEvent>,
    ) : ManagedExecutionService {
        override val serviceKind: ManagedExecutionServiceKind =
            when (reference) {
                is ManagedExecutionSessionReference.VertexAgentEngine ->
                    ManagedExecutionServiceKind.VERTEX_AGENT_ENGINE
                is ManagedExecutionSessionReference.BedrockAgentCore ->
                    ManagedExecutionServiceKind.BEDROCK_AGENT_CORE
            }

        constructor(
            reference: ManagedExecutionSessionReference,
            events: List<ManagedExecutionEvent>,
        ) : this(reference, flowOf(*events.toTypedArray()))

        var acquireCount: Int = 0
        var executeCount: Int = 0
        var releaseCount: Int = 0
        var lastExisting: ManagedExecutionSessionReference? = null

        override suspend fun acquireSession(
            existing: ManagedExecutionSessionReference?,
        ): ManagedExecutionSession {
            acquireCount += 1
            lastExisting = existing
            return ManagedExecutionSession(
                reference = existing ?: reference,
                ownership = if (existing == null) {
                    ManagedExecutionSessionOwnership.OWNED
                } else {
                    ManagedExecutionSessionOwnership.BORROWED
                },
            )
        }

        override fun execute(
            session: ManagedExecutionSession,
            request: ManagedExecutionRequest,
        ): Flow<ManagedExecutionEvent> {
            executeCount += 1
            return events
        }

        override suspend fun releaseSession(session: ManagedExecutionSession) {
            releaseCount += 1
        }
    }
}
