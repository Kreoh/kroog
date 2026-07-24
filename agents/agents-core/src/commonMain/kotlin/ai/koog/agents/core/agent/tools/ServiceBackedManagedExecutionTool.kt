package ai.koog.agents.core.agent.tools

import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.prompt.executor.managed.ManagedExecutionEvent
import ai.koog.prompt.executor.managed.ManagedExecutionRequest
import ai.koog.prompt.executor.managed.ManagedExecutionService
import ai.koog.prompt.executor.managed.ManagedExecutionSession
import ai.koog.prompt.executor.managed.ManagedExecutionSessionOwnership
import ai.koog.prompt.executor.managed.ManagedExecutionSessionReference
import ai.koog.prompt.message.CLIENT_MANAGED_EXECUTION_TOOL_NAME
import ai.koog.prompt.provider.HostedExecutionAcceptance
import ai.koog.prompt.provider.ManagedExecutionServiceKind
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.typeToken
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Explicit lifecycle choice for sessions owned by a service-backed managed execution. */
@Serializable
public enum class ManagedExecutionReleasePolicy {
    RELEASE_OWNED_AFTER_EXECUTION,
    RETAIN_OWNED,
}

/** Ordinary custom-tool result retained in prompt history after managed event presentation. */
@Serializable
public data class ManagedExecutionToolResult(
    val executionId: String,
    val session: ManagedExecutionSessionReference,
    val output: String? = null,
    val exitCode: Int? = null,
)

/** Stable pre-acquisition failure category for a service-backed managed execution binding. */
public enum class ManagedExecutionBindingFailure {
    ALREADY_COLLECTED,
    ACCEPTANCE_NOT_CLIENT_MANAGED,
    SERVICE_KIND_MISMATCH,
    SESSION_KIND_MISMATCH,
}

/** Typed rejection raised before a managed service acquires a provider session. */
public class ManagedExecutionBindingException(
    public val reason: ManagedExecutionBindingFailure,
) : IllegalStateException(
    when (reason) {
        ManagedExecutionBindingFailure.ALREADY_COLLECTED ->
            "A managed execution binding can be collected only once"
        ManagedExecutionBindingFailure.ACCEPTANCE_NOT_CLIENT_MANAGED ->
            "Hosted execution acceptance does not select a client-managed service"
        ManagedExecutionBindingFailure.SERVICE_KIND_MISMATCH ->
            "Hosted execution acceptance and injected service identify different managed services"
        ManagedExecutionBindingFailure.SESSION_KIND_MISMATCH ->
            "Persisted session and injected service identify different managed services"
    }
)

/**
 * One service-bound execution with deterministic owned or borrowed session handling.
 *
 * A binding is single-use. Acquisition starts only when [events] is collected. Borrowed sessions are never passed to
 * [ManagedExecutionService.releaseSession], including on failure or cancellation.
 */
public class ManagedExecutionServiceBinding(
    private val service: ManagedExecutionService,
    public val request: ManagedExecutionRequest,
    public val persistedSession: ManagedExecutionSessionReference? = null,
    public val releasePolicy: ManagedExecutionReleasePolicy =
        ManagedExecutionReleasePolicy.RELEASE_OWNED_AFTER_EXECUTION,
) {
    private val collectionGuard = Mutex()

    /** Session returned by the service after collection starts. */
    public var acquiredSession: ManagedExecutionSession? = null
        private set

    /** True only when the acquired provider resource belongs to this binding. */
    public val ownsSession: Boolean
        get() = acquiredSession?.ownership == ManagedExecutionSessionOwnership.OWNED

    init {
        if (persistedSession != null && persistedSession.serviceKind != service.serviceKind) {
            throw ManagedExecutionBindingException(ManagedExecutionBindingFailure.SESSION_KIND_MISMATCH)
        }
    }

    /** Cold, ordered event stream for this single binding. */
    public fun events(): Flow<ManagedExecutionEvent> = flow {
        if (!collectionGuard.tryLock()) {
            throw ManagedExecutionBindingException(ManagedExecutionBindingFailure.ALREADY_COLLECTED)
        }

        val session = service.acquireSession(persistedSession)
        acquiredSession = session
        try {
            emitAll(service.execute(session, request))
        } finally {
            releaseOwnedSessionOnce(session)
        }
    }

    private suspend fun releaseOwnedSessionOnce(session: ManagedExecutionSession) {
        if (
            session.ownership != ManagedExecutionSessionOwnership.OWNED ||
            releasePolicy != ManagedExecutionReleasePolicy.RELEASE_OWNED_AFTER_EXECUTION
        ) {
            return
        }
        withContext(NonCancellable) {
            service.releaseSession(session)
        }
    }
}

