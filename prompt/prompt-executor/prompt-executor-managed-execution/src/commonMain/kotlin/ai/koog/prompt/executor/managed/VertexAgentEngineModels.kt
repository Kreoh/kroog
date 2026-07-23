package ai.koog.prompt.executor.managed

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Supplies a short-lived OAuth access token for one Vertex request. */
public fun interface VertexAccessTokenProvider {
    /** Returns a bearer token without the `Bearer` prefix. */
    public suspend fun getAccessToken(): String
}

/** Code languages supported by Vertex Agent Engine Code Execution. */
public enum class VertexAgentEngineCodeLanguage(
    internal val wireName: String,
    internal val requestName: String,
) {
    PYTHON("LANGUAGE_PYTHON", "python"),
    JAVASCRIPT("LANGUAGE_JAVASCRIPT", "javascript"),
}

/**
 * Vertex Agent Engine Code Execution configuration.
 *
 * The current service is Preview, supports only `us-central1`, limits sandbox TTL to 14 days, limits each complete
 * request or response to 100 MB, and fixes execution timeout at 300 seconds. The v1 Execute RPC has no timeout field,
 * so request timeout remains a responsibility of the injected HTTP client.
 *
 * Official documentation:
 * https://docs.cloud.google.com/gemini-enterprise-agent-platform/scale/sandbox/code-execution-overview
 * https://docs.cloud.google.com/gemini-enterprise-agent-platform/reference/rest/v1/projects.locations.reasoningEngines.sandboxEnvironments
 */
public data class VertexAgentEngineConfiguration(
    val project: String,
    val location: String,
    val reasoningEngineResource: String,
    val baseUrl: String,
    val displayName: String = "koog-managed-execution",
    val codeLanguage: VertexAgentEngineCodeLanguage = VertexAgentEngineCodeLanguage.PYTHON,
    val sandboxTtlSeconds: Long = DEFAULT_SANDBOX_TTL_SECONDS,
    val operationPollIntervalMillis: Long = DEFAULT_OPERATION_POLL_INTERVAL_MILLIS,
    val operationDeadlineMillis: Long = DEFAULT_OPERATION_DEADLINE_MILLIS,
) {
    init {
        require(location == SUPPORTED_LOCATION) {
            "Vertex Agent Engine Code Execution is available only in $SUPPORTED_LOCATION"
        }
        requireResourceSegment(project, "project")
        requireReasoningEngineResource(reasoningEngineResource, project, location)
        require(
            baseUrl.startsWith("https://") &&
                baseUrl.removePrefix("https://").isNotBlank() &&
                baseUrl.none { it.isWhitespace() || it.isISOControl() || it == '?' || it == '#' }
        ) {
            "Vertex base URL must be an HTTPS URL"
        }
        require(displayName.isNotBlank() && displayName.none(Char::isISOControl)) {
            "Vertex sandbox display name must be non-blank and contain no control characters"
        }
        require(sandboxTtlSeconds in 1..MAX_SANDBOX_TTL_SECONDS) {
            "Vertex sandbox TTL must be between 1 second and $MAX_SANDBOX_TTL_SECONDS seconds"
        }
        require(operationPollIntervalMillis > 0) {
            "Vertex operation poll interval must be positive"
        }
        require(operationDeadlineMillis > 0) {
            "Vertex operation deadline must be positive"
        }
    }

    public companion object {
        public const val SUPPORTED_LOCATION: String = "us-central1"
        public const val MAX_SANDBOX_TTL_SECONDS: Long = 14L * 24L * 60L * 60L
        public const val MAX_EXECUTION_SECONDS: Int = 300
        public const val MAX_REQUEST_OR_RESPONSE_BYTES: Long = 100L * 1024L * 1024L
        public const val DEFAULT_SANDBOX_TTL_SECONDS: Long = 60L * 60L
        public const val DEFAULT_OPERATION_POLL_INTERVAL_MILLIS: Long = 1_000L
        public const val DEFAULT_OPERATION_DEADLINE_MILLIS: Long = 120_000L
    }
}

/** Safe details returned by the Vertex get and list sandbox methods. */
public data class VertexAgentEngineSandbox(
    val name: String,
    val displayName: String,
    val state: String?,
    val expiresAtEpochMilliseconds: Long?,
    val codeLanguage: VertexAgentEngineCodeLanguage? = null,
)

/** One page from the Vertex list sandboxes method. */
public data class VertexAgentEngineSandboxPage(
    val sandboxes: List<VertexAgentEngineSandbox>,
    val nextPageToken: String?,
)

