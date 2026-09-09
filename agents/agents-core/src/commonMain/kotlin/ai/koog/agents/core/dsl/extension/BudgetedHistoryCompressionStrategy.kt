package ai.koog.agents.core.dsl.extension

import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.prompt.Prompts.summariseForContinuation
import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.tokenizer.PromptTokenizer

internal class BudgetedHistoryCompressionStrategy(
    private val preserveRecentTurns: Int,
    private val maxInputTokens: Int,
    private val tokenizer: PromptTokenizer,
    private val summaryParams: LLMParams?,
    private val onCompression: ((Int, Int, Int) -> Unit)?,
) : HistoryCompressionStrategy() {
    init {
        require(preserveRecentTurns >= 0) { "preserveRecentTurns must not be negative" }
        require(maxInputTokens > 0) { "maxInputTokens must be positive" }
    }

    override suspend fun compress(llmSession: AIAgentLLMWriteSession, memoryMessages: List<Message>) {
        val original = llmSession.prompt
        val messages = original.messages.mapNotNull(Message::toPortableHistoryMessage)
        val system = messages.filterIsInstance<Message.System>()
        val memory = memoryMessages.mapNotNull(Message::toPortableHistoryMessage).filter { it !is Message.System }.distinct()
        val fixed = system + memory
        val turns = messages.indices.filter { index -> messages[index].startsUserTurn() }
        var retained = minOf(preserveRecentTurns, (turns.size - 1).coerceAtLeast(0))
        var tailStart = if (retained == 0) messages.size else turns[turns.size - retained]
        val before = tokenizer.tokenCountFor(original)
        val params = (summaryParams ?: original.params).withoutSavedContainer()
            .copy(maxTokens = minOf(summaryParams?.maxTokens ?: 1024, (maxInputTokens / 4).coerceAtLeast(1)))
        fun count(content: List<Message>): Int = tokenizer.tokenCountFor(original.copy(messages = content))
        check(count(fixed) < maxInputTokens) { "System and preserved memory exceed the compression input budget" }

        suspend fun summarise(records: List<Message>): Message.Assistant? {
            val instruction = Prompt.build("history-summary", clock = llmSession.clock) {
                user { summariseForContinuation(params.maxTokens) }
            }.messages.single()
            var carry: Message.Assistant? = null
            val batch = mutableListOf<Message>()
            fun requestMessages(): List<Message> = fixed + listOfNotNull(carry) + batch + instruction
            suspend fun flush() {
                if (batch.isEmpty()) return
                check(count(requestMessages()) <= maxInputTokens) { "Summary request exceeds the compression input budget" }
                llmSession.prompt = original.copy(params = params, messages = requestMessages())
                val response = llmSession.requestLLMWithoutTools()
                val text = response.textContent().removeHandoverFrame()
                check(text.isNotBlank()) {
                    "History compression returned an empty text summary " +
                        "(finishReason=" + response.finishReason + ", outputTokens=" + response.metaInfo.outputTokensCount + ")"
                }
                carry = Message.Assistant(content = HANDOVER_FRAME + text, metaInfo = response.metaInfo)
                batch.clear()
            }
            for (record in records.filter { it !is Message.System && it !in memory }) {
                val parts = record.parts.flatMap { part ->
                    when (part) {
                        is MessagePart.Text -> listOf("Historical ${record.role} text" to part.copy(text = part.text.removeHandoverFrame()))
                        is MessagePart.Tool.Call -> listOf("Historical tool call ${part.tool} (${part.id})" to MessagePart.Text(part.args))
                        is MessagePart.Tool.Result -> part.parts.map {
                            "Historical tool result ${part.tool} (${part.id}), status=${if (part.isError) "failed" else "succeeded"}" to it
                        }
                        is MessagePart.Attachment -> listOf("Historical ${record.role} attachment" to part)
                        else -> emptyList()
                    }
                }
                for ((source, part) in parts) {
                    val meta = ai.koog.prompt.message.RequestMetaInfo(timestamp = record.metaInfo.timestamp)
                    fun textMessage(text: String): Message.User = Message.User("$source (fragment of the same historical item):\n$text", meta)
                    if (part is MessagePart.Text) {
                        var remaining = part.text
                        while (remaining.isNotEmpty()) {
                            val message = textMessage(remaining)
                            batch += message
                            if (count(requestMessages()) <= maxInputTokens) break
                            batch.removeAt(batch.lastIndex)
                            if (batch.isNotEmpty()) {
                                flush()
                                continue
                            }
                            var low = 0
                            var high = remaining.length
                            while (low < high) {
                                val middle = low + (high - low + 1) / 2
                                val candidate = textMessage(remaining.take(middle))
                                if (count(fixed + listOfNotNull(carry) + candidate + instruction) <= maxInputTokens) {
                                    low = middle
                                } else {
                                    high = middle - 1
                                }
                            }
                            if (low < remaining.length && low > 0 && remaining[low - 1].isHighSurrogate()) low -= 1
                            check(low > 0) { "Summary and system messages leave no room for history within the compression input budget" }
                            batch += textMessage(remaining.take(low))
                            remaining = remaining.drop(low)
                            flush()
                        }
                    } else {
                        val message = Message.User(parts = listOf(MessagePart.Text("$source:\n"), part), metaInfo = meta)
                        batch += message
                        if (count(requestMessages()) > maxInputTokens) {
                            batch.removeAt(batch.lastIndex)
                            flush()
                            batch += message
                            check(count(requestMessages()) <= maxInputTokens) { "An attachment exceeds the compression input budget" }
                        }
                    }
                }
            }
            flush()
            return carry
        }

        try {
            var summary = summarise(messages.take(tailStart))
            while (true) {
                val tail = messages.drop(tailStart).filter { it !is Message.System }
                val result = system + memory.filter { it !in tail } + listOfNotNull(summary) + tail
                val after = count(result)
                if (after <= maxInputTokens) {
                    llmSession.prompt = original.copy(params = original.params.withoutSavedContainer(), messages = result)
                    onCompression?.invoke(before, after, retained)
                    return
                }
                check(retained > 0) { "The summary and system messages exceed the compression input budget" }
                retained -= 1
                val nextStart = if (retained == 0) messages.size else turns[turns.size - retained]
                summary = summarise(listOfNotNull(summary) + messages.subList(tailStart, nextStart))
                tailStart = nextStart
            }
        } catch (cause: Throwable) {
            llmSession.prompt = original
            throw cause
        }
    }
}