/**
 * Creates the provider-free binding selected by an authoritative hosted-execution acceptance.
 *
 * The caller injects the service and request. This factory creates no clients and reads no credentials.
 */
public fun createManagedExecutionServiceBinding(
    acceptance: HostedExecutionAcceptance,
    service: ManagedExecutionService,
    request: ManagedExecutionRequest,
    persistedSession: ManagedExecutionSessionReference? = null,
    releasePolicy: ManagedExecutionReleasePolicy =
        ManagedExecutionReleasePolicy.RELEASE_OWNED_AFTER_EXECUTION,
): ManagedExecutionServiceBinding {
    val clientManaged = acceptance as? HostedExecutionAcceptance.ClientManaged
        ?: throw ManagedExecutionBindingException(ManagedExecutionBindingFailure.ACCEPTANCE_NOT_CLIENT_MANAGED)
    if (clientManaged.serviceKind != service.serviceKind) {
        throw ManagedExecutionBindingException(ManagedExecutionBindingFailure.SERVICE_KIND_MISMATCH)
    }
    return ManagedExecutionServiceBinding(
        service = service,
        request = request,
        persistedSession = persistedSession,
        releasePolicy = releasePolicy,
    )
}

private val ManagedExecutionSessionReference.serviceKind: ManagedExecutionServiceKind
    get() = when (this) {
        is ManagedExecutionSessionReference.VertexAgentEngine -> ManagedExecutionServiceKind.VERTEX_AGENT_ENGINE
        is ManagedExecutionSessionReference.BedrockAgentCore -> ManagedExecutionServiceKind.BEDROCK_AGENT_CORE
    }

/**
 * Concrete ordinary custom tool backed by an injected [ManagedExecutionService].
 *
 * Provider clients and credentials remain outside this type. Each invocation creates one binding, executes once, and
 * returns exactly one [ManagedExecutionToolResult] after the inherited event protocol validation succeeds.
 */
public class ServiceBackedManagedExecutionTool(
    private val service: ManagedExecutionService,
    private val persistedSession: ManagedExecutionSessionReference? = null,
    private val releasePolicy: ManagedExecutionReleasePolicy =
        ManagedExecutionReleasePolicy.RELEASE_OWNED_AFTER_EXECUTION,
    description: String = "Executes code in a client-managed provider session.",
) : ManagedExecutionTool<ManagedExecutionRequest, ManagedExecutionToolResult>(
    argsType = typeToken<ManagedExecutionRequest>(),
    resultType = typeToken<ManagedExecutionToolResult>(),
    name = CLIENT_MANAGED_EXECUTION_TOOL_NAME,
    description = description,
) {
    /** Most recently acquired session, exposed without provider-specific service semantics. */
    public var acquiredSession: ManagedExecutionSession? = null
        private set

    private var activeBinding: ManagedExecutionServiceBinding? = null

    override fun executeStreaming(
        args: ManagedExecutionRequest,
        metadata: ToolCallMetadata,
    ): Flow<ManagedExecutionEvent> {
        val binding = ManagedExecutionServiceBinding(
            service = service,
            request = args,
            persistedSession = persistedSession,
            releasePolicy = releasePolicy,
        )
        activeBinding = binding
        return flow {
            try {
                emitAll(binding.events())
            } finally {
                acquiredSession = binding.acquiredSession
                if (activeBinding === binding) {
                    activeBinding = null
                }
            }
        }
    }

    override fun decodeResult(result: ManagedExecutionEvent.Result): ManagedExecutionToolResult =
        ManagedExecutionToolResult(
            executionId = result.executionId,
            session = result.session,
            output = result.output,
            exitCode = result.exitCode,
        )

    override fun encodeResultToString(
        result: ManagedExecutionToolResult,
        serializer: JSONSerializer,
    ): String = result.output.orEmpty()
}