/** Typed lifecycle failure from Vertex session acquisition, inspection, polling, or release. */
public class VertexAgentEngineLifecycleException(
    public val kind: ManagedExecutionErrorKind,
    public val providerCode: String?,
    public val statusCode: Int?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal fun requireResourceSegment(value: String, label: String) {
    require(RESOURCE_SEGMENT.matches(value)) {
        "Vertex $label must be one non-empty resource-name segment"
    }
}

internal fun requireReasoningEngineResource(resource: String, project: String, location: String) {
    val match = REASONING_ENGINE_RESOURCE.matchEntire(resource)
        ?: throw IllegalArgumentException(
            "Vertex reasoning engine must use projects/{project}/locations/{location}/reasoningEngines/{id}"
        )
    require(match.groupValues[1] == project && match.groupValues[2] == location) {
        "Vertex reasoning engine must match the configured project and location"
    }
}

internal fun parseSandboxResource(resource: String): VertexSandboxResourceParts {
    val match = SANDBOX_RESOURCE.matchEntire(resource)
        ?: throw IllegalArgumentException(
            "Vertex sandbox must use projects/{project}/locations/{location}/reasoningEngines/{id}/" +
                "sandboxEnvironments/{id}"
        )
    return VertexSandboxResourceParts(
        project = match.groupValues[1],
        location = match.groupValues[2],
        reasoningEngine = match.groupValues[3],
        sandboxEnvironment = match.groupValues[4],
    )
}

internal data class VertexSandboxResourceParts(
    val project: String,
    val location: String,
    val reasoningEngine: String,
    val sandboxEnvironment: String,
)

private val RESOURCE_SEGMENT = Regex("[A-Za-z0-9._~-]+")
private val REASONING_ENGINE_RESOURCE = Regex(
    "projects/([A-Za-z0-9._~-]+)/locations/([A-Za-z0-9._~-]+)/reasoningEngines/([A-Za-z0-9._~-]+)"
)
private val SANDBOX_RESOURCE = Regex(
    "projects/([A-Za-z0-9._~-]+)/locations/([A-Za-z0-9._~-]+)/reasoningEngines/([A-Za-z0-9._~-]+)/" +
        "sandboxEnvironments/([A-Za-z0-9._~-]+)"
)

@Serializable
internal data class VertexSandboxEnvironmentWire(
    val name: String? = null,
    val displayName: String? = null,
    val state: String? = null,
    val expireTime: String? = null,
    val spec: VertexSandboxSpecWire? = null,
)

/**
 * v1 create body from the official discovery document.
 *
 * https://aiplatform.googleapis.com/$discovery/rest?version=v1
 */
@Serializable
internal data class VertexCreateSandboxWire(
    val displayName: String,
    val ttl: String,
    val spec: VertexSandboxSpecWire,
)

@Serializable
internal data class VertexSandboxSpecWire(
    val codeExecutionEnvironment: VertexCodeExecutionEnvironmentWire? = null,
)

@Serializable
internal data class VertexCodeExecutionEnvironmentWire(
    val codeLanguage: String? = null,
)

/**
 * Chunk uses base64 for both [data] and every value in [metadata] attributes.
 *
 * https://aiplatform.googleapis.com/$discovery/rest?version=v1
 */
@Serializable
internal data class VertexChunkWire(
    val data: String? = null,
    val mimeType: String? = null,
    val metadata: VertexChunkMetadataWire? = null,
)

@Serializable
internal data class VertexChunkMetadataWire(
    val attributes: Map<String, String>? = null,
)

@Serializable
internal data class VertexExecuteSandboxWire(
    val inputs: List<VertexChunkWire>,
)

@Serializable
internal data class VertexExecuteSandboxResponseWire(
    val outputs: List<VertexChunkWire>? = null,
)

@Serializable
internal data class VertexListSandboxesResponseWire(
    val sandboxEnvironments: List<VertexSandboxEnvironmentWire> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
internal data class VertexOperationWire(
    val name: String? = null,
    val done: Boolean = false,
    val error: VertexRpcStatusWire? = null,
    val response: JsonObject? = null,
)

@Serializable
internal data class VertexRpcStatusWire(
    val code: Int? = null,
    val message: String? = null,
)

@Serializable
internal data class VertexCodeInputWire(
    val code: String,
)

@Serializable
internal data class VertexJsonOutputFileWire(
    val name: String? = null,
    @SerialName("file_name")
    val fileName: String? = null,
    val content: String? = null,
    val mimeType: String? = null,
    @SerialName("mime_type")
    val legacyMimeType: String? = null,
)
