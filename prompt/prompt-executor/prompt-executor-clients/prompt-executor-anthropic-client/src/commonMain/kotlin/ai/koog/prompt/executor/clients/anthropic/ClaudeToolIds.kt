package ai.koog.prompt.executor.clients.anthropic

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.InternalLLMClientApi
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

private val claudeToolId = Regex("[a-zA-Z0-9_-]+")

/** Translate foreign call IDs and their corresponding results in the outgoing Claude request only. */
@InternalLLMClientApi
public fun Prompt.withClaudeToolIds(maxLength: Int = Int.MAX_VALUE): Prompt {
    val idCounts = messages.asSequence().flatMap { it.parts }.filterIsInstance<MessagePart.Tool>()
        .map { part -> when (part) {
            is MessagePart.Tool.Call -> part.id
            is MessagePart.Tool.Result -> part.id
        } }
        .groupingBy { it }.eachCount()
    val reserved = idCounts.keys.filterNotNullTo(mutableSetOf())
    val repaired = if (null in idCounts) repairSingleToolPairs(reserved, idCounts) else this
    val invalid = reserved.filter { it.length > maxLength || !claudeToolId.matches(it) }
    if (invalid.isEmpty()) return repaired

    // Reserve every original ID before allocating replacements, including valid IDs later in the history.
    val aliases = invalid.associateWith { id ->
        val base = "toolu_replay_${id.hashCode().toUInt().toString(16)}"
        reserveId(base, reserved)
    }
    return repaired.copy(messages = repaired.messages.map { message ->
        when (message) {
            is Message.System -> message
            is Message.Assistant -> message.copy(parts = message.parts.map { part ->
                if (part is MessagePart.Tool.Call) aliases[part.id]?.let { part.copy(id = it) } ?: part else part
            })
            is Message.User -> message.copy(parts = message.parts.map { part ->
                if (part is MessagePart.Tool.Result) aliases[part.id]?.let { part.copy(id = it) } ?: part else part
            })
        }
    })
}

private fun reserveId(base: String, reserved: MutableSet<String>): String {
    var candidate = base
    var suffix = 0
    while (!reserved.add(candidate)) candidate = "${base}_${++suffix}"
    return candidate
}

private fun Prompt.repairSingleToolPairs(reserved: MutableSet<String>, idCounts: Map<String?, Int>): Prompt {
    val turns = messages.toMutableList()
    for ((index, turn) in messages.withIndex()) {
        val assistant = turn as? Message.Assistant ?: continue
        val call = assistant.parts.filterIsInstance<MessagePart.Tool.Call>().singleOrNull() ?: continue
        val user = messages.getOrNull(index + 1) as? Message.User ?: continue
        val result = user.parts.filterIsInstance<MessagePart.Tool.Result>().singleOrNull() ?: continue
        if (call.tool != result.tool || (call.id != null && result.id != null)) continue
        // A single known ID is usable only if it belongs exclusively to this pair.
        val knownId = call.id ?: result.id
        if (knownId != null && idCounts[knownId] != 1) continue
        // Split results from another call before the next assistant also make this batch ambiguous.
        val laterTurns = messages.subList(index + 2, messages.size).asSequence().takeWhile { it !is Message.Assistant }
        if (laterTurns.any { next -> next.parts.any { it is MessagePart.Tool.Result } }) continue

        val id = knownId ?: reserveId("toolu_replay_turn_$index", reserved)
        turns[index] = assistant.copy(parts = assistant.parts.map { if (it === call) call.copy(id = id) else it })
        turns[index + 1] = user.copy(parts = user.parts.map { if (it === result) result.copy(id = id) else it })
    }
    return copy(messages = turns)
}
