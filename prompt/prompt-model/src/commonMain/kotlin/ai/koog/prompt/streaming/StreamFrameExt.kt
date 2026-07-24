@file:JvmName("StreamFrameExt")

package ai.koog.prompt.streaming

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.jvm.JvmName

private val logger = KotlinLogging.logger {}

/**
 * Identity used while accumulating stream frames.
 *
 * [ProviderItem] is stable replay metadata. [Internal] exists only while merging a stream and must never be copied to
 * a message part's `providerItemId`.
 */
public sealed interface StreamFrameMergeIdentity {
    public val value: String

    /** A stable identity supplied by the provider. */
    public data class ProviderItem(override val value: String) : StreamFrameMergeIdentity

    /** A generated file identity scoped to one provider execution or one framework-local stream file. */
    public data class GeneratedFile(
        public val executionScope: String,
        public val fileId: String,
    ) : StreamFrameMergeIdentity {
        override val value: String
            get() = "$executionScope\u0000$fileId"
    }

    /** A framework-local fallback identity. */
    public data class Internal(
        public val kind: String,
        override val value: String,
    ) : StreamFrameMergeIdentity
}

/**
 * Returns this frame's merge identity, preferring a non-blank provider item ID over an internal frame key.
 */
public fun StreamFrame.mergeIdentity(): StreamFrameMergeIdentity? {
    if (this is StreamFrame.GeneratedFileBytes || this is StreamFrame.ManagedGeneratedFileBytes) {
        val fileId = when (this) {
            is StreamFrame.GeneratedFileBytes -> fileId
            is StreamFrame.ManagedGeneratedFileBytes -> fileId
        }
        val providerFileId = when (this) {
            is StreamFrame.GeneratedFileBytes -> providerFileId
            is StreamFrame.ManagedGeneratedFileBytes -> providerFileId
        }
        val executionId = when (this) {
            is StreamFrame.GeneratedFileBytes -> executionId
            is StreamFrame.ManagedGeneratedFileBytes -> executionId
        }
        val localFileId = fileId.takeIf(String::isNotBlank) ?: return null
        val fileIdentity = providerFileId?.takeIf(String::isNotBlank) ?: localFileId
        val executionScope = executionId?.takeIf(String::isNotBlank) ?: "stream-file:$localFileId"
        return StreamFrameMergeIdentity.GeneratedFile(executionScope, fileIdentity)
    }
    if (this is StreamFrame.GeneratedFileComplete) {
        val executionScope = file.producingExecutionId?.takeIf(String::isNotBlank)
        val fileIdentity = file.providerFileId.takeIf(String::isNotBlank)
        if (executionScope != null && fileIdentity != null) {
            return StreamFrameMergeIdentity.GeneratedFile(executionScope, fileIdentity)
        }
    }
    val providerItemId = when (this) {
        is StreamFrame.TextDelta -> providerItemId
        is StreamFrame.TextComplete -> providerItemId
        is StreamFrame.ReasoningDelta -> providerItemId
        is StreamFrame.ReasoningComplete -> providerItemId
        is StreamFrame.ToolCallDelta -> providerItemId
        is StreamFrame.ToolCallComplete -> providerItemId
        is StreamFrame.CodeExecutionStart -> providerItemId
        is StreamFrame.CodeExecutionCodeDelta -> providerItemId
        is StreamFrame.CodeExecutionOutput -> providerItemId
        is StreamFrame.CodeExecutionFailure -> providerItemId
        is StreamFrame.CodeExecutionComplete -> providerItemId
        is StreamFrame.HostedExecutionUpdate -> update.providerItemId
        is StreamFrame.GeneratedFileBytes -> null
        is StreamFrame.ManagedGeneratedFileBytes -> null
        is StreamFrame.GeneratedFileComplete -> file.providerItemId
        is StreamFrame.End -> null
    }
    providerItemId?.takeIf(String::isNotBlank)?.let {
        return StreamFrameMergeIdentity.ProviderItem(it)
    }

    val internal = when (this) {
        is StreamFrame.TextDelta -> index?.let { "text-index" to it.toString() }
        is StreamFrame.TextComplete -> index?.let { "text-index" to it.toString() }
        is StreamFrame.ReasoningDelta -> id?.let { "reasoning-id" to it } ?: index?.let { "reasoning-index" to it.toString() }
        is StreamFrame.ReasoningComplete -> id?.let { "reasoning-id" to it } ?: index?.let { "reasoning-index" to it.toString() }
        is StreamFrame.ToolCallDelta -> index?.let { "tool-index" to it.toString() } ?: id?.let { "call-id" to it }
        is StreamFrame.ToolCallComplete -> index?.let { "tool-index" to it.toString() } ?: id?.let { "call-id" to it }
        is StreamFrame.CodeExecutionStart -> index?.let { "code-index" to it.toString() } ?: ("code-id" to id)
        is StreamFrame.CodeExecutionCodeDelta -> index?.let { "code-index" to it.toString() } ?: ("code-id" to id)
        is StreamFrame.CodeExecutionOutput -> index?.let { "code-index" to it.toString() } ?: ("code-id" to id)
        is StreamFrame.CodeExecutionFailure -> index?.let { "code-index" to it.toString() } ?: ("code-id" to id)
        is StreamFrame.CodeExecutionComplete -> index?.let { "code-index" to it.toString() } ?: ("code-id" to id)
        is StreamFrame.HostedExecutionUpdate ->
            update.executionId?.let { "execution-id" to it } ?: index?.let { "execution-index" to it.toString() }
        is StreamFrame.GeneratedFileBytes -> null
        is StreamFrame.ManagedGeneratedFileBytes -> null
        is StreamFrame.GeneratedFileComplete -> null
        is StreamFrame.End -> null
    }
    return internal?.let { (kind, value) -> StreamFrameMergeIdentity.Internal(kind, value) }
}

