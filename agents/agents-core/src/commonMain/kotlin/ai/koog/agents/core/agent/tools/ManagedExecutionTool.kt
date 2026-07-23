package ai.koog.agents.core.agent.tools

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.core.tools.schema.defaultJsonSchemaConfig
import ai.koog.agents.core.tools.schema.getToolDescriptor
import ai.koog.prompt.executor.managed.ManagedExecutionEvent
import ai.koog.serialization.TypeToken
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.schema.generator.json.JsonSchemaConfig

/**
 * A normal custom-tool descriptor whose execution produces ordered managed-execution events.
 *
 * The environment forwards every non-terminal event to a request-scoped observer before requesting the next Flow
 * element. It holds the single terminal event until the Flow completes cleanly, then observes that terminal and
 * converts it into one ordinary tool result for the next agent turn.
 */
public abstract class ManagedExecutionTool<TArgs, TResult>(
    argsType: TypeToken,
    resultType: TypeToken,
    descriptor: ToolDescriptor,
    metadata: Map<String, String> = emptyMap(),
) : ToolBase<TArgs, TResult>(argsType, resultType, descriptor, metadata) {

    /** Convenience constructor that generates a normal custom-tool descriptor. */
    @OptIn(InternalAgentToolsApi::class)
    public constructor(
        argsType: TypeToken,
        resultType: TypeToken,
        name: String,
        description: String,
        jsonSchemaConfig: JsonSchemaConfig = defaultJsonSchemaConfig,
    ) : this(
        argsType = argsType,
        resultType = resultType,
        descriptor = getToolDescriptor(argsType, name, description, jsonSchemaConfig),
    )

    /** Returns a cold event Flow for one tool invocation. */
    public abstract fun executeStreaming(
        args: TArgs,
        metadata: ToolCallMetadata,
    ): Flow<ManagedExecutionEvent>

    /** Converts the successful terminal event into the tool's ordinary result value. */
    public abstract fun decodeResult(result: ManagedExecutionEvent.Result): TResult

    final override suspend fun execute(args: TArgs, metadata: ToolCallMetadata): TResult =
        collectExecution(args, metadata) { _, _ -> }

    internal suspend fun collectExecution(
        args: TArgs,
        metadata: ToolCallMetadata,
        observe: suspend (Long, ManagedExecutionEvent) -> Unit,
    ): TResult {
        var eventIndex = 0L
        var previousSequence: Long? = null
        var executionId: String? = null
        var session: Any? = null
        var terminal: IndexedTerminal? = null

        executeStreaming(args, metadata).collect { event ->
            terminal?.let {
                val message = if (event is ManagedExecutionEvent.Terminal) {
                    "Managed execution emitted more than one terminal event"
                } else {
                    "Managed execution emitted a non-terminal event after its terminal event"
                }
                throw ManagedExecutionProtocolException(message)
            }

            if (previousSequence != null && event.sequence <= previousSequence!!) {
                throw ManagedExecutionProtocolException(
                    "Managed execution event sequence must increase strictly"
                )
            }

            if (eventIndex == 0L) {
                if (event !is ManagedExecutionEvent.Request) {
                    throw ManagedExecutionProtocolException(
                        "Managed execution must begin with a request event"
                    )
                }
                executionId = event.executionId
                session = event.session
            } else {
                if (event.executionId != executionId) {
                    throw ManagedExecutionProtocolException(
                        "Managed execution changed execution identity within one event flow"
                    )
                }
                if (event.session != session) {
                    throw ManagedExecutionProtocolException(
                        "Managed execution changed session identity within one event flow"
                    )
                }
            }

            previousSequence = event.sequence
            if (event is ManagedExecutionEvent.Terminal) {
                terminal = IndexedTerminal(eventIndex, event)
            } else {
                observe(eventIndex, event)
            }
            eventIndex += 1
        }

        val finalEvent = terminal
            ?: throw ManagedExecutionProtocolException("Managed execution ended without a terminal event")

        currentCoroutineContext().ensureActive()
        observe(finalEvent.index, finalEvent.event)

        return when (val event = finalEvent.event) {
            is ManagedExecutionEvent.Result -> decodeResult(event)
            is ManagedExecutionEvent.Error -> throw ManagedExecutionFailedException(event)
        }
    }

    private data class IndexedTerminal(
        val index: Long,
        val event: ManagedExecutionEvent.Terminal,
    )
}

/** A typed provider failure reported by a managed-execution tool. */
public class ManagedExecutionFailedException(
    public val error: ManagedExecutionEvent.Error,
) : RuntimeException(error.message)

/** A malformed managed-execution event stream. */
public class ManagedExecutionProtocolException(message: String) : IllegalStateException(message)
