package ai.koog.prompt.executor.clients

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.models.ModelCatalogue
import ai.koog.prompt.models.ModelPublisher
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Wire format of native reasoning stored with an assistant turn. */
public enum class ReasoningReplayFormat {
    CHAT_COMPLETIONS, RESPONSES, GEMINI, ANTHROPIC, BEDROCK;

    private fun stamp(meta: ResponseMetaInfo, model: LLModel): ResponseMetaInfo = meta.copy(
        modelId = model.id,
        metadata = JsonObject(meta.metadata.orEmpty() + ("reasoningReplayFormat" to JsonPrimitive(name))),
    )

    /** Record the originating model and format when the response actually contains reasoning. */
    public fun record(message: Message.Assistant, model: LLModel): Message.Assistant =
        if (message.parts.any { it is MessagePart.Reasoning }) message.copy(metaInfo = stamp(message.metaInfo, model))
        else message

    /** Record the same origin in the terminal metadata used to reconstruct streamed messages. */
    public fun record(frames: Flow<StreamFrame>, model: LLModel): Flow<StreamFrame> = flow {
        var reasoning = false
        frames.collect { frame ->
            if (frame is StreamFrame.ReasoningComplete || frame is StreamFrame.ReasoningDelta) reasoning = true
            emit(if (reasoning && frame is StreamFrame.End) frame.copy(metaInfo = stamp(frame.metaInfo, model)) else frame)
        }
    }

    /**
     * Select reasoning for this request without modifying stored history. On older turns, explicit model IDs,
     * signed replay blocks and Responses item IDs still constrain the destination. Ambiguous legacy values
     * retain the caller-managed contract. Summaries remain attached to their native Responses item.
     */
    public fun requestView(message: Message.Assistant, model: LLModel): Message.Assistant? {
        if (message.parts.none { it is MessagePart.Reasoning }) return message
        val origin = (message.metaInfo.metadata?.get("reasoningReplayFormat") as? JsonPrimitive)?.content
        val sourceModel = message.metaInfo.modelId
        val parts = message.parts.filter { part ->
            part !is MessagePart.Reasoning || accepts(part, origin, sourceModel, model)
        }
        return when {
            parts.size == message.parts.size -> message
            parts.isEmpty() -> null
            else -> message.copy(parts = parts)
        }
    }

    private fun accepts(part: MessagePart.Reasoning, origin: String?, sourceId: String?, model: LLModel): Boolean {
        // A provider may expose a summary without disclosing any native reasoning content.
        // Only Responses can represent that item, and it needs the provider's original item ID.
        val summaryOnly = !part.summary.isNullOrEmpty() && part.content.isEmpty() && part.encrypted == null && part.replay.isEmpty()
        if (summaryOnly && (this != RESPONSES || part.providerItemId.isNullOrBlank() || !model.supports(LLMCapability.Thinking))) {
            return false
        }
        if (origin == null) {
            // These native shapes identify a wire family even when only the source model was stored.
            val legacyFormat = when {
                part.replay.isNotEmpty() -> usesClaudeBlocks
                part.providerItemId?.startsWith("rs_") == true -> this == RESPONSES
                else -> null
            }
            if (legacyFormat == false) return false
            if (sourceId == null) return legacyFormat == null || model.supports(LLMCapability.Thinking)
        }

        // An endpoint can return native reasoning without advertising configurable thinking.
        // Replay observed content only on its recorded model and wire format.
        if (origin == name && sourceId == model.id) {
            if (this == CHAT_COMPLETIONS && part.content.isNotEmpty()) return true
            if (this == BEDROCK && (part.content.isNotEmpty() || part.replay.isNotEmpty())) return true
        }
        if (!model.supports(LLMCapability.Thinking) || sourceId == null) return false
        if (sourceId == model.id && (origin == name || origin == null)) return true
        val source = semanticClaudeId(sourceId) ?: return false
        return usesClaudeBlocks && (origin == null || origin == ANTHROPIC.name || origin == BEDROCK.name) &&
            source == semanticClaudeId(model.id)
    }

    private val usesClaudeBlocks: Boolean get() = this == ANTHROPIC || this == BEDROCK

    private fun semanticClaudeId(deploymentId: String): String? {
        val modelId = deploymentId.substringAfter("anthropic.")
        return ModelCatalogue.find(modelId)?.takeIf { it.publisher == ModelPublisher.ANTHROPIC }?.id
    }
}
