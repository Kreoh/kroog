package ai.koog.agents.core.environment

import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.prompt.executor.managed.ManagedExecutionEvent

/**
 * Request-scoped observation of one managed-execution event.
 *
 * [eventIndex] starts at zero. Once an observer receives any observation, the request has crossed the execution
 * boundary and a caller can prevent inference retries without inspecting provider-specific event types.
 */
public data class ManagedExecutionObservation(
    val toolCallId: String?,
    val toolName: String,
    val eventIndex: Long,
    val event: ManagedExecutionEvent,
)

/** Suspended sink used to preserve event ordering and downstream backpressure. */
public fun interface ManagedExecutionEventObserver {
    public suspend fun onEvent(observation: ManagedExecutionObservation)

    public companion object {
        /** Reserved [ToolCallMetadata] key for a request-scoped observer. */
        public const val MetadataKey: String = "ai.koog.managedExecutionEventObserver"
    }
}

/** Adds a request-scoped managed-execution [observer] to this tool-call metadata. */
public fun ToolCallMetadata.withManagedExecutionEventObserver(
    observer: ManagedExecutionEventObserver,
): ToolCallMetadata = this + mapOf(ManagedExecutionEventObserver.MetadataKey to observer)

/** Returns the request-scoped managed-execution observer, when one was contributed by the caller or pipeline. */
public val ToolCallMetadata.managedExecutionEventObserver: ManagedExecutionEventObserver?
    get() = get(ManagedExecutionEventObserver.MetadataKey) as? ManagedExecutionEventObserver
