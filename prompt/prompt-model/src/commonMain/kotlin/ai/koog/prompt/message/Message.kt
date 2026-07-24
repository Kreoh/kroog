package ai.koog.prompt.message

import ai.koog.utils.time.KoogClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.jvm.JvmOverloads
import kotlin.time.Instant

/** A list of [Message.Assistant] responses representing multiple completion choices from the LLM. */
public typealias LLMChoice = List<Message.Assistant>

/** Identifies whether execution happened inside a provider response or through a client-managed service. */
@Serializable
public enum class ExecutionOrigin {
    NATIVE_PROVIDER_HOSTED,
    CLIENT_MANAGED,
}

/** Text channel represented by a client-managed progress update. */
@Serializable
public enum class ManagedExecutionOutputStream {
    STDOUT,
    STDERR,
}

/** Lossless provider resource identity retained with a client-managed presentation transcript. */
@Serializable
public sealed interface ManagedExecutionSessionReference {
    @Serializable
    public data class VertexAgentEngine(
        val project: String,
        val location: String,
        val reasoningEngineResource: String,
        val sandboxResourceName: String,
        val expiresAtEpochMilliseconds: Long? = null,
        val codeLanguage: String? = null,
    ) : ManagedExecutionSessionReference

    @Serializable
    public data class BedrockAgentCore(
        val region: String,
        val codeInterpreterIdentifier: String,
        val sessionId: String,
        val createdAtEpochMilliseconds: Long,
        val timeoutSeconds: Int,
    ) : ManagedExecutionSessionReference
}

/** Lossless provider file identity retained with client-managed file events. */
@Serializable
public sealed interface ManagedExecutionFileReference {
    public val providerFileId: String?

    @Serializable
    public data class VertexAgentEngine(
        val sandboxResourceName: String,
        val path: String,
        override val providerFileId: String? = null,
    ) : ManagedExecutionFileReference

    @Serializable
    public data class BedrockAgentCore(
        val sessionId: String,
        val path: String,
        override val providerFileId: String? = null,
        val region: String = "",
        val codeInterpreterIdentifier: String = "aws.codeinterpreter.v1",
    ) : ManagedExecutionFileReference
}

/**
 * Represents a message exchanged in a chat with LLM. Messages can be categorized
 * by their type and role, denoting the purpose and source of the message.
 *
 * Represents both a message from LLM and a message to LLM from user or environment.
 */
@Serializable
public sealed interface Message {
    /**
     * The unique identifier of the message.
     */
    public val id: String?

    /**
     * The role associated with the message.
     */
    public val role: Role

    /**
     * The list of message parts: text, reasoning, tool calls and results
     */
    public val parts: List<MessagePart>

    /**
     * Extracts and concatenates the textual content from all `MessagePart.Text` elements in the message.
     *
     * @param separator The delimiter used to join the text content of the parts. Default is "\n".
     * @return A single string with the concatenated text content of all `MessagePart.Text` elements,
     *         separated by the specified delimiter.
     */
    public fun textContent(
        separator: String,
    ): String = parts.filterIsInstance<MessagePart.Text>().joinToString(separator = separator) { it.text }

    /**
     * Extracts and concatenates the textual content from all `MessagePart.Text` elements in the message.
     * Messages are joined by a newline character ("\n").
     *
     * @return A single string with the concatenated text content of all `MessagePart.Text` elements,
     *         separated by a newline character.
     */
    public fun textContent(): String = textContent(separator = "\n")

    /**
     * Stores metadata information for the current message instance, such as token count and timestamp.
     */
    public val metaInfo: MessageMetaInfo

