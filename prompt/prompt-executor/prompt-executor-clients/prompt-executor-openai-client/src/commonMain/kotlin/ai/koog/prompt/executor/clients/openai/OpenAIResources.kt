package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.KoogHttpMultipartPart
import ai.koog.http.client.KoogHttpResponse
import ai.koog.http.client.postBytes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The provider wire dialect used for Files and Containers requests. */
public enum class OpenAIResourceDialect { OpenAI, Azure }

/** Explicit Files and Containers endpoints. The client never infers a dialect from a hostname. */
public data class OpenAIResourceApiSettings(
    public val dialect: OpenAIResourceDialect,
    public val filesPath: String,
    public val containersPath: String,
    public val queryParameters: Map<String, String> = emptyMap(),
) {
    public companion object {
        /** Creates settings for direct OpenAI v1 resource endpoints. */
        public fun openAI(): OpenAIResourceApiSettings = OpenAIResourceApiSettings(
            OpenAIResourceDialect.OpenAI,
            filesPath = "v1/files",
            containersPath = "v1/containers",
        )

        /** Creates settings for Azure OpenAI v1 resource endpoints with an explicit API version. */
        public fun azure(apiVersion: String): OpenAIResourceApiSettings = OpenAIResourceApiSettings(
            OpenAIResourceDialect.Azure,
            filesPath = "openai/v1/files",
            containersPath = "openai/v1/containers",
            queryParameters = mapOf("api-version" to apiVersion),
        )
    }
}

/** A structured provider error returned by a Files or Containers operation. */
@Serializable
public data class OpenAIResourceError(
    public val message: String,
    public val type: String? = null,
    public val param: String? = null,
    public val code: String? = null,
)

/** Closed result of a resource operation, with a distinct missing-resource branch. */
public sealed interface OpenAIResourceResult<out T> {
    public val dialect: OpenAIResourceDialect
    public val statusCode: Int
    public val headers: Map<String, List<String>>
    public val requestId: String?

    /** A successful response. Value is null for an empty response such as HTTP 204. */
    public data class Success<T>(
        public val value: T?,
        override val dialect: OpenAIResourceDialect,
        override val statusCode: Int,
        override val headers: Map<String, List<String>>,
        override val requestId: String?,
    ) : OpenAIResourceResult<T>

    /** A provider-confirmed missing resource. */
    public data class NotFound(
        public val error: OpenAIResourceError?,
        override val dialect: OpenAIResourceDialect,
        override val statusCode: Int,
        override val headers: Map<String, List<String>>,
        override val requestId: String?,
    ) : OpenAIResourceResult<Nothing>

    /** A non-404 provider failure, including conflict and transient statuses. */
    public data class Failure(
        public val error: OpenAIResourceError?,
        public val errorBody: String?,
        override val dialect: OpenAIResourceDialect,
        override val statusCode: Int,
        override val headers: Map<String, List<String>>,
        override val requestId: String?,
    ) : OpenAIResourceResult<Nothing>
}

/** Metadata for a provider file. */
@Serializable
public data class OpenAIFileResource(
    public val id: String,
    public val bytes: Long,
    @SerialName("created_at") public val createdAt: Long,
    public val filename: String,
    public val purpose: String,
    public val status: String? = null,
    @SerialName("expires_at") public val expiresAt: Long? = null,
    public val `object`: String = "file",
)

/** A provider deletion acknowledgement. */
@Serializable
public data class OpenAIDeletion(
    public val id: String,
    public val deleted: Boolean,
    public val `object`: String? = null,
)

/** Metadata for an OpenAI or Azure hosted-execution container. */
@Serializable
public data class OpenAIContainerResource(
    public val id: String,
    public val name: String,
    @SerialName("created_at") public val createdAt: Long,
    public val status: String? = null,
    @SerialName("last_active_at") public val lastActiveAt: Long? = null,
    public val `object`: String = "container",
)

/** Metadata for a file attached to a hosted-execution container. */
@Serializable
public data class OpenAIContainerFileResource(
    public val id: String,
    public val bytes: Long,
    @SerialName("container_id") public val containerId: String,
    @SerialName("created_at") public val createdAt: Long,
    public val path: String,
    public val source: String,
    public val `object`: String = "container.file",
)

