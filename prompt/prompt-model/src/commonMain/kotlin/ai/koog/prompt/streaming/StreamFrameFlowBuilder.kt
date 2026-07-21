package ai.koog.prompt.streaming

import ai.koog.prompt.message.MessagePart
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
import kotlin.jvm.JvmOverloads

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
@JvmOverloads
public suspend fun FlowCollector<StreamFrame>.emitTextDelta(
    text: String,
    index: Int? = null,
    providerItemId: String? = null,
): Unit = emit(StreamFrame.TextDelta(text, index, providerItemId))

/**
 * Emits a [StreamFrame.TextComplete] with the given [text].
 */
@JvmOverloads
public suspend fun FlowCollector<StreamFrame>.emitTextComplete(
    text: String,
    index: Int? = null,
    providerItemId: String? = null,
): Unit = emit(StreamFrame.TextComplete(text, index, providerItemId))

/**
 * Emits a [StreamFrame.ReasoningDelta] with the given [text] and [summary].
 */
@JvmOverloads
public suspend fun FlowCollector<StreamFrame>.emitReasoningDelta(
    id: String? = null,
    text: String? = null,
    summary: String? = null,
    index: Int? = null,
    providerItemId: String? = null,
): Unit =
    emit(StreamFrame.ReasoningDelta(id, text, summary, index, providerItemId))

/**
 * Emits a [StreamFrame.ReasoningComplete] with the given [text].
 */
@JvmOverloads
public suspend fun FlowCollector<StreamFrame>.emitReasoningComplete(
    id: String? = null,
    text: String,
    summary: String? = null,
    encrypted: String? = null,
    index: Int? = null,
    providerItemId: String? = null,
    replay: List<MessagePart.ReasoningReplay> = emptyList(),
): Unit =
    emitReasoningComplete(id, listOf(text), summary?.let { listOf(it) }, encrypted, index, providerItemId, replay)

/**
 * Emits a [StreamFrame.ReasoningComplete] with the given [text].
 */
@JvmOverloads
public suspend fun FlowCollector<StreamFrame>.emitReasoningComplete(
    id: String? = null,
    text: List<String>,
    summary: List<String>? = null,
    encrypted: String? = null,
    index: Int? = null,
    providerItemId: String? = null,
    replay: List<MessagePart.ReasoningReplay> = emptyList(),
): Unit =
    emit(StreamFrame.ReasoningComplete(id, text, summary, encrypted, index, providerItemId, replay))

/**
 * Emits a [StreamFrame.End] with the given [finishReason].
 */
@JvmOverloads
public suspend fun FlowCollector<StreamFrame>.emitEnd(
    finishReason: String? = null,
    metaInfo: ResponseMetaInfo? = null,
    messageId: String? = null,
): Unit =
    emit(StreamFrame.End(finishReason, metaInfo ?: ResponseMetaInfo.Empty, messageId))

/**
 * Emits a [StreamFrame.ToolCallDelta] with the given [id], [name] and [content].
 */
@JvmOverloads
public suspend fun FlowCollector<StreamFrame>.emitToolCallDelta(
    id: String?,
    name: String?,
    content: String?,
    index: Int? = null,
    providerItemId: String? = null,
): Unit =
    emit(StreamFrame.ToolCallDelta(id, name, content, index, providerItemId))

/**
 * Emits a [StreamFrame.ToolCallComplete] with the given [id], [name] and [content].
 */