    /**
     * A system-role message used to set the behaviour or persona of the assistant.
     * Only [MessagePart.Text] parts are supported.
     *
     * @property parts The text parts that make up the system prompt.
     * @property metaInfo Request metadata such as timestamp.
     * @property id Optional unique identifier for the message.
     */
    @Serializable
    public data class System @JvmOverloads constructor(
        override val parts: List<MessagePart.Text>,
        override val metaInfo: RequestMetaInfo,
        override val id: String? = null,
    ) : Message {
        override val role: Role = Role.System

        /**
         * Convenience constructor that wraps a single [MessagePart.Text] in a list.
         */
        @JvmOverloads
        public constructor(
            part: MessagePart.Text,
            metaInfo: RequestMetaInfo,
            id: String? = null,
        ) : this(
            listOf(part),
            metaInfo,
            id,
        )

        /**
         * Convenience constructor that creates a [MessagePart.Text] from a raw string.
         *
         * @param content The plain-text content of the system message.
         * @param cache Optional cache-control directive for the message part.
         */
        @JvmOverloads
        public constructor(
            content: String,
            metaInfo: RequestMetaInfo,
            cache: CacheControl? = null,
            id: String? = null,
        ) : this(
            MessagePart.Text(content, cache),
            metaInfo,
            id,
        )
    }

    /**
     * A user-role message sent to the LLM. May contain text, attachments, or tool results.
     *
     * @property parts The request parts (text, attachments, or tool results) of the message.
     * @property metaInfo Request metadata such as timestamp.
     * @property id Optional unique identifier for the message.
     */
    @Serializable
    public data class User @JvmOverloads constructor(
        override val parts: List<MessagePart.RequestPart>,
        override val metaInfo: RequestMetaInfo,
        override val id: String? = null,
    ) : Message {
        override val role: Role = Role.User

        /**
         * Convenience constructor that wraps a single [MessagePart.RequestPart] in a list.
         */
        @JvmOverloads
        public constructor(
            part: MessagePart.RequestPart,
            metaInfo: RequestMetaInfo,
            id: String? = null,
        ) : this(
            listOf(part),
            metaInfo,
            id
        )

        /**
         * Convenience constructor that creates a [MessagePart.Text] from a raw string.
         *
         * @param content The plain-text content of the user message.
         * @param cache Optional cache-control directive for the message part.
         */
        @JvmOverloads
        public constructor(
            content: String,
            metaInfo: RequestMetaInfo,
            cache: CacheControl? = null,
            id: String? = null,
        ) : this(
            MessagePart.Text(content, cache),
            metaInfo,
            id,
        )
    }

    /**
     * An assistant-role message returned by the LLM. May contain text, reasoning, and/or tool calls.
     *
     * @property parts The response parts (text, reasoning, tool calls) produced by the LLM.
     * @property metaInfo Response metadata including token counts and timestamp.
     * @property finishReason The reason the LLM stopped generating (e.g. `"stop"`, `"tool_calls"`), or null if unknown.
     * @property rawResponse The raw JSON response body from the provider, or null if not captured.
     * @property id Optional unique identifier for the message.
     */
    @Serializable
    public data class Assistant @JvmOverloads constructor(
        override val parts: List<MessagePart.ResponsePart>,
        override val metaInfo: ResponseMetaInfo,
        public val finishReason: String? = null,
        // TODO: replace with JSONObject?
        public val rawResponse: JsonObject? = null,
        override val id: String? = null,
    ) : Message {
        override val role: Role = Role.Assistant

        /**
         * Convenience constructor that wraps a single [MessagePart.ResponsePart] in a list.
         */
        @JvmOverloads
        public constructor(
            part: MessagePart.ResponsePart,
            metaInfo: ResponseMetaInfo,
            finishReason: String? = null,
            rawResponse: JsonObject? = null,
            id: String? = null,
        ) : this(
            listOf(part),
            metaInfo,
            finishReason,
            rawResponse,
            id
        )

        /**
         * Convenience constructor that creates a [MessagePart.Text] from a raw string.
         *
         * @param content The plain-text content of the assistant message.
         */
        @JvmOverloads
        public constructor(
            content: String,
            metaInfo: ResponseMetaInfo,
            finishReason: String? = null,
            rawResponse: JsonObject? = null,
            id: String? = null,
        ) : this(
            MessagePart.Text(content),
            metaInfo,
            finishReason,
            rawResponse,
            id
        )
    }

    /**
     * Defines the role of the message in the chat (e.g., system, user, assistant).
     */
    @Serializable
    public enum class Role {
        /**
         * Role indicating a system message.
         */
        System,

        /**
         * Role for messages generated by the user.
         */
        User,

        /**
         * Role for messages generated by an assistant (e.g., an AI assistant).
         */
        Assistant,
    }
}

