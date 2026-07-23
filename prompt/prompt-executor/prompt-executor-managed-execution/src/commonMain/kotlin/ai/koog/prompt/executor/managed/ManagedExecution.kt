package ai.koog.prompt.executor.managed

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Provider-owned identity for a managed execution session.
 *
 * Session references retain the fields required to address the corresponding provider resource. They are not
 * interchangeable with inline hosted-execution container identifiers.
 */
@Serializable
public sealed interface ManagedExecutionSessionReference {

    /** Vertex Agent Engine reasoning-engine and sandbox identity. */
    @Serializable
    @SerialName("vertex_agent_engine")
    public data class VertexAgentEngine(
        val project: String,
        val location: String,
        val reasoningEngineResource: String,
        val sandboxResourceName: String,
        val expiresAtEpochMilliseconds: Long? = null,
        val codeLanguage: VertexAgentEngineCodeLanguage? = null,
    ) : ManagedExecutionSessionReference

    /** Bedrock AgentCore Code Interpreter session identity. */
    @Serializable
    @SerialName("bedrock_agent_core")
    public data class BedrockAgentCore(
        val region: String,
        val codeInterpreterIdentifier: String,
        val sessionId: String,
        val createdAtEpochMilliseconds: Long,
        val timeoutSeconds: Int,
    ) : ManagedExecutionSessionReference
}

/** Whether the service created a session for this caller or attached to an existing session. */
@Serializable
public enum class ManagedExecutionSessionOwnership {
    OWNED,
    BORROWED,
}

/** A provider session together with the ownership required for safe cleanup. */
@Serializable
public data class ManagedExecutionSession(
    val reference: ManagedExecutionSessionReference,
    val ownership: ManagedExecutionSessionOwnership,
)

/** Code submitted to a managed execution service. */
@Serializable
public data class ManagedExecutionRequest(
    val executionId: String,
    val code: String,
    val language: String = "python",
    val files: List<ManagedExecutionInputFile> = emptyList(),
)

/** One binary input file submitted with managed code. */
@Serializable
public data class ManagedExecutionInputFile(
    val filename: String,
    val mediaType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ManagedExecutionInputFile) return false

        return filename == other.filename &&
            mediaType == other.mediaType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = filename.hashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "ManagedExecutionInputFile(filename=$filename, mediaType=$mediaType, byteCount=${bytes.size})"
}

/**
 * Provider-specific handle for a generated file.
 *
 * [providerFileId] is optional because both managed services can identify a file through their session and path.
 */
@Serializable
public sealed interface ManagedExecutionFileReference {
    public val providerFileId: String?

    /** A file in a Vertex Agent Engine sandbox. */
    @Serializable
    @SerialName("vertex_agent_engine")
    public data class VertexAgentEngine(
        val sandboxResourceName: String,
        val path: String,
        override val providerFileId: String? = null,
    ) : ManagedExecutionFileReference

    /** A file in a Bedrock AgentCore Code Interpreter session. */
    @Serializable
    @SerialName("bedrock_agent_core")
    public data class BedrockAgentCore(
        val sessionId: String,
        val path: String,
        override val providerFileId: String? = null,
    ) : ManagedExecutionFileReference
}

/** Complete metadata for a generated file. File bytes are delivered separately as chunks. */
@Serializable
public data class ManagedExecutionGeneratedFile(
    val fileId: String,
    val reference: ManagedExecutionFileReference,
    val filename: String? = null,
    val mediaType: String? = null,
    val sizeBytes: Long? = null,
)

/** Stable error categories exposed by managed execution providers. */
@Serializable
public enum class ManagedExecutionErrorKind {
    INVALID_REQUEST,
    SESSION_EXPIRED,
    EXECUTION_FAILED,
    PROVIDER_FAILURE,
    PROTOCOL_FAILURE,
}

/**
 * One event from a managed execution.
 *
 * A service returns these events in provider order. [sequence] is monotonically increasing within one execution,
 * and every event retains the provider session identity. Terminal events are [Result] and [Error].
 */
@Serializable
public sealed interface ManagedExecutionEvent {
    public val sequence: Long
    public val executionId: String
    public val session: ManagedExecutionSessionReference

