package ai.koog.agents.core.dsl.extension

import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlin.collections.chunked
import kotlin.time.Instant

/**
 * WholeHistory is a concrete implementation of the HistoryCompressionStrategy
 * that encapsulates the logic for compressing entire conversation history into
 * a succinct summary (TL;DR) and composing the necessary messages to create a
 * streamlined prompt suitable for language model interactions.
 *
 * This strategy preserves all system messages as well as the first user message
 * (if presented) and memory messages (if provided) and then appends
 * tldr of the whole original history (except trailing tool calls).
 *
 * [System, User, Assistant, ToolCall1, ToolResult, ToolCall2]
 * ->
 * [System, User, Memory, TLDR(System, User, Assistant, ToolCall1, ToolResult)]
 */
public object WholeHistoryCompressionStrategy : HistoryCompressionStrategy() {
    /**
     * Compresses and adjusts the prompt for the agent's writing session by summarizing and incorporating
     * memory messages optionally.
     *
     * @param llmSession The current session of the agent which allows prompt manipulation and sending requests.
     * @param memoryMessages A list of memory messages to be optionally preserved and included in the prompt.
     */
    override suspend fun compress(
        llmSession: AIAgentLLMWriteSession,
        memoryMessages: List<Message>
    ) {
        val originalMessages = llmSession.prompt.messages
        val tldrMessages = compressPromptIntoTLDR(llmSession)
        val compressedMessages = composeMessageHistory(
            originalMessages,
            tldrMessages,
            memoryMessages,
        )
        llmSession.prompt = llmSession.prompt.withMessages { compressedMessages }
    }
}

/**
 * Compresses older conversation turns into a portable text summary while retaining the most recent
 * [preserveRecentTurns] user turns as provider-neutral messages.
 *
 * The resulting history has two tiers: a compact summary of older turns followed by a recent tail. A user turn starts
 * with a user message that does not contain tool results, so custom tool calls and their results remain together in the
 * retained tail. System messages and the first textual user message from the compressed prefix are preserved through
 * the existing history-composition machinery.
 *
 * Reapplying this strategy is incremental. A summary produced by an earlier compression is included in the next older
 * prefix and folded into the replacement summary. Both tiers discard provider response identifiers, reasoning replay
 * data, raw responses, provider-hosted execution items, and other provider-specific state. Ordinary text, user
 * attachments, and complete custom-tool exchanges retain their typed representation. This allows the compressed
 * history to be sent to a model from a different provider.
 *
 * When the history contains at most [preserveRecentTurns] user-led turns, compression is a no-op.
 *
 * @property preserveRecentTurns Minimum number of newest user-led turns to retain. Must be positive.
 */
public data class TieredHistoryCompressionStrategy(
    public val preserveRecentTurns: Int,
) : HistoryCompressionStrategy() {
    init {
        require(preserveRecentTurns > 0) { "preserveRecentTurns must be positive" }
    }

    override suspend fun compress(
        llmSession: AIAgentLLMWriteSession,
        memoryMessages: List<Message>,
    ) {
        val initialPrompt = llmSession.prompt
        val originalMessages = initialPrompt.messages
        val userTurnStarts = originalMessages.indices.filter { index -> originalMessages[index].startsUserTurn() }
        if (userTurnStarts.size <= preserveRecentTurns) return

        val retainedTailStart = userTurnStarts[userTurnStarts.size - preserveRecentTurns]
        val olderMessages = originalMessages.take(retainedTailStart)
        val retainedMessages = originalMessages.drop(retainedTailStart)
        val portableOlderMessages = olderMessages.mapNotNull(Message::toPortableHistoryMessage)
        val portableRetainedMessages = retainedMessages.mapNotNull(Message::toPortableHistoryMessage)
        val portableOlderMemoryMessages =
            memoryMessages.filter { it in olderMessages }.mapNotNull(Message::toPortableHistoryMessage)

        try {
            llmSession.prompt = initialPrompt.withMessages { portableOlderMessages }
            val portableSummaries =
                compressPromptIntoTLDR(llmSession).map { summary ->
                    val text = summary.textContent()
                    check(text.isNotBlank()) { "History compression returned an empty text summary" }
                    Message.Assistant(
                        content = text,
                        metaInfo = summary.metaInfo,
                        finishReason = summary.finishReason,
                    )
                }
            val compressedOlderMessages =
                composeMessageHistory(
                    originalMessages = portableOlderMessages,
                    tldrMessages = portableSummaries,
                    memoryMessages = portableOlderMemoryMessages,
                )
            llmSession.prompt = initialPrompt.withMessages { compressedOlderMessages + portableRetainedMessages }
        } catch (cause: Throwable) {
            llmSession.prompt = initialPrompt
            throw cause
        }
    }
}