/**
 * A discrete piece of content within a [Message]. Parts are typed by their direction and purpose:
 * [RequestPart] parts go to the LLM, [ResponsePart] parts come from the LLM, and [ContentPart]
 * parts (text, attachments) are valid in both directions.
 */
@Serializable
public sealed interface MessagePart {

    /** Optional cache-control directive for the provider's prompt-caching feature. */
    public val cacheControl: CacheControl?

    /**
     * A part that can appear in a request sent to the LLM.
     * All request parts carry an optional [cacheControl] directive.
     */
    @Serializable
    public sealed interface RequestPart : MessagePart

    /**
     * A part that can appear in a response received from the LLM
     * (e.g. text, reasoning, or tool calls).
     */
    @Serializable
    public sealed interface ResponsePart : MessagePart

    /**
     * Represents a part of a message that can be used in both request and response contexts.
     * Parts can be text [MessagePart.Text] or attachments [MessagePart.Attachment].
     */
    @Serializable
    public sealed interface ContentPart : RequestPart, ResponsePart

    /**
     * Text content part of the message.
     *
     * @property text The text content.
     */
    @Serializable
    public data class Text @JvmOverloads constructor(
        public val text: String,
        override val cacheControl: CacheControl? = null,
        public val providerItemId: String? = null,
        public val generatedFileCitations: List<GeneratedFileCitation> = emptyList(),
    ) : ContentPart

    /**
     * Attachment content part of the message.
     *
     * @property source The attachment source.
     */
    @Serializable
    public data class Attachment @JvmOverloads constructor(
        public val source: AttachmentSource,
        override val cacheControl: CacheControl? = null,
    ) : ContentPart

    /**
     * Represents a reasoning message exchanged in a chat system, encapsulating the content,
     * role, and associated metadata, with an optional reference to the original thinking process.
     *
     * @property id An optional framework-local identifier for the reasoning process.
     * @property content The content of the reasoning message.
     * @property summary An optional summary of the reasoning process.
     * @property encrypted The encrypted content of the reasoning message.
     */
    @Serializable
    public data class Reasoning @JvmOverloads constructor(
        public val content: List<String>,
        public val summary: List<String>? = null,
        public val encrypted: String? = null,
        public val id: String? = null,
        override val cacheControl: CacheControl? = null,
        public val providerItemId: String? = null,
        public val replay: List<ReasoningReplay> = emptyList(),
    ) : ResponsePart {

        /**
         * Convenience constructor for a single reasoning string.
         *
         * @param content The reasoning text, wrapped in a single-element list.
         */
        @JvmOverloads
        public constructor(
            content: String,
            summary: List<String>? = null,
            encrypted: String? = null,
            id: String? = null,
            cacheControl: CacheControl? = null,
            providerItemId: String? = null,
            replay: List<ReasoningReplay> = emptyList(),
        ) : this(
            listOf(content),
            summary,
            encrypted,
            id,
            cacheControl,
            providerItemId,
            replay,
        )
    }

    /**
     * Lossless provider replay content associated with a reasoning part.
     *
     * Signed text and opaque redacted payloads are deliberately different variants. Opaque payloads must be replayed
     * unchanged and must not be parsed or exposed as reasoning text.
     */
    @Serializable
    public sealed interface ReasoningReplay {

        /** Signed reasoning text and its provider signature. */
        @Serializable
        public data class Signed(
            public val text: String,
            public val signature: String,
        ) : ReasoningReplay

        /** Provider-owned redacted reasoning data that must remain opaque. */
        @Serializable
        public data class OpaqueRedacted(
            public val data: String,
        ) : ReasoningReplay
    }

    /**
     * Metadata for a provider-generated file.
     *
     * All descriptive fields are optional because streaming citations may initially contain only a provider file ID.
     */
    @Serializable
    public data class GeneratedFileCitation @JvmOverloads constructor(
        public val providerFileId: String,
        public val containerId: String? = null,
        public val filename: String? = null,
        public val mediaType: String? = null,
        public val sizeBytes: Long? = null,
        public val producingExecutionId: String? = null,
        public val providerItemId: String? = null,
        public val startIndex: Int? = null,
        public val endIndex: Int? = null,
    )