/** A page of container files. */
@Serializable
public data class OpenAIContainerFileList(
    public val data: List<OpenAIContainerFileResource>,
    @SerialName("first_id") public val firstId: String? = null,
    @SerialName("last_id") public val lastId: String? = null,
    @SerialName("has_more") public val hasMore: Boolean = false,
    public val `object`: String = "list",
)

/** Typed OpenAI and Azure Files lifecycle operations. Ownership authorisation remains caller-owned. */
public class OpenAIFilesClient(
    private val httpClient: KoogHttpClient,
    private val settings: OpenAIResourceApiSettings = OpenAIResourceApiSettings.openAI(),
) {
    private val support: OpenAIResourceClientSupport = OpenAIResourceClientSupport(settings)

    /** Uploads exact bytes with an explicit filename and media type. */
    public suspend fun upload(
        filename: String,
        mediaType: String,
        bytes: ByteArray,
        purpose: String = "assistants",
    ): OpenAIResourceResult<OpenAIFileResource> = support.executeJson {
        httpClient.postMultipart(
            settings.filesPath,
            listOf(
                KoogHttpMultipartPart.File("file", filename, mediaType, bytes),
                KoogHttpMultipartPart.Text("purpose", purpose),
            ),
            settings.queryParameters,
        )
    }

    /** Retrieves file metadata. */
    public suspend fun retrieve(fileId: String): OpenAIResourceResult<OpenAIFileResource> = support.executeJson {
        httpClient.getBytes(resourcePath(settings.filesPath, fileId), settings.queryParameters)
    }

    /** Downloads exact file bytes. */
    public suspend fun download(fileId: String): OpenAIResourceResult<ByteArray> = support.executeBytes {
        httpClient.getBytes(resourcePath(settings.filesPath, fileId, "content"), settings.queryParameters)
    }

    /** Deletes a file. */
    public suspend fun delete(fileId: String): OpenAIResourceResult<OpenAIDeletion> = support.executeJson {
        httpClient.delete(resourcePath(settings.filesPath, fileId), settings.queryParameters)
    }
}

/** Typed OpenAI and Azure Containers lifecycle operations. Ownership authorisation remains caller-owned. */
public class OpenAIContainersClient(
    private val httpClient: KoogHttpClient,
    private val settings: OpenAIResourceApiSettings = OpenAIResourceApiSettings.openAI(),
) {
    private val support: OpenAIResourceClientSupport = OpenAIResourceClientSupport(settings)

    /** Creates a hosted-execution container. */
    public suspend fun create(
        name: String,
        fileIds: List<String> = emptyList(),
    ): OpenAIResourceResult<OpenAIContainerResource> = support.executeJson {
        httpClient.postBytes(
            settings.containersPath,
            support.json.encodeToString(CreateContainerRequest(name, fileIds.ifEmpty { null })),
            settings.queryParameters,
            JSON_HEADERS,
        )
    }

    /** Retrieves container metadata. */
    public suspend fun retrieve(containerId: String): OpenAIResourceResult<OpenAIContainerResource> = support.executeJson {
        httpClient.getBytes(resourcePath(settings.containersPath, containerId), settings.queryParameters)
    }

    /** Deletes a container. */
    public suspend fun delete(containerId: String): OpenAIResourceResult<OpenAIDeletion> = support.executeJson {
        httpClient.delete(resourcePath(settings.containersPath, containerId), settings.queryParameters)
    }

    /** Attaches an existing provider file to a container. */
    public suspend fun attachFile(
        containerId: String,
        fileId: String,
    ): OpenAIResourceResult<OpenAIContainerFileResource> = support.executeJson {
        httpClient.postMultipart(
            containerFilesPath(containerId),
            listOf(KoogHttpMultipartPart.Text("file_id", fileId)),
            settings.queryParameters,
        )
    }

    /** Uploads exact bytes directly into a container. */
    public suspend fun uploadFile(
        containerId: String,
        filename: String,
        mediaType: String,
        bytes: ByteArray,
    ): OpenAIResourceResult<OpenAIContainerFileResource> = support.executeJson {
        httpClient.postMultipart(
            containerFilesPath(containerId),
            listOf(KoogHttpMultipartPart.File("file", filename, mediaType, bytes)),
            settings.queryParameters,
        )
    }

    /** Lists files attached to a container. */
    public suspend fun listFiles(
        containerId: String,
        limit: Int? = null,
        order: String? = null,
        after: String? = null,
        before: String? = null,
    ): OpenAIResourceResult<OpenAIContainerFileList> = support.executeJson {
        require(limit == null || limit in 1..100) { "limit must be between 1 and 100" }
        require(order == null || order == "asc" || order == "desc") { "order must be asc or desc" }
        httpClient.getBytes(
            containerFilesPath(containerId),
            settings.queryParameters + buildMap {
                limit?.let { put("limit", it.toString()) }
                order?.let { put("order", it) }
                after?.let { put("after", it) }
                before?.let { put("before", it) }
            },
        )
    }

    /** Retrieves metadata for one container file. */
    public suspend fun retrieveFile(
        containerId: String,
        fileId: String,
    ): OpenAIResourceResult<OpenAIContainerFileResource> = support.executeJson {
        httpClient.getBytes(resourcePath(containerFilesPath(containerId), fileId), settings.queryParameters)
    }

    /** Downloads exact container-file bytes. */
    public suspend fun downloadFile(
        containerId: String,
        fileId: String,
    ): OpenAIResourceResult<ByteArray> = support.executeBytes {
        httpClient.getBytes(resourcePath(containerFilesPath(containerId), fileId, "content"), settings.queryParameters)
    }

    /** Deletes a container file. */
    public suspend fun deleteFile(
        containerId: String,
        fileId: String,
    ): OpenAIResourceResult<OpenAIDeletion> = support.executeJson {
        httpClient.delete(resourcePath(containerFilesPath(containerId), fileId), settings.queryParameters)
    }

    private fun containerFilesPath(containerId: String): String =
        resourcePath(settings.containersPath, containerId, "files")
}