private fun Message.startsUserTurn(): Boolean =
    this is Message.User && parts.isNotEmpty() && parts.none { it is MessagePart.Tool.Result }

private fun Message.toPortableHistoryMessage(): Message? = when (this) {
    is Message.System -> copy(
        parts = parts.map(MessagePart.Text::withoutProviderState),
        id = null,
    )

    is Message.User -> copy(
        parts = parts.map(MessagePart.RequestPart::withoutProviderState),
        id = null,
    )

    is Message.Assistant -> {
        val portableParts = parts.mapNotNull(MessagePart.ResponsePart::withoutProviderState)
        portableParts.takeIf(List<*>::isNotEmpty)?.let {
            copy(parts = portableParts, rawResponse = null, id = null)
        }
    }
}

private fun MessagePart.Text.withoutProviderState(): MessagePart.Text =
    copy(cacheControl = null, providerItemId = null, generatedFileCitations = emptyList())

private fun MessagePart.Attachment.withoutProviderState(): MessagePart.Attachment = copy(cacheControl = null)

private fun MessagePart.ContentPart.withoutProviderState(): MessagePart.ContentPart = when (this) {
    is MessagePart.Text -> withoutProviderState()
    is MessagePart.Attachment -> withoutProviderState()
}

private fun MessagePart.RequestPart.withoutProviderState(): MessagePart.RequestPart = when (this) {
    is MessagePart.Text -> withoutProviderState()
    is MessagePart.Attachment -> withoutProviderState()
    is MessagePart.Tool.Result -> copy(
        parts = parts.map(MessagePart.ContentPart::withoutProviderState),
        cacheControl = null,
        providerItemId = null,
    )
}

private fun MessagePart.ResponsePart.withoutProviderState(): MessagePart.ResponsePart? = when (this) {
    is MessagePart.Text -> withoutProviderState()
    is MessagePart.Tool.Call -> copy(cacheControl = null, providerItemId = null)
    is MessagePart.Attachment,
    is MessagePart.Reasoning,
    is MessagePart.CodeExecution,
    is MessagePart.GeneratedFile,
    is MessagePart.HostedExecution,
    -> null
}

/**
 * [WholeCompressionStrategyWithMultipleSystemMessages] is a concrete implementation of the [HistoryCompressionStrategy]
 * that handles scenarios where the conversation history contains multiple system messages.
 *
 * This strategy:
 * 1. Splits the history into blocks based on system message boundaries
 * 2. Processes each block separately to generate TL;DR summaries
 * 3. Maintains the chronological order of system messages while compressing the conversation
 * 4. Preserves memory messages only in the first block to maintain context
 *
 * [System1, User1, Assistant, ToolCall, ToolResult, System2, User2, Assistant, User3, System3, Assistant, System4 ]
 * ->
 * [System1, User1, Memory, TLDR(System1, User1, Assistant, ToolCall, ToolResult),
 * System2, User2, TLDR(System2, User2, Assistant, User3),
 * System3, Assistant, TLDR(System3, Assistant)
 * System4, TLDR(System4)]
 */
public object WholeCompressionStrategyWithMultipleSystemMessages : HistoryCompressionStrategy() {
    /**
     * Compresses and adjusts the prompt for the agent's write session by summarizing and incorporating
     * memory messages optionally.
     *
     * @param llmSession The current session of the agent which allows prompt manipulation and sending requests.
     * @param memoryMessages A list of memory messages to be optionally preserved and included in the prompt.
     */
    override suspend fun compress(
        llmSession: AIAgentLLMWriteSession,
        memoryMessages: List<Message>
    ) {
        val compressedMessages = mutableListOf<Message>()

        // Split the messages into blocks by system messages
        val messageBlocks = splitHistoryBySystemMessages(llmSession.prompt.messages)

        messageBlocks.mapIndexed { index, messageBlock ->
            llmSession.prompt = llmSession.prompt.withMessages { messageBlock }

            // Compress the current block of messages
            val tldrMessageBlock = compressPromptIntoTLDR(llmSession)

            // Compos the messages for the current block
            val compressedMessageBlock = composeMessageHistory(
                originalMessages = messageBlock,
                tldrMessages = tldrMessageBlock,
                // Add memories only to the first block
                memoryMessages = if (index == 0) memoryMessages else emptyList(),
            )
            compressedMessages.addAll(compressedMessageBlock)
        }
        llmSession.prompt = llmSession.prompt.withMessages { compressedMessages }
    }
}