    /** A generated file returned by provider-hosted execution. */
    @Serializable
    public data class GeneratedFile @JvmOverloads constructor(
        public val providerFileId: String,
        public val containerId: String? = null,
        public val filename: String? = null,
        public val mediaType: String? = null,
        public val sizeBytes: Long? = null,
        public val producingExecutionId: String? = null,
        public val providerItemId: String? = null,
        override val cacheControl: CacheControl? = null,
        public val origin: ExecutionOrigin = ExecutionOrigin.NATIVE_PROVIDER_HOSTED,
        public val fileId: String? = null,
        public val managedReference: ManagedExecutionFileReference? = null,
        public val managedSession: ManagedExecutionSessionReference? = null,
        public val toolCallId: String? = null,
        public val managedSequence: Long? = null,
    ) : ResponsePart {
        /** Preserves the original JVM copy descriptor. */
        @Deprecated("Binary compatibility copy", level = DeprecationLevel.HIDDEN)
        public fun copy(
            providerFileId: String,
            containerId: String?,
            filename: String?,
            mediaType: String?,
            sizeBytes: Long?,
            producingExecutionId: String?,
            providerItemId: String?,
            cacheControl: CacheControl?,
        ): GeneratedFile = GeneratedFile(
            providerFileId,
            containerId,
            filename,
            mediaType,
            sizeBytes,
            producingExecutionId,
            providerItemId,
            cacheControl,
            origin,
            fileId,
            managedReference,
            managedSession,
            toolCallId,
            managedSequence,
        )
    }

    /**
     * Provider-neutral hosted execution lifecycle content.
     *
     * [executionId] is the framework's execution key. [providerItemId] is the provider's replay identity. A provider
     * container is optional for every lifecycle variant.
     */
    @Serializable
    public sealed interface HostedExecution : ResponsePart {
        public val executionId: String?
        public val containerId: String?
        public val providerItemId: String?
        public val origin: ExecutionOrigin
        public val managedSession: ManagedExecutionSessionReference?
        public val toolCallId: String?

        /** A request to execute [code] in provider-managed infrastructure. */
        @Serializable
        public data class Request @JvmOverloads constructor(
            public val code: String,
            public val language: String = "python",
            override val executionId: String? = null,
            override val containerId: String? = null,
            override val providerItemId: String? = null,
            override val cacheControl: CacheControl? = null,
            override val origin: ExecutionOrigin = ExecutionOrigin.NATIVE_PROVIDER_HOSTED,
            override val managedSession: ManagedExecutionSessionReference? = null,
            override val toolCallId: String? = null,
            public val managedSequence: Long? = null,
        ) : HostedExecution {
            /** Preserves the original JVM copy descriptor. */
            @Deprecated("Binary compatibility copy", level = DeprecationLevel.HIDDEN)
            public fun copy(
                code: String,
                language: String,
                executionId: String?,
                containerId: String?,
                providerItemId: String?,
                cacheControl: CacheControl?,
            ): Request = Request(
                code, language, executionId, containerId, providerItemId, cacheControl,
                origin, managedSession, toolCallId, managedSequence,
            )
        }

        /** A provider progress update. */
        @Serializable
        public data class Progress @JvmOverloads constructor(
            public val message: String? = null,
            public val sequence: Int? = null,
            override val executionId: String? = null,
            override val containerId: String? = null,
            override val providerItemId: String? = null,
            override val cacheControl: CacheControl? = null,
            override val origin: ExecutionOrigin = ExecutionOrigin.NATIVE_PROVIDER_HOSTED,
            override val managedSession: ManagedExecutionSessionReference? = null,
            override val toolCallId: String? = null,
            public val managedSequence: Long? = null,
            public val outputStream: ManagedExecutionOutputStream? = null,
        ) : HostedExecution {
            /** Preserves the original JVM copy descriptor. */
            @Deprecated("Binary compatibility copy", level = DeprecationLevel.HIDDEN)
            public fun copy(
                message: String?,
                sequence: Int?,
                executionId: String?,
                containerId: String?,
                providerItemId: String?,
                cacheControl: CacheControl?,
            ): Progress = Progress(
                message, sequence, executionId, containerId, providerItemId, cacheControl,
                origin, managedSession, toolCallId, managedSequence, outputStream,
            )
        }

