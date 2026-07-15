package ai.koog.prompt.streaming

import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.experimental.ExperimentalTypeInference

/**
 * Create a [Flow] of [StreamFrame.TextDelta] objects from a list of [String] content.
 */
public fun streamFrameFlowOf(vararg content: String): Flow<StreamFrame.TextDelta> =
    content.asFlow().map(StreamFrame::TextDelta)

/**
 * Builds a [Flow] of [StreamFrame] objects.
 *
 * @see emitTextDelta for emitting a [StreamFrame.TextDelta] object.
 * @see emitTextComplete for emitting a [StreamFrame.TextComplete] object.
 * @see emitReasoningDelta for emitting a [StreamFrame.ReasoningDelta] object.
 * @see emitReasoningComplete for emitting a [StreamFrame.ReasoningComplete] object.
 * @see emitToolCallDelta for emitting a [StreamFrame.ToolCallDelta] object.
 * @see emitToolCallComplete for emitting a [StreamFrame.ToolCallComplete] object.

 * @see emitEnd for emitting a [StreamFrame.End] object.
 */
@OptIn(ExperimentalTypeInference::class)
public fun streamFrameFlow(@BuilderInference block: suspend FlowCollector<StreamFrame>.() -> Unit): Flow<StreamFrame> =
    flow(block)

/**
 * Emits a [StreamFrame.TextDelta] with the given [text].
 */
public suspend fun FlowCollector<StreamFrame>.emitTextDelta(text: String, index: Int? = null): Unit =
    emit(StreamFrame.TextDelta(text, index))

/**
 * Emits a [StreamFrame.TextComplete] with the given [text].
 */
public suspend fun FlowCollector<StreamFrame>.emitTextComplete(text: String, index: Int? = null): Unit =
    emit(StreamFrame.TextComplete(text, index))

/**
 * Emits a [StreamFrame.ReasoningDelta] with the given [text] and [summary].
 */
public suspend fun FlowCollector<StreamFrame>.emitReasoningDelta(
    id: String? = null,
    text: String? = null,
    summary: String? = null,
    index: Int? = null
): Unit =
    emit(StreamFrame.ReasoningDelta(id, text, summary, index))

/**
 * Emits a [StreamFrame.ReasoningComplete] with the given [text].
 */
public suspend fun FlowCollector<StreamFrame>.emitReasoningComplete(
    id: String? = null,
    text: String,
    summary: String? = null,
    encrypted: String? = null,
    index: Int? = null
): Unit =
    emitReasoningComplete(id, listOf(text), summary?.let { listOf(it) }, encrypted, index)

/**
 * Emits a [StreamFrame.ReasoningComplete] with the given [text].
 */
public suspend fun FlowCollector<StreamFrame>.emitReasoningComplete(
    id: String? = null,
    text: List<String>,
    summary: List<String>? = null,
    encrypted: String? = null,
    index: Int? = null
): Unit =
    emit(StreamFrame.ReasoningComplete(id, text, summary, encrypted, index))

/**
 * Emits a [StreamFrame.End] with the given [finishReason].
 */
public suspend fun FlowCollector<StreamFrame>.emitEnd(
    finishReason: String? = null,
    metaInfo: ResponseMetaInfo? = null
): Unit =
    emit(StreamFrame.End(finishReason, metaInfo ?: ResponseMetaInfo.Empty))

/**
 * Emits a [StreamFrame.ToolCallDelta] with the given [id], [name] and [content].
 */
public suspend fun FlowCollector<StreamFrame>.emitToolCallDelta(
    id: String?,
    name: String?,
    content: String?,
    index: Int? = null
): Unit =
    emit(StreamFrame.ToolCallDelta(id, name, content, index))

/**
 * Emits a [StreamFrame.ToolCallComplete] with the given [id], [name] and [content].
 */
public suspend fun FlowCollector<StreamFrame>.emitToolCallComplete(
    id: String?,
    name: String,
    content: String,
    index: Int? = null
): Unit =
    emit(StreamFrame.ToolCallComplete(id, name, content, index))

/**
 * Builds a [Flow] of [StreamFrame] objects.
 * Should be used only in case model does not produce completion events.
 */
public fun buildStreamFrameFlow(block: suspend StreamFrameFlowBuilder.() -> Unit): Flow<StreamFrame> =
    channelFlow {
        val collector = FlowCollector<StreamFrame> { frame -> send(frame) }
        val builder = StreamFrameFlowBuilder(collector)
        block(builder)
    }

