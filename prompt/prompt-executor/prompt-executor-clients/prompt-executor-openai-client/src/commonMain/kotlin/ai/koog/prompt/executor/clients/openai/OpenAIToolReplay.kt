package ai.koog.prompt.executor.clients.openai

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun Prompt.forOpenAIToolReplay(): Prompt {
    val ordered = inToolCallOrder()
    var updated: MutableList<Message>? = null
    for ((index, turn) in ordered.messages.withIndex()) {
        if (turn !is Message.User || turn.parts.none { it is MessagePart.Tool.Result && it.needsStatusEnvelope() }) continue
        val target = updated ?: ordered.messages.toMutableList().also { updated = it }
        target[index] = turn.copy(
            parts = turn.parts.map { part ->
                if (part is MessagePart.Tool.Result && part.needsStatusEnvelope()) part.withStatusEnvelope() else part
            }
        )
    }
    return updated?.let { ordered.copy(messages = it) } ?: ordered
}

// Some models associate parallel outputs by position even when call IDs are present.
// Reorder only complete, unambiguous batches in the outgoing request.
private fun Prompt.inToolCallOrder(): Prompt {
    var reordered: MutableList<Message>? = null
    for ((index, message) in messages.withIndex()) {
        if (message !is Message.Assistant) continue
        val calls = message.parts.filterIsInstance<MessagePart.Tool.Call>()
        if (calls.size < 2) continue
        val ids = calls.mapNotNull { it.id }
        if (ids.toSet().size != calls.size) continue

        val results = mutableListOf<MessagePart.Tool.Result>()
        var end = index + 1
        while (results.size < ids.size) {
            val turn = messages.getOrNull(end) as? Message.User ?: break
            val batch = turn.parts.filterIsInstance<MessagePart.Tool.Result>()
            if (batch.isEmpty()) break
            results.addAll(batch)
            end++
            // Both serializers send results before other user content. Stop at that content boundary.
            if (batch.size != turn.parts.size) break
        }
        if (results.size != ids.size || results.map { it.id } == ids) continue
        val byId = results.associateBy { it.id }
        if (!byId.keys.containsAll(ids)) continue

        val ordered = ids.map { byId.getValue(it) }
        val target = reordered ?: messages.toMutableList().also { reordered = it }
        var offset = 0
        for (i in index + 1 until end) {
            val turn = messages[i] as Message.User
            target[i] = turn.copy(
                parts = turn.parts.map { part ->
                    if (part is MessagePart.Tool.Result) ordered[offset++] else part
                }
            )
        }
    }
    return reordered?.let { copy(messages = it) } ?: this
}

// Neither OpenAI endpoint has a failure flag on a custom function result.
private fun MessagePart.Tool.Result.needsStatusEnvelope(): Boolean {
    if (isError) return true
    val firstText = parts.firstNotNullOfOrNull { (it as? MessagePart.Text)?.text } ?: return false
    // Escape a literal successful output that starts with one of our own wire markers.
    return firstText.startsWith("{\"status\":\"error\"") || firstText.startsWith("{\"status\":\"success\"")
}

private fun MessagePart.Tool.Result.withStatusEnvelope(): MessagePart.Tool.Result {
    val textOnly = parts.all { it is MessagePart.Text }
    val envelope = buildJsonObject {
        put("status", if (isError) "error" else "success")
        if (textOnly) put("output", parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text })
    }
    val marker = MessagePart.Text(envelope.toString())
    return copy(parts = if (textOnly) listOf(marker) else listOf(marker) + parts)
}