        /**
         * The complete output observed for an execution at [sequence].
         *
         * [output] is cumulative rather than a delta, so consumers can replace prior output safely after reconnects.
         */
        @Serializable
        public data class CumulativeOutput @JvmOverloads constructor(
            public val output: String,
            public val sequence: Int? = null,
            override val executionId: String? = null,
            override val containerId: String? = null,
            override val providerItemId: String? = null,
            override val cacheControl: CacheControl? = null,
            override val origin: ExecutionOrigin = ExecutionOrigin.NATIVE_PROVIDER_HOSTED,
            override val managedSession: ManagedExecutionSessionReference? = null,
            override val toolCallId: String? = null,
            public val managedSequence: Long? = null,
        ) : HostedExecution {
            /** Preserves the original JVM copy descriptor. */
            @Deprecated("Binary compatibility copy", level = DeprecationLevel.HIDDEN)
            public fun copy(
                output: String,
                sequence: Int?,
                executionId: String?,
                containerId: String?,
                providerItemId: String?,
                cacheControl: CacheControl?,
            ): CumulativeOutput = CumulativeOutput(
                output, sequence, executionId, containerId, providerItemId, cacheControl,
                origin, managedSession, toolCallId, managedSequence,
            )
        }

        /** A successful terminal execution result. */
        @Serializable
        public data class Result @JvmOverloads constructor(
            public val output: String? = null,
            public val exitCode: Int? = null,
            public val generatedFiles: List<GeneratedFile> = emptyList(),
            override val executionId: String? = null,
            override val containerId: String? = null,
            override val providerItemId: String? = null,
            override val cacheControl: CacheControl? = null,
            override val origin: ExecutionOrigin = ExecutionOrigin.NATIVE_PROVIDER_HOSTED,
            override val managedSession: ManagedExecutionSessionReference? = null,
            override val toolCallId: String? = null,
            public val managedSequence: Long? = null,
            public val executionTimeSeconds: Double? = null,
            public val taskId: String? = null,
            public val taskStatus: String? = null,
        ) : HostedExecution {
            /** Preserves the original JVM copy descriptor. */
            @Deprecated("Binary compatibility copy", level = DeprecationLevel.HIDDEN)
            public fun copy(
                output: String?,
                exitCode: Int?,
                generatedFiles: List<GeneratedFile>,
                executionId: String?,
                containerId: String?,
                providerItemId: String?,
                cacheControl: CacheControl?,
            ): Result = Result(
                output, exitCode, generatedFiles, executionId, containerId, providerItemId, cacheControl,
                origin, managedSession, toolCallId, managedSequence, executionTimeSeconds, taskId, taskStatus,
            )
        }

        /** A terminal hosted execution error. */
        @Serializable
        public data class Error @JvmOverloads constructor(
            public val message: String,
            public val code: String? = null,
            override val executionId: String? = null,
            override val containerId: String? = null,
            override val providerItemId: String? = null,
            override val cacheControl: CacheControl? = null,
            override val origin: ExecutionOrigin = ExecutionOrigin.NATIVE_PROVIDER_HOSTED,
            override val managedSession: ManagedExecutionSessionReference? = null,
            override val toolCallId: String? = null,
            public val managedSequence: Long? = null,
            public val managedErrorKind: String? = null,
        ) : HostedExecution {
            /** Preserves the original JVM copy descriptor. */
            @Deprecated("Binary compatibility copy", level = DeprecationLevel.HIDDEN)
            public fun copy(
                message: String,
                code: String?,
                executionId: String?,
                containerId: String?,
                providerItemId: String?,
                cacheControl: CacheControl?,
            ): Error = Error(
                message, code, executionId, containerId, providerItemId, cacheControl,
                origin, managedSession, toolCallId, managedSequence, managedErrorKind,
            )
        }
    }