/**
 * Represents a wrapper around a [FlowCollector] that provides methods for emitting [StreamFrame] objects.
 *
 * This is mainly used for combining chunked tool calls and only emit completed tool calls.
 *
 * @property flowCollector The underlying [FlowCollector] used for emitting [StreamFrame] objects.
 */
@OptIn(ExperimentalAtomicApi::class)
public class StreamFrameFlowBuilder(
    private val flowCollector: FlowCollector<StreamFrame>,
) {

    private val stateMutex = Mutex()
    private val pendingToolCalls = mutableListOf<PendingToolCall>()
    private val pendingTextRef = AtomicReference<PendingText?>(null)
    private val pendingReasoningRef = AtomicReference<PendingReasoning?>(null)

    /**
     * Emits a [StreamFrame.TextDelta] with the given [text].
     */
    public suspend fun emitTextDelta(text: String, index: Int? = null) {
        withStateLock {
            tryEmitPendingToolCallsLocked()
            tryEmitPendingReasoningLocked()
            val previous: PendingText? = pendingTextRef.load()
            if (previous == null) {
                pendingTextRef.store(PendingText(textDelta = text, index = index))
            } else {
                pendingTextRef.store(previous.appendTextDelta(text, index))
            }
            flowCollector.emitTextDelta(text, index)
        }
    }

    /**
     * Emits a [StreamFrame.ReasoningDelta] with the given [text].
     */
    public suspend fun emitReasoningDelta(id: String? = null, text: String? = null, summary: String? = null, index: Int? = null) {
        withStateLock {
            tryEmitPendingToolCallsLocked()
            tryEmitPendingTextLocked()
            val previous: PendingReasoning? = pendingReasoningRef.load()
            if (previous == null) {
                pendingReasoningRef.store(
                    PendingReasoning(id = id, textDelta = text, summaryDelta = summary, index = index)
                )
            } else if (id != previous.id) {
                tryEmitPendingReasoningLocked()
                pendingReasoningRef.store(
                    PendingReasoning(id = id, textDelta = text, summaryDelta = summary, index = index)
                )
            } else {
                pendingReasoningRef.store(previous.appendDelta(id, text, summary, index))
            }
            flowCollector.emitReasoningDelta(id, text, summary, index)
        }
    }

    /**
     * Emits a [StreamFrame.End] with the given [finishReason].
     */
    public suspend fun emitEnd(finishReason: String? = null, metaInfo: ResponseMetaInfo? = null) {
        withStateLock {
            tryEmitPendingToolCallsLocked()
            tryEmitPendingTextLocked()
            tryEmitPendingReasoningLocked()
            flowCollector.emitEnd(finishReason, metaInfo)
        }
    }

    /**
     * Updates the coroutine context to signal we're currently combining a tool call,
     * this does not emit anything yet, that happens only in [tryEmitPendingToolCall].
     *
     * @throws StreamFrameFlowBuilderError if there is
     */
    public suspend fun emitToolCallDelta(
        id: String? = null,
        name: String? = null,
        args: String? = null,
        index: Int? = null
    ) {
        withStateLock {
            val sanitizedId = id?.takeUnless { it.isBlank() }
            val update = resolvePendingToolCallUpdate(sanitizedId, name, args, index)
            tryEmitPendingTextLocked()
            tryEmitPendingReasoningLocked()
            if (update.position == null) {
                pendingToolCalls += update.pendingToolCall
            } else {
                pendingToolCalls[update.position] = update.pendingToolCall
            }
            flowCollector.emitToolCallDelta(sanitizedId, name, args, index)
        }
    }

    /**
     * Emits a [pendingTextRef] if it exists and then clears it.
     */
    public suspend fun tryEmitPendingText() {
        withStateLock { tryEmitPendingTextLocked() }
    }

    private suspend fun tryEmitPendingTextLocked() {
        val pendingText = pendingTextRef.exchange(null)
        if (pendingText != null) {
            flowCollector.emitTextComplete(
                text = pendingText.textDelta ?: "",
                index = pendingText.index
            )
        }
    }

    /**
     * Emits a [pendingReasoningRef] if it exists and then clears it.
     */
    public suspend fun tryEmitPendingReasoning() {
        withStateLock { tryEmitPendingReasoningLocked() }
    }

    private suspend fun tryEmitPendingReasoningLocked() {
        val pendingReasoning = pendingReasoningRef.exchange(null)
        if (pendingReasoning != null) {
            flowCollector.emitReasoningComplete(
                id = pendingReasoning.id,
                text = pendingReasoning.textDelta?.let { listOf(pendingReasoning.textDelta) } ?: emptyList(),
                summary = pendingReasoning.summaryDelta?.let { listOf(pendingReasoning.summaryDelta) },
                index = pendingReasoning.index
            )
        }
    }

    /**
     * Emits all pending tool calls in first-seen order and then clears them.
     */
    public suspend fun tryEmitPendingToolCall() {
        withStateLock { tryEmitPendingToolCallsLocked() }
    }

    private suspend fun tryEmitPendingToolCallsLocked() {
        val calls = pendingToolCalls.toList()
        pendingToolCalls.clear()
        calls.forEach { pendingToolCall ->
            flowCollector.emitToolCallComplete(
                id = pendingToolCall.id,
                name = pendingToolCall.name,
                content = pendingToolCall.argumentsDelta ?: "{}",
                index = pendingToolCall.index
            )
        }
    }

    private fun resolvePendingToolCallUpdate(
        id: String?,
        name: String?,
        argumentsDelta: String?,
        index: Int?
    ): PendingToolCallUpdate {
        val normalizedName = name?.takeUnless { it.isBlank() }
        val indexPosition = index?.let { value -> pendingToolCalls.indexOfFirst { it.index == value }.takeIf { it >= 0 } }
        val idPosition = id?.let { value -> pendingToolCalls.indexOfFirst { it.id == value }.takeIf { it >= 0 } }
        if (indexPosition != null && idPosition != null && indexPosition != idPosition) {
            throw IllegalArgumentException("Tool call id $id and index $index identify different pending tool calls.")
        }
        val position =
            indexPosition ?: idPosition ?: when {
                index != null || id != null -> null
                pendingToolCalls.isEmpty() -> throw StreamFrameFlowBuilderError.NoPartialToolCallToComplete()
                pendingToolCalls.size == 1 -> 0
                else -> throw IllegalStateException(
                    "Cannot append a tool-call delta without an id or index while multiple tool calls are pending."
                )
            }
        val pendingToolCall =
            if (position == null) {
                PendingToolCall(id, normalizedName ?: "", argumentsDelta, index)
            } else {
                pendingToolCalls[position].enrich(id, normalizedName, argumentsDelta, index)
            }
        return PendingToolCallUpdate(position, pendingToolCall)
    }

    private suspend fun <T> withStateLock(block: suspend () -> T): T {
        stateMutex.lock()
        return try {
            block()
        } finally {
            stateMutex.unlock()
        }
    }

    private data class PendingToolCallUpdate(
        val position: Int?,
        val pendingToolCall: PendingToolCall,
    )

    private data class PendingToolCall(
        val id: String?,
        val name: String,
        val argumentsDelta: String?,
        val index: Int?,
    ) {
        fun enrich(id: String?, name: String?, argumentsDelta: String?, index: Int?): PendingToolCall {
            if (this.id != null && id != null && this.id != id) {
                throw IllegalArgumentException("Tool call index ${this.index ?: index} has conflicting ids ${this.id} and $id.")
            }
            if (this.index != null && index != null && this.index != index) {
                throw IllegalArgumentException("Tool call id ${this.id ?: id} has conflicting indices ${this.index} and $index.")
            }
            if (this.name.isNotBlank() && name != null && this.name != name) {
                throw IllegalArgumentException("Tool call ${this.id ?: id ?: this.index ?: index} has conflicting names ${this.name} and $name.")
            }
            val newArguments =
                if (argumentsDelta == null) this.argumentsDelta else (this.argumentsDelta ?: "") + argumentsDelta
            return copy(
                id = this.id ?: id,
                name = this.name.ifBlank { name.orEmpty() },
                argumentsDelta = newArguments,
                index = this.index ?: index,
            )
        }
    }

    private data class PendingText(
        val textDelta: String?,
        val index: Int?,
    ) {
        fun appendTextDelta(textDelta: String?, index: Int?): PendingText {
            require(this.index == index)
            val newText = if (textDelta == null) this.textDelta else (this.textDelta ?: "") + textDelta
            return copy(textDelta = newText)
        }
    }

    private data class PendingReasoning(
        val id: String?,
        val textDelta: String?,
        val summaryDelta: String?,
        val index: Int?
    ) {
        fun appendDelta(id: String?, textDelta: String?, summaryDelta: String?, index: Int?): PendingReasoning {
            require(this.index == index)
            require(this.id == id)
            val newTextDelta = if (textDelta == null) this.textDelta else (this.textDelta ?: "") + textDelta
            val newSummaryDelta = if (summaryDelta == null) this.summaryDelta else (this.summaryDelta ?: "") + summaryDelta
            return copy(textDelta = newTextDelta, summaryDelta = newSummaryDelta)
        }
    }
}
