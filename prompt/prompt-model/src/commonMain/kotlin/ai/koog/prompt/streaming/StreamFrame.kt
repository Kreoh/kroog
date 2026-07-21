package ai.koog.prompt.streaming

import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.jvm.JvmOverloads

/**
 * Represents a frame of a streaming response from a LLM.
 */
@Serializable
public sealed interface StreamFrame {

    /**
     * The interface representing a complete frame of a streaming response from a LLM.
     */
    public sealed interface CompleteFrame : StreamFrame {
        public val index: Int?
    }

    /**
     * The interface representing a delta or partial frame of a streaming response from a LLM.
     */
    public sealed interface DeltaFrame : StreamFrame {
        public val index: Int?
    }

    /**
     * Represents a frame of a streaming response from a LLM with text delta.
     *
     * @property text The text to append to the response.
     */
    @Serializable
    public data class TextDelta @JvmOverloads constructor(
        val text: String,
        override val index: Int? = null,
        public val providerItemId: String? = null,
    ) : DeltaFrame, StreamFrame

    /**
     * Represents a completion of a streaming response text part.
     *
     * @property text The complete text of the response.
     */
    @Serializable
    public data class TextComplete @JvmOverloads constructor(
        val text: String,
        override val index: Int? = null,
        public val providerItemId: String? = null,
        public val generatedFileCitations: List<MessagePart.GeneratedFileCitation> = emptyList(),
    ) : CompleteFrame, StreamFrame

    /**
     * Represents a frame of a streaming response from a LLM with reasoning text delta.
     *
     * @property id The id of the reasoning text.
     * @property text The text to append to the reasoning text.
     * @property summary The summary of the reasoning text.
     * @property index The index of the frame in the list of frames.
     */
    @Serializable
    public data class ReasoningDelta @JvmOverloads constructor(
        val id: String? = null,
        val text: String? = null,
        val summary: String? = null,
        override val index: Int? = null,
        public val providerItemId: String? = null,
    ) : DeltaFrame, StreamFrame

    /**
     * Represents a frame of a streaming response from a LLM with reasoning text delta.
     *
     * @property id The id of the reasoning text.
     * @property content The text to append to the reasoning text.
     * @property summary The summary of the reasoning text.
     * @param encrypted The encrypted text of the reasoning text.
     * @property index The index of the frame in the list of frames.
     */
    @Serializable
    public data class ReasoningComplete @JvmOverloads constructor(
        val id: String?,
        val content: List<String>,
        val summary: List<String>? = null,
        public val encrypted: String? = null,
        override val index: Int? = null,
        public val providerItemId: String? = null,
        public val replay: List<MessagePart.ReasoningReplay> = emptyList(),
    ) : CompleteFrame, StreamFrame

    /**
     * Represents a frame of a streaming response from a LLM that contains a tool call.
     *
     * @property id The ID of the tool call. Can be null for partial frames.
     * @property name The name of the tool being called. Can be null for partial frames.
     * @property content The content/arguments of the tool call. Can be null for partial frames.
     */
    @Serializable
    public data class ToolCallDelta @JvmOverloads constructor(
        val id: String?,
        val name: String?,
        val content: String?,
        override val index: Int? = null,
        public val providerItemId: String? = null,
    ) : DeltaFrame, StreamFrame

    /**
     * Represents a frame of a streaming response from a LLM that contains a tool call.
     *
     * @property id The ID of the tool call. Can be null for partial frames.
     * @property name The name of the tool being called. Can be null for partial frames.
     * @property content The complete content/arguments of the tool call..
     */
    @Serializable
    public data class ToolCallComplete @JvmOverloads constructor(
        val id: String?,
        val name: String,
        val content: String,
        override val index: Int? = null,
        public val providerItemId: String? = null,
    ) : CompleteFrame, StreamFrame {

        /**
         * Lazily parses and caches the result of parsing [content] as a JSON object.
         */
        val contentJsonResult: Result<JsonObject> by lazy {
            runCatching { Json.parseToJsonElement(content).jsonObject }
        }

        /**
         * Lazily parses the content of the tool call as a JSON object.
         * Can throw an exception when parsing fails.
         */
        val contentJson: JsonObject
            get() = contentJsonResult.getOrThrow()
    }

    /**
     * Starts one provider-hosted code execution.
     *
     * @property id The provider item identifier.
     * @property containerId The provider container identifier.
     * @property index The response output index.
     */
    @Serializable
    public data class CodeExecutionStart @JvmOverloads constructor(
        public val id: String,
        public val containerId: String?,
        public val index: Int? = null,
        public val providerItemId: String? = null,
    ) : StreamFrame

    /**
     * Appends a code fragment to one provider-hosted code execution.
     *
     * @property id The provider item identifier.
     * @property containerId The provider container identifier.
     * @property code The code fragment.
     * @property index The response output index.
     */
    @Serializable
    public data class CodeExecutionCodeDelta @JvmOverloads constructor(
        public val id: String,
        public val containerId: String?,
        public val code: String,
        override val index: Int? = null,
        public val providerItemId: String? = null,
    ) : DeltaFrame

    /**
     * Emits one ordered output from provider-hosted code execution.
     *
     * @property id The provider item identifier.
     * @property containerId The provider container identifier.
     * @property output The typed log or image output.
     * @property index The response output index.
     */
    @Serializable
    public data class CodeExecutionOutput @JvmOverloads constructor(
        public val id: String,
        public val containerId: String?,
        public val output: MessagePart.CodeExecution.Output,
        public val index: Int? = null,
        public val providerItemId: String? = null,
    ) : StreamFrame

    /**
     * Reports a terminal unsuccessful provider-hosted code execution state.
     *
     * @property id The provider item identifier.
     * @property containerId The provider container identifier.
     * @property failure The typed terminal failure state.
     * @property index The response output index.
     */
    @Serializable
    public data class CodeExecutionFailure @JvmOverloads constructor(
        public val id: String,
        public val containerId: String?,
        public val failure: MessagePart.CodeExecution.Failure,
        public val index: Int? = null,
        public val providerItemId: String? = null,
    ) : StreamFrame

    /**
     * Completes one provider-hosted code execution.
     *
     * @property id The provider item identifier.
     * @property code The complete executed code.
     * @property containerId The provider container identifier.
     * @property outputs Ordered log and image outputs.
     * @property failure The terminal failure state, or null on success.
     * @property index The response output index.
     */
    @Serializable
    public data class CodeExecutionComplete @JvmOverloads constructor(
        public val id: String,
        public val code: String,
        public val containerId: String?,
        public val outputs: List<MessagePart.CodeExecution.Output>,
        public val failure: MessagePart.CodeExecution.Failure? = null,
        override val index: Int? = null,
        public val providerItemId: String? = null,
    ) : CompleteFrame

    /** A complete hosted execution lifecycle update. */
    @Serializable
    public data class HostedExecutionUpdate(
        public val update: MessagePart.HostedExecution,
        override val index: Int? = null,
    ) : CompleteFrame

    /** A complete provider-generated file update. */
    @Serializable
    public data class GeneratedFileComplete(
        public val file: MessagePart.GeneratedFile,
        override val index: Int? = null,
    ) : CompleteFrame

    /**
     * Represents a frame of a streaming response from a LLM that signals the end of the stream.
     *
     * @property finishReason The reason for the stream to end.
     */
    @Serializable
    public data class End @JvmOverloads constructor(
        val finishReason: String? = null,
        val metaInfo: ResponseMetaInfo = ResponseMetaInfo.Empty,
        public val messageId: String? = null,
    ) : StreamFrame
}