    /** The code accepted for execution. */
    @Serializable
    @SerialName("request")
    public data class Request(
        override val sequence: Long,
        override val executionId: String,
        override val session: ManagedExecutionSessionReference,
        val code: String,
        val language: String = "python",
    ) : ManagedExecutionEvent

    /** A stdout text delta. */
    @Serializable
    @SerialName("stdout")
    public data class Stdout(
        override val sequence: Long,
        override val executionId: String,
        override val session: ManagedExecutionSessionReference,
        val text: String,
    ) : ManagedExecutionEvent

    /** A stderr text delta. */
    @Serializable
    @SerialName("stderr")
    public data class Stderr(
        override val sequence: Long,
        override val executionId: String,
        override val session: ManagedExecutionSessionReference,
        val text: String,
    ) : ManagedExecutionEvent

    /** A provider-supplied cumulative text output snapshot. */
    @Serializable
    @SerialName("cumulative_output")
    public data class CumulativeOutput(
        override val sequence: Long,
        override val executionId: String,
        override val session: ManagedExecutionSessionReference,
        val output: String,
    ) : ManagedExecutionEvent

    /**
     * One binary chunk of a generated file.
     *
     * Collectors receive [bytes] before the next provider event is requested, preserving Flow backpressure.
     */
    @Serializable
    @SerialName("generated_file_chunk")
    public data class GeneratedFileChunk(
        override val sequence: Long,
        override val executionId: String,
        override val session: ManagedExecutionSessionReference,
        val fileId: String,
        val reference: ManagedExecutionFileReference,
        val offset: Long,
        val bytes: ByteArray,
    ) : ManagedExecutionEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GeneratedFileChunk) return false

            return sequence == other.sequence &&
                executionId == other.executionId &&
                session == other.session &&
                fileId == other.fileId &&
                reference == other.reference &&
                offset == other.offset &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = sequence.hashCode()
            result = 31 * result + executionId.hashCode()
            result = 31 * result + session.hashCode()
            result = 31 * result + fileId.hashCode()
            result = 31 * result + reference.hashCode()
            result = 31 * result + offset.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }

        override fun toString(): String =
            "GeneratedFileChunk(sequence=$sequence, executionId=$executionId, fileId=$fileId, " +
                "offset=$offset, byteCount=${bytes.size})"
    }

    /** Completion metadata and provider handle for a generated file. */
    @Serializable
    @SerialName("generated_file_complete")
    public data class GeneratedFileComplete(
        override val sequence: Long,
        override val executionId: String,
        override val session: ManagedExecutionSessionReference,
        val file: ManagedExecutionGeneratedFile,
    ) : ManagedExecutionEvent

    /** Successful terminal result. */
    @Serializable
    @SerialName("result")
    public data class Result(
        override val sequence: Long,
        override val executionId: String,
        override val session: ManagedExecutionSessionReference,
        val output: String? = null,
        val exitCode: Int? = null,
        val generatedFiles: List<ManagedExecutionGeneratedFile> = emptyList(),
    ) : ManagedExecutionEvent, Terminal

    /** Typed terminal failure. */
    @Serializable
    @SerialName("error")
    public data class Error(
        override val sequence: Long,
        override val executionId: String,
        override val session: ManagedExecutionSessionReference,
        val kind: ManagedExecutionErrorKind,
        val message: String,
        val providerCode: String? = null,
    ) : ManagedExecutionEvent, Terminal

    /** Marker for the sole terminal event of an execution. */
    public sealed interface Terminal : ManagedExecutionEvent
}

/**
 * Provider-neutral lifecycle for stateful managed execution.
 *
 * Passing no [existing] reference requests a new owned session. Passing a reference attaches to a borrowed session.
 * [execute] must return a cold flow and must not buffer complete generated files. [releaseSession] cleans up an owned
 * session using the provider's supported stop or delete operation and leaves borrowed sessions untouched.
 */
public interface ManagedExecutionService {
    public suspend fun acquireSession(
        existing: ManagedExecutionSessionReference? = null,
    ): ManagedExecutionSession

    public fun execute(
        session: ManagedExecutionSession,
        request: ManagedExecutionRequest,
    ): Flow<ManagedExecutionEvent>

    public suspend fun releaseSession(session: ManagedExecutionSession)
}