    /**
     * Represents one provider-hosted code execution and its ordered outputs.
     *
     * @property id The framework execution identifier retained for compatibility.
     * @property code The executed code.
     * @property containerId The optional provider container identifier.
     * @property outputs Ordered log and image outputs.
     * @property failure The terminal failure state, or null when execution completed successfully.
     * @property cacheControl Optional cache-control directive.
     * @property providerItemId The stable provider replay identifier, when supplied.
     */
    @Serializable
    public data class CodeExecution @JvmOverloads constructor(
        public val id: String,
        public val code: String,
        public val containerId: String?,
        public val outputs: List<Output> = emptyList(),
        public val failure: Failure? = null,
        override val cacheControl: CacheControl? = null,
        public val providerItemId: String? = null,
    ) : ResponsePart {

        /** A typed output produced by provider-hosted code execution. */
        @Serializable
        public sealed interface Output {

            /** Text written to the execution logs. */
            @Serializable
            public data class Logs(public val logs: String) : Output

            /** An image made available at [url] by the provider. */
            @Serializable
            public data class Image(public val url: String) : Output
        }

        /** A terminal unsuccessful code execution state. */
        @Serializable
        public enum class Failure {
            /** The provider reported a failed execution. */
            FAILED,

            /** The provider reported an incomplete execution. */
            INCOMPLETE,
        }
    }

    /**
     * Represents messages exchanged with tools, either as calls or results.
     */
    @Serializable
    public sealed interface Tool : MessagePart {
        /**
         * The name of the tool used.
         */
        public val tool: String

        /**
         * Represents a tool call message sent as a response.
         *
         * @property id The unique identifier of the tool call.
         * @property tool The name of the tool being called.
         * @property args The JSON-encoded arguments for the tool call.
         */
        @Serializable
        public data class Call @JvmOverloads constructor(
            public val id: String? = null,
            override val tool: String,
            public val args: String,
            override val cacheControl: CacheControl? = null,
            public val providerItemId: String? = null,
        ) : Tool, ResponsePart {

            /** The provider function-call identifier, kept separate from [providerItemId]. */
            public val callId: String?
                get() = id

            // TODO: replace with JSONObject?
            /** Lazily parsed [JsonObject] view of [args]. */
            val argsJson: JsonObject by lazy {
                Json.parseToJsonElement(args).jsonObject
            }

            /**
             * Convenience constructor that accepts a [JsonObject] and serialises it to [args].
             *
             * @param args The tool arguments as a [JsonObject].
             */
            @JvmOverloads
            public constructor(
                id: String? = null,
                tool: String,
                args: JsonObject,
                cacheControl: CacheControl? = null,
                providerItemId: String? = null,
            ) : this(
                id = id,
                tool = tool,
                args = Json.encodeToString(args),
                cacheControl = cacheControl,
                providerItemId = providerItemId,
            )
        }

        /**
         * Represents the result of a tool call sent as a request.
         *
         * @property id The unique identifier of the tool result.
         * @property tool The name of the tool that provided the result.
         * @property parts The parts of the tool result. May contain text and attachment parts such as images or files.
         *   Note that not all LLM providers support non-text content in tool results.
         * @property isError Whether this tool result represents an error. Defaults to false.
         * @property cacheControl Optional cache-control directive for the provider's prompt-caching feature.
         */
        @Serializable
        public data class Result @JvmOverloads constructor(
            public val id: String? = null,
            override val tool: String,
            public val parts: List<ContentPart>,
            public val isError: Boolean = false,
            override val cacheControl: CacheControl? = null,
            public val providerItemId: String? = null,
        ) : Tool, RequestPart {

            /** The provider function-call identifier, kept separate from [providerItemId]. */
            public val callId: String?
                get() = id

            /** Convenience constructor for a single text output. */
            @JvmOverloads
            public constructor(
                id: String? = null,
                tool: String,
                output: String,
                isError: Boolean = false,
                cacheControl: CacheControl? = null,
                providerItemId: String? = null,
            ) :
                this(id, tool, listOf(Text(output)), isError, cacheControl, providerItemId)

            /** Returns the concatenated text content of all text parts. */
            public val output: String
                get() = parts.filterIsInstance<Text>().joinToString("") { it.text }
        }
    }
}

/**
 * Meta-information associated with a message in a chat system.
 *
 * @property timestamp The timestamp [Instant] of when the message is created
 * since the Unix epoch. Defaults to the current system time.
 */
@Serializable
public sealed interface MessageMetaInfo {
    /**
     * Represents the timestamp of a message
     *
     * This property indicates the precise time when a message was created. It defaults
     * to the current system time if not explicitly set.
     */
    public val timestamp: Instant