@JvmOverloads
public suspend fun FlowCollector<StreamFrame>.emitToolCallComplete(
    id: String?,
    name: String,
    content: String,
    index: Int? = null,
    providerItemId: String? = null,
): Unit =
    emit(StreamFrame.ToolCallComplete(id, name, content, index, providerItemId))

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
    @JvmOverloads
    public suspend fun emitTextDelta(text: String, index: Int? = null, providerItemId: String? = null) {
        withStateLock {
            tryEmitPendingToolCallsLocked()
            tryEmitPendingReasoningLocked()
            val previous: PendingText? = pendingTextRef.load()
            if (previous == null) {
                pendingTextRef.store(PendingText(textDelta = text, index = index, providerItemId = providerItemId))
            } else if (previous.hasConflictingIdentity(providerItemId, index)) {
                tryEmitPendingTextLocked()
                pendingTextRef.store(PendingText(textDelta = text, index = index, providerItemId = providerItemId))
            } else {
                pendingTextRef.store(previous.appendTextDelta(text, index, providerItemId))
            }
            flowCollector.emitTextDelta(text, index, providerItemId)
        }
    }

    /**
     * Emits a [StreamFrame.ReasoningDelta] with the given [text].
     */
    @JvmOverloads
    public suspend fun emitReasoningDelta(
        id: String? = null,
        text: String? = null,
        summary: String? = null,
        index: Int? = null,
        providerItemId: String? = null,
    ) {
        withStateLock {
            tryEmitPendingToolCallsLocked()
            tryEmitPendingTextLocked()
            val previous: PendingReasoning? = pendingReasoningRef.load()
            if (previous == null) {
                pendingReasoningRef.store(
                    PendingReasoning(
                        id = id,
                        textDelta = text,
                        summaryDelta = summary,
                        index = index,
                        providerItemId = providerItemId,
                    )
                )
            } else if (previous.hasConflictingIdentity(id, index, providerItemId)) {
                tryEmitPendingReasoningLocked()
                pendingReasoningRef.store(
                    PendingReasoning(
                        id = id,
                        textDelta = text,
                        summaryDelta = summary,
                        index = index,
                        providerItemId = providerItemId,
                    )
                )
            } else {
                pendingReasoningRef.store(previous.appendDelta(id, text, summary, index, providerItemId))
            }
            flowCollector.emitReasoningDelta(id, text, summary, index, providerItemId)
        }
    }

    /**
     * Attaches an encrypted reasoning payload to the pending reasoning block identified by [id] or [index].
     */
    @JvmOverloads
    public suspend fun attachReasoningEncrypted(
        encrypted: String,
        id: String? = null,
        index: Int? = null,
        providerItemId: String? = null,
    ) {
        withStateLock {
            tryEmitPendingToolCallsLocked()
            tryEmitPendingTextLocked()
            val previous = pendingReasoningRef.load()
            if (previous == null) {
                pendingReasoningRef.store(
                    PendingReasoning(id = id, encrypted = encrypted, index = index, providerItemId = providerItemId)
                )
            } else if (previous.hasConflictingIdentity(id, index, providerItemId)) {
                tryEmitPendingReasoningLocked()
                pendingReasoningRef.store(
                    PendingReasoning(id = id, encrypted = encrypted, index = index, providerItemId = providerItemId)
                )
            } else {
                pendingReasoningRef.store(previous.attachEncrypted(encrypted, id, index, providerItemId))
            }
        }
    }

    /** Attaches one lossless signed or opaque replay payload to pending reasoning. */
    public suspend fun attachReasoningReplay(
        replay: MessagePart.ReasoningReplay,
        id: String? = null,
        index: Int? = null,
        providerItemId: String? = null,
    ) {
        withStateLock {
            tryEmitPendingToolCallsLocked()
            tryEmitPendingTextLocked()
            val previous = pendingReasoningRef.load()
            if (previous == null) {
                pendingReasoningRef.store(
                    PendingReasoning(
                        id = id,
                        replay = listOf(replay),
                        index = index,
                        providerItemId = providerItemId,
                    )
                )
            } else if (previous.hasConflictingIdentity(id, index, providerItemId)) {
                tryEmitPendingReasoningLocked()
                pendingReasoningRef.store(
                    PendingReasoning(
                        id = id,
                        replay = listOf(replay),
                        index = index,
                        providerItemId = providerItemId,
                    )
                )
            } else {
                pendingReasoningRef.store(previous.attachReplay(replay, id, index, providerItemId))
            }
        }
    }

    /**
     * Emits a [StreamFrame.End] with the given [finishReason].
     */
    @JvmOverloads
    public suspend fun emitEnd(
        finishReason: String? = null,
        metaInfo: ResponseMetaInfo? = null,
        messageId: String? = null,
    ) {
        withStateLock {
            tryEmitPendingToolCallsLocked()
            tryEmitPendingTextLocked()
            tryEmitPendingReasoningLocked()
            flowCollector.emitEnd(finishReason, metaInfo, messageId)
        }
    }

    /**
     * Updates the coroutine context to signal we're currently combining a tool call,
     * this does not emit anything yet, that happens only in [tryEmitPendingToolCall].
     *
     * @throws StreamFrameFlowBuilderError if there is
     */
    @JvmOverloads
    public suspend fun emitToolCallDelta(
        id: String? = null,
        name: String? = null,
        args: String? = null,
        index: Int? = null,
        providerItemId: String? = null,
    ) {
        withStateLock {
            val sanitizedId = id?.takeUnless { it.isBlank() }
            val update = resolvePendingToolCallUpdate(sanitizedId, name, args, index, providerItemId)
            tryEmitPendingTextLocked()
            tryEmitPendingReasoningLocked()
            if (update.position == null) {
                pendingToolCalls += update.pendingToolCall
            } else {
                pendingToolCalls[update.position] = update.pendingToolCall
            }
            flowCollector.emitToolCallDelta(sanitizedId, name, args, index, providerItemId)
        }
    }

    /** Emits one complete hosted execution lifecycle update after flushing pending deltas. */
    public suspend fun emitHostedExecutionUpdate(update: MessagePart.HostedExecution, index: Int? = null) {
        withStateLock {
            tryEmitPendingToolCallsLocked()
            tryEmitPendingTextLocked()
            tryEmitPendingReasoningLocked()
            flowCollector.emit(StreamFrame.HostedExecutionUpdate(update, index))
        }
    }

    /** Emits one complete generated-file update after flushing pending deltas. */
    public suspend fun emitGeneratedFile(file: MessagePart.GeneratedFile, index: Int? = null) {
        withStateLock {
            tryEmitPendingToolCallsLocked()
            tryEmitPendingTextLocked()
            tryEmitPendingReasoningLocked()
            flowCollector.emit(StreamFrame.GeneratedFileComplete(file, index))
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
                index = pendingText.index,
                providerItemId = pendingText.providerItemId,
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
                encrypted = pendingReasoning.encrypted,
                index = pendingReasoning.index,
                providerItemId = pendingReasoning.providerItemId,
                replay = pendingReasoning.replay,
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
                index = pendingToolCall.index,
                providerItemId = pendingToolCall.providerItemId,
            )
        }
    }

    private fun resolvePendingToolCallUpdate(
        id: String?,
        name: String?,
        argumentsDelta: String?,
        index: Int?,
        providerItemId: String?,
    ): PendingToolCallUpdate {
        val normalizedName = name?.takeUnless { it.isBlank() }
        val providerPosition = providerItemId?.let { value ->
            pendingToolCalls.indexOfFirst { it.providerItemId == value }.takeIf { it >= 0 }
        }
        val indexPosition = index?.let { value -> pendingToolCalls.indexOfFirst { it.index == value }.takeIf { it >= 0 } }
        val idPosition = id?.let { value ->
            val matchingPositions = pendingToolCalls.indices.filter { pendingToolCalls[it].id == value }
            val eligiblePositions =
                if (providerItemId == null) matchingPositions else matchingPositions.filter {
                    pendingToolCalls[it].providerItemId == null
                }
            eligiblePositions.singleOrNull()
        }
        if (providerPosition != null && indexPosition != null && providerPosition != indexPosition) {
            throw IllegalArgumentException(
                "Tool call provider item $providerItemId and index $index identify different pending tool calls."
            )
        }
        if (providerPosition == null && indexPosition != null && idPosition != null && indexPosition != idPosition) {
            throw IllegalArgumentException("Tool call id $id and index $index identify different pending tool calls.")
        }
        val knownPosition = providerPosition ?: indexPosition ?: idPosition
        val position =
            knownPosition ?: when {
                index != null || id != null -> null
                pendingToolCalls.isEmpty() -> throw StreamFrameFlowBuilderError.NoPartialToolCallToComplete()
                pendingToolCalls.size == 1 -> 0
                else -> throw IllegalStateException(
                    "Cannot append a tool-call delta without an id or index while multiple tool calls are pending."
                )
            }
        val pendingToolCall =
            if (position == null) {
                PendingToolCall(id, normalizedName ?: "", argumentsDelta, index, providerItemId)
            } else {
                pendingToolCalls[position].enrich(id, normalizedName, argumentsDelta, index, providerItemId)
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
        val providerItemId: String?,
    ) {
        fun enrich(
            id: String?,
            name: String?,
            argumentsDelta: String?,
            index: Int?,
            providerItemId: String?,
        ): PendingToolCall {
            if (this.id != null && id != null && this.id != id) {
                throw IllegalArgumentException("Tool call index ${this.index ?: index} has conflicting ids ${this.id} and $id.")
            }
            if (this.index != null && index != null && this.index != index) {
                throw IllegalArgumentException("Tool call id ${this.id ?: id} has conflicting indices ${this.index} and $index.")
            }
            if (this.providerItemId != null && providerItemId != null && this.providerItemId != providerItemId) {
                throw IllegalArgumentException(
                    "Tool call ${this.id ?: id ?: this.index ?: index} has conflicting provider item ids " +
                        "${this.providerItemId} and $providerItemId."
                )
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
                providerItemId = this.providerItemId ?: providerItemId,
            )
        }
    }

    private data class PendingText(
        val textDelta: String?,
        val index: Int?,
        val providerItemId: String?,
    ) {
        fun hasConflictingIdentity(providerItemId: String?, index: Int?): Boolean =
            (this.providerItemId != null && providerItemId != null && this.providerItemId != providerItemId) ||
                (this.index != null && index != null && this.index != index)

        fun appendTextDelta(textDelta: String?, index: Int?, providerItemId: String?): PendingText {
            require(!hasConflictingIdentity(providerItemId, index))
            val newText = if (textDelta == null) this.textDelta else (this.textDelta ?: "") + textDelta
            return copy(
                textDelta = newText,
                index = this.index ?: index,
                providerItemId = this.providerItemId ?: providerItemId,
            )
        }
    }

    private data class PendingReasoning(
        val id: String?,
        val textDelta: String? = null,
        val summaryDelta: String? = null,
        val encrypted: String? = null,
        val replay: List<MessagePart.ReasoningReplay> = emptyList(),
        val index: Int?,
        val providerItemId: String?,
    ) {
        fun hasConflictingIdentity(id: String?, index: Int?, providerItemId: String?): Boolean =
            (this.providerItemId != null && providerItemId != null && this.providerItemId != providerItemId) ||
                (this.id != null && id != null && this.id != id) ||
                (this.index != null && index != null && this.index != index)

        fun appendDelta(
            id: String?,
            textDelta: String?,
            summaryDelta: String?,
            index: Int?,
            providerItemId: String?,
        ): PendingReasoning {
            require(!hasConflictingIdentity(id, index, providerItemId))
            val newTextDelta = if (textDelta == null) this.textDelta else (this.textDelta ?: "") + textDelta
            val newSummaryDelta = if (summaryDelta == null) this.summaryDelta else (this.summaryDelta ?: "") + summaryDelta
            return copy(
                id = this.id ?: id,
                textDelta = newTextDelta,
                summaryDelta = newSummaryDelta,
                index = this.index ?: index,
                providerItemId = this.providerItemId ?: providerItemId,
            )
        }

        fun attachEncrypted(
            encrypted: String,
            id: String?,
            index: Int?,
            providerItemId: String?,
        ): PendingReasoning {
            require(!hasConflictingIdentity(id, index, providerItemId))
            require(this.encrypted == null || this.encrypted == encrypted) {
                "Reasoning block ${this.id ?: id ?: this.index ?: index} has conflicting encrypted payloads."
            }
            return copy(
                id = this.id ?: id,
                encrypted = encrypted,
                index = this.index ?: index,
                providerItemId = this.providerItemId ?: providerItemId,
            )
        }

        fun attachReplay(
            replay: MessagePart.ReasoningReplay,
            id: String?,
            index: Int?,
            providerItemId: String?,
        ): PendingReasoning {
            require(!hasConflictingIdentity(id, index, providerItemId))
            return copy(
                id = this.id ?: id,
                replay = this.replay + replay,
                index = this.index ?: index,
                providerItemId = this.providerItemId ?: providerItemId,
            )
        }
    }
}