/**
 * A strategy for compressing history by retaining only the last `n` messages in a session.
 *
 * This class removes all but the last `n` messages from the current prompt history and then
 * compresses the retained messages into a summary (TL;DR). It also allows integration of
 * specific memory messages back into the prompt if needed.
 *
 * @property n The number of most recent messages to retain during compression.
 */
public data class FromLastNMessagesHistoryCompressionStrategy(val n: Int) : HistoryCompressionStrategy() {
    /**
     * Compresses the conversation history by retaining the last N messages, generating a summary,
     * and composing the resulting prompt with the necessary messages.
     *
     * @param llmSession the session in which the language model operates, providing functionalities
     *        to manage prompts and request responses.
     * @param memoryMessages a list of messages representing historical memory to be optionally retained
     *        if preserveMemory is true.
     */
    override suspend fun compress(
        llmSession: AIAgentLLMWriteSession,
        memoryMessages: List<Message>
    ) {
        val originalMessages = llmSession.prompt.messages
        llmSession.leaveLastNMessages(n)
        val tldrMessages = compressPromptIntoTLDR(llmSession)
        val compressedMessages = composeMessageHistory(
            originalMessages,
            tldrMessages,
            memoryMessages,
        )
        llmSession.prompt = llmSession.prompt.withMessages { compressedMessages }
    }
}

/**
 * A strategy for compressing message histories using a specified timestamp as a reference point.
 * This strategy removes messages that occurred before a given timestamp and creates a summarized
 * context for further interactions.
 *
 * This strategy preserves all system messages as well as the first user message
 * (if presented) and memory messages (if provided) and then appends
 * tldr of the subset of messages starting from the provided timestamp (except trailing tool calls).
 *
 * @param timestamp The timestamp indicating the earliest point to retain messages from.
 */
public data class FromTimestampHistoryCompressionStrategy(val timestamp: Instant) : HistoryCompressionStrategy() {
    /**
     * Compresses the conversation history by retaining the messages from the timestamp, generating a summary,
     * and composing the resulting prompt with the necessary messages.
     *
     * @param llmSession The session used for writing and managing the large language model's state.
     * @param memoryMessages The list of memory messages that should be used or referenced during compression.
     */
    override suspend fun compress(
        llmSession: AIAgentLLMWriteSession,
        memoryMessages: List<Message>
    ) {
        val originalMessages = llmSession.prompt.messages
        llmSession.leaveMessagesFromTimestamp(timestamp)
        val tldrMessages = compressPromptIntoTLDR(llmSession)
        val compressedMessages = composeMessageHistory(
            originalMessages,
            tldrMessages,
            memoryMessages,
        )
        llmSession.prompt = llmSession.prompt.withMessages { compressedMessages }
    }
}

/**
 * A concrete implementation of the `HistoryCompressionStrategy` that splits the session's prompt
 * into chunks of a predefined size and generates summaries (TL;DR) for each chunk.
 *
 * This strategy preserves all system messages as well as the first user message
 * (if presented) and memory messages (if provided) and then appends
 * tldr of each chuck of messages from initial history (except trailing tool calls for each chunk).
 *
 * @property chunkSize The size of chunks into which the prompt messages are divided.
 */
public data class ChunkedHistoryCompressionStrategy(val chunkSize: Int) : HistoryCompressionStrategy() {
    /**
     * Compresses the conversation history into a summarized form (TLDR) using chunked processing.
     *
     * @param llmSession The session used to interact with the LLM, which maintains the prompt and tool states.
     * @param memoryMessages A list of memory messages to be retained if preserveMemory is true.
     */
    override suspend fun compress(
        llmSession: AIAgentLLMWriteSession,
        memoryMessages: List<Message>
    ) {
        val originalMessages = llmSession.prompt.messages
        val tldrMessageChunks = llmSession.prompt.messages.chunked(chunkSize).flatMap { messageChunk ->
            llmSession.prompt = llmSession.prompt.withMessages { messageChunk }

            compressPromptIntoTLDR(llmSession)
        }

        val compressedMessages = composeMessageHistory(
            originalMessages,
            tldrMessageChunks,
            memoryMessages
        )

        llmSession.prompt = llmSession.prompt.withMessages { compressedMessages }
    }
}