@Serializable
private data class CreateContainerRequest(val name: String, @SerialName("file_ids") val fileIds: List<String>? = null)

@Serializable
private data class ErrorEnvelope(
    val error: OpenAIResourceError? = null,
    val message: String? = null,
    val type: String? = null,
    val param: String? = null,
    val code: String? = null,
)

private class OpenAIResourceClientSupport(
    private val settings: OpenAIResourceApiSettings,
) {
    val json: Json = Json { ignoreUnknownKeys = true }

    suspend inline fun <reified T> executeJson(
        noinline request: suspend () -> KoogHttpResponse<ByteArray>,
    ): OpenAIResourceResult<T> = execute(request) { bytes ->
        bytes.takeIf { it.isNotEmpty() }?.decodeToString()?.let(json::decodeFromString)
    }

    suspend fun executeBytes(
        request: suspend () -> KoogHttpResponse<ByteArray>,
    ): OpenAIResourceResult<ByteArray> = execute(request) { it }

    suspend fun <T> execute(
        request: suspend () -> KoogHttpResponse<ByteArray>,
        decode: (ByteArray) -> T?,
    ): OpenAIResourceResult<T> = try {
        val response = request()
        OpenAIResourceResult.Success(
            decode(response.body), settings.dialect, response.statusCode, response.headers, response.requestId
        )
    } catch (failure: KoogHttpClientException) {
        val statusCode = failure.statusCode ?: throw failure
        val error = decodeError(failure.errorBody)
        if (statusCode == 404) {
            OpenAIResourceResult.NotFound(
                error, settings.dialect, statusCode, failure.responseHeaders, failure.requestId
            )
        } else {
            OpenAIResourceResult.Failure(
                error, failure.errorBody, settings.dialect, statusCode, failure.responseHeaders, failure.requestId
            )
        }
    }

    private fun decodeError(body: String?): OpenAIResourceError? = body?.let { content ->
        runCatching {
            val envelope = json.decodeFromString<ErrorEnvelope>(content)
            envelope.error ?: envelope.message?.let { message ->
                OpenAIResourceError(message, envelope.type, envelope.param, envelope.code)
            }
        }.getOrNull()
    }
}

private val JSON_HEADERS: Map<String, String> = mapOf("Content-Type" to "application/json")

private fun resourcePath(base: String, vararg identifiers: String): String = buildString {
    append(base.trimEnd('/'))
    identifiers.forEach { identifier ->
        require(identifier.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid provider resource identifier" }
        append('/')
        append(identifier)
    }
}