    /**
     * Free-form information associated with a message.
     * Can be used to store custom metadata that doesn't fit into the standard fields.
     */
    // TODO: replace with JSONObject?
    public val metadata: JsonObject?
}

/**
 * Represents [MessageMetaInfo] specific to a request within the system.
 *
 * This class is an implementation of the [MessageMetaInfo] interface and provides
 * timestamp information for a request.
 *
 * @property timestamp The time at which the request metadata was created.
 * Defaults to the current system time if not provided.
 */
@Serializable
public data class RequestMetaInfo @JvmOverloads constructor(
    override val timestamp: Instant,
    override val metadata: JsonObject? = null
) : MessageMetaInfo {
    /**
     * Companion object for `RequestMetaInfo` that provides factory methods and utilities related to creating instances.
     */
    public companion object {
        /**
         * Creates a RequestMetadata instance with a timestamp from the provided clock.
         *
         * @param clock The clock to use for generating the timestamp.
         * @return A new RequestMetadata instance with the timestamp from the provided clock.
         */
        public fun create(clock: KoogClock): RequestMetaInfo = RequestMetaInfo(clock.now())

        /**
         * An empty instance of [RequestMetaInfo] with the timestamp set to a distant past.
         */
        public val Empty: RequestMetaInfo = RequestMetaInfo(Instant.DISTANT_PAST)
    }
}

/**
 * Represents metadata associated with a response message in a chat system.
 *
 * This class provides details about the response, including the count of tokens
 * used in the response and the timestamp of when the response was created.
 * It implements the `MessageMetadata` interface, inheriting the timestamp property.
 *
 *
 * Example:
 * - Message 1: "Hello" (3 tokens) → tokensCount = 3
 * - Message 2: "How are you?" (4 tokens) → tokensCount = 3 + 4 = 7
 * - Message 3: "I am fine, thank you." (6 tokens) → tokensCount = 7 + 6 = 13
 *
 * @property totalTokensCount The total number of tokens involved in the response, including both input and output tokens, or null if not available.
 * @property inputTokensCount The number of tokens used in the input, or null if not available.
 * @property outputTokensCount The number of tokens generated in the output, or null if not available.
 * @property metadata Additional metadata as a JSON object.
 *                    This can be used to store custom metadata that doesn't fit into the standard fields.
 * @property timestamp The timestamp indicating when the response was created.
 * @property modelId The ID of the model used for generating the response, or null if not available.
 * @property metadata Additional metadata associated with the response, or null if not available.
 *
 * Defaults to the current system time if not explicitly set.
 */
@Serializable
public data class ResponseMetaInfo @JvmOverloads constructor(
    public override val timestamp: Instant,
    public val totalTokensCount: Int? = null,
    public val inputTokensCount: Int? = null,
    public val outputTokensCount: Int? = null,
    public val modelId: String? = null,
    override val metadata: JsonObject? = null,
) : MessageMetaInfo {
    /**
     * Companion object for the ResponseMetaInfo class.
     * Provides utility methods to create ResponseMetaInfo instances.
     */
    public companion object {
        /**
         * Creates a ResponseMetadata instance with a timestamp from the provided clock.
         *
         * @param clock The clock to use for generating the timestamp.
         * @param totalTokensCount The total number of tokens involved in the response, including both input and output tokens.
         * @param inputTokensCount The number of tokens used in the input.
         * @param outputTokensCount The number of tokens generated in the output.
         * @param modelId The ID of the model used for generating the response, or null if not available.
         * @param metadata Additional metadata as a JSON object.
         * @return A new ResponseMetadata instance with the timestamp from the provided clock.
         */
        @JvmOverloads
        public fun create(
            clock: KoogClock,
            totalTokensCount: Int? = null,
            inputTokensCount: Int? = null,
            outputTokensCount: Int? = null,
            modelId: String? = null,
            metadata: JsonObject? = null,
        ): ResponseMetaInfo = ResponseMetaInfo(
            clock.now(),
            totalTokensCount,
            inputTokensCount,
            outputTokensCount,
            modelId,
            metadata
        )

        /**
         * An empty instance of the [ResponseMetaInfo] with the timestamp set to a distant past.
         */
        public val Empty: ResponseMetaInfo = ResponseMetaInfo(Instant.DISTANT_PAST)
    }
}