/**
 * Converts a [Message.Assistant] to a list of [StreamFrame].
 * First it emits the delta frames for each content part for each message, then complete frame with the full message content.
 */
public fun Message.Assistant.toStreamFrames(): List<StreamFrame> {
    return buildList {
        parts.forEachIndexed { index, part ->
            when (part) {
                is MessagePart.Reasoning -> {
                    part.content.forEach {
                        add(
                            StreamFrame.ReasoningDelta(
                                id = part.id,
                                text = it,
                                summary = null,
                                index = index,
                                providerItemId = part.providerItemId,
                            )
                        )
                    }

                    part.summary?.forEach {
                        add(
                            StreamFrame.ReasoningDelta(
                                id = part.id,
                                text = null,
                                summary = it,
                                index = index,
                                providerItemId = part.providerItemId,
                            )
                        )
                    }

                    add(
                        StreamFrame.ReasoningComplete(
                            id = part.id,
                            content = part.content,
                            summary = part.summary,
                            encrypted = part.encrypted,
                            index = index,
                            providerItemId = part.providerItemId,
                            replay = part.replay,
                        )
                    )
                }

                is MessagePart.CodeExecution -> {
                    add(StreamFrame.CodeExecutionStart(part.id, part.containerId, index, part.providerItemId))
                    add(StreamFrame.CodeExecutionCodeDelta(part.id, part.containerId, part.code, index, part.providerItemId))
                    part.outputs.forEach { output ->
                        add(StreamFrame.CodeExecutionOutput(part.id, part.containerId, output, index, part.providerItemId))
                    }
                    part.failure?.let { failure ->
                        add(StreamFrame.CodeExecutionFailure(part.id, part.containerId, failure, index, part.providerItemId))
                    }
                    add(
                        StreamFrame.CodeExecutionComplete(
                            id = part.id,
                            code = part.code,
                            containerId = part.containerId,
                            outputs = part.outputs,
                            failure = part.failure,
                            index = index,
                            providerItemId = part.providerItemId,
                        )
                    )
                }

                is MessagePart.HostedExecution -> {
                    add(StreamFrame.HostedExecutionUpdate(part, index))
                }

                is MessagePart.GeneratedFile -> {
                    add(StreamFrame.GeneratedFileComplete(part, index))
                }

                is MessagePart.Text -> {
                    add(StreamFrame.TextDelta(part.text, index, part.providerItemId))
                    add(
                        StreamFrame.TextComplete(
                            part.text,
                            index,
                            part.providerItemId,
                            part.generatedFileCitations,
                        )
                    )
                }

                is MessagePart.Tool.Call -> {
                    add(StreamFrame.ToolCallDelta(part.id, part.tool, part.args, index, part.providerItemId))
                    add(StreamFrame.ToolCallComplete(part.id, part.tool, part.args, index, part.providerItemId))
                }

                is MessagePart.Attachment -> {
                    logger.warn { "Attachment is not supported for streaming yet" }
                }
            }
        }

        add(
            StreamFrame.End(
                finishReason = finishReason,
                metaInfo = metaInfo,
                messageId = id,
            )
        )
    }
}

/**
 * Converts frames into [Message.Assistant] objects.
 *
 * Collects all complete frames into one [Message.Assistant] objects.
 *
 * @return A list of [Message.Assistant] objects.
 */
public fun Iterable<StreamFrame>.toMessageResponse(): Message.Assistant {
    var end: StreamFrame.End? = null

    val parts: List<MessagePart.ResponsePart> = mapNotNull { frame ->
        when (frame) {
            is StreamFrame.ReasoningComplete -> MessagePart.Reasoning(
                id = frame.id,
                content = frame.content,
                summary = frame.summary,
                encrypted = frame.encrypted,
                providerItemId = frame.providerItemId,
                replay = frame.replay,
            )

            is StreamFrame.TextComplete ->
                MessagePart.Text(
                    frame.text,
                    providerItemId = frame.providerItemId,
                    generatedFileCitations = frame.generatedFileCitations,
                )

            is StreamFrame.ToolCallComplete ->
                MessagePart.Tool.Call(
                    id = frame.id,
                    tool = frame.name,
                    args = Json.parseToJsonElement(frame.content).jsonObject,
                    providerItemId = frame.providerItemId,
                )

            is StreamFrame.CodeExecutionComplete ->
                MessagePart.CodeExecution(
                    id = frame.id,
                    code = frame.code,
                    containerId = frame.containerId,
                    outputs = frame.outputs,
                    failure = frame.failure,
                    providerItemId = frame.providerItemId,
                )

            is StreamFrame.HostedExecutionUpdate -> frame.update

            is StreamFrame.GeneratedFileComplete -> frame.file

            is StreamFrame.GeneratedFileBytes -> null

            is StreamFrame.ManagedGeneratedFileBytes -> null

            is StreamFrame.End -> {
                end = frame
                null
            }

            else -> null
        }
    }

    return Message.Assistant(
        parts = parts,
        finishReason = end?.finishReason,
        metaInfo = end?.metaInfo ?: ResponseMetaInfo.Empty,
        id = end?.messageId,
    )
}
