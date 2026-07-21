package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.KoogHttpMultipartPart
import ai.koog.http.client.KoogHttpResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAIFilesClientTest {
    @Test
    fun testAzureFilesLifecyclePreservesDialectBytesAndErrors() = runTest {
        val requests = mutableListOf<ResourceRequest>()
        val binary = byteArrayOf(0, 4, 0, -1)
        val transport = ResourceTransport { request ->
            requests += request
            when {
                request.method == "MULTIPART" -> response(FILE_JSON)
                request.path.endsWith("/content") -> response(binary)
                request.path.endsWith("missing") -> throw providerFailure(404, "missing", "azure-404")
                request.path.endsWith("conflict") -> throw providerFailure(409, "conflict", "azure-409")
                request.method == "DELETE" -> response(byteArrayOf(), statusCode = 204)
                else -> response(FILE_JSON)
            }
        }
        val client = OpenAIFilesClient(transport, OpenAIResourceApiSettings.azure("preview"))

        val upload = assertIs<OpenAIResourceResult.Success<OpenAIFileResource>>(
            client.upload("zero.bin", "application/octet-stream", binary)
        )
        assertEquals(OpenAIResourceDialect.Azure, upload.dialect)
        assertEquals("file_1", upload.value?.id)
        val uploadRequest = requests.single()
        assertEquals("openai/v1/files", uploadRequest.path)
        assertEquals(mapOf("api-version" to "preview"), uploadRequest.parameters)
        val filePart = assertIs<KoogHttpMultipartPart.File>(uploadRequest.parts?.first())
        assertEquals("zero.bin", filePart.filename)
        assertEquals("application/octet-stream", filePart.mediaType)
        assertContentEquals(binary, filePart.bytes)
        assertEquals(KoogHttpMultipartPart.Text("purpose", "assistants"), uploadRequest.parts?.last())

        val download = assertIs<OpenAIResourceResult.Success<ByteArray>>(client.download("file_1"))
        assertContentEquals(binary, download.value)
        assertEquals("openai/v1/files/file_1/content", requests.last().path)

        val deleted = assertIs<OpenAIResourceResult.Success<OpenAIDeletion>>(client.delete("file_1"))
        assertEquals(204, deleted.statusCode)
        assertNull(deleted.value)

        val missing = assertIs<OpenAIResourceResult.NotFound>(client.retrieve("missing"))
        assertEquals("missing", missing.error?.message)
        assertEquals("azure-404", missing.requestId)

        val conflict = assertIs<OpenAIResourceResult.Failure>(client.retrieve("conflict"))
        assertEquals(409, conflict.statusCode)
        assertEquals("conflict", conflict.error?.message)
        assertEquals("azure-409", conflict.requestId)
    }

    @Test
    fun testOpenAIFilesPathDoesNotDependOnHostname() = runTest {
        var request: ResourceRequest? = null
        val client = OpenAIFilesClient(ResourceTransport {
            request = it
            response(FILE_JSON)
        })

        client.retrieve("file_1")

        assertEquals("v1/files/file_1", request?.path)
        assertEquals(emptyMap(), request?.parameters)
    }
}

class OpenAIContainersClientTest {
    @Test
    fun testCompleteContainerAndContainerFileLifecycle() = runTest {
        val requests = mutableListOf<ResourceRequest>()
        val binary = byteArrayOf(0, 9, 0, -1)
        val transport = ResourceTransport { request ->
            requests += request
            when {
                request.path.endsWith("/content") -> response(binary)
                request.method == "DELETE" -> response(DELETION_JSON)
                request.path.endsWith("/files") && request.method == "GET" -> response(CONTAINER_FILE_LIST_JSON)
                request.path.contains("/files/") -> response(CONTAINER_FILE_JSON)
                request.path.endsWith("/files") -> response(CONTAINER_FILE_JSON)
                else -> response(CONTAINER_JSON)
            }
        }
        val client = OpenAIContainersClient(transport)

        val created = assertIs<OpenAIResourceResult.Success<OpenAIContainerResource>>(
            client.create("chat-1", listOf("file_1"))
        )
        assertEquals("container_1", created.value?.id)
        assertEquals("v1/containers", requests.last().path)
        assertTrue(requests.last().body.orEmpty().contains("\"file_ids\":[\"file_1\"]"))

        client.retrieve("container_1")
        assertEquals("v1/containers/container_1", requests.last().path)

        client.attachFile("container_1", "file_1")
        assertEquals(KoogHttpMultipartPart.Text("file_id", "file_1"), requests.last().parts?.single())

        client.uploadFile("container_1", "zero.bin", "application/octet-stream", binary)
        val upload = assertIs<KoogHttpMultipartPart.File>(requests.last().parts?.single())
        assertContentEquals(binary, upload.bytes)

        val listed = assertIs<OpenAIResourceResult.Success<OpenAIContainerFileList>>(
            client.listFiles("container_1", limit = 20, order = "desc", after = "cfile_0")
        )
        assertEquals("cfile_1", listed.value?.data?.single()?.id)
        assertEquals("20", requests.last().parameters["limit"])
        assertEquals("desc", requests.last().parameters["order"])
        assertEquals("cfile_0", requests.last().parameters["after"])

        client.retrieveFile("container_1", "cfile_1")
        assertEquals("v1/containers/container_1/files/cfile_1", requests.last().path)

        val downloaded = assertIs<OpenAIResourceResult.Success<ByteArray>>(
            client.downloadFile("container_1", "cfile_1")
        )
        assertContentEquals(binary, downloaded.value)

        client.deleteFile("container_1", "cfile_1")
        assertEquals("DELETE", requests.last().method)
        assertEquals("v1/containers/container_1/files/cfile_1", requests.last().path)

        client.delete("container_1")
        assertEquals("DELETE", requests.last().method)
        assertEquals("v1/containers/container_1", requests.last().path)
    }

    @Test
    fun testAzureContainerUrlsAreExplicit() = runTest {
        var request: ResourceRequest? = null
        val client = OpenAIContainersClient(
            ResourceTransport {
                request = it
                response(CONTAINER_JSON)
            },
            OpenAIResourceApiSettings.azure("v1"),
        )

        val result = assertIs<OpenAIResourceResult.Success<OpenAIContainerResource>>(
            client.retrieve("container_1")
        )

        assertEquals(OpenAIResourceDialect.Azure, result.dialect)
        assertEquals("openai/v1/containers/container_1", request?.path)
        assertEquals(mapOf("api-version" to "v1"), request?.parameters)
    }
}

private data class ResourceRequest(
    val method: String,
    val path: String,
    val parameters: Map<String, String>,
    val body: String? = null,
    val parts: List<KoogHttpMultipartPart>? = null,
)

private class ResourceTransport(
    private val handler: suspend (ResourceRequest) -> KoogHttpResponse<ByteArray>,
) : KoogHttpClient {
    override val clientName: String = "ResourceTransport"

    override suspend fun getBytes(
        path: String,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): KoogHttpResponse<ByteArray> = handler(ResourceRequest("GET", path, parameters))

    override suspend fun <T : Any> postBytes(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): KoogHttpResponse<ByteArray> = handler(ResourceRequest("POST", path, parameters, requestBody.toString()))

    override suspend fun postMultipart(
        path: String,
        parts: List<KoogHttpMultipartPart>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): KoogHttpResponse<ByteArray> = handler(ResourceRequest("MULTIPART", path, parameters, parts = parts))

    override suspend fun delete(
        path: String,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): KoogHttpResponse<ByteArray> = handler(ResourceRequest("DELETE", path, parameters))

    override suspend fun <R : Any> get(
        path: String,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = error("unused")

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = error("unused")

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> = emptyFlow()

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> = emptyFlow()

    override fun close() = Unit
}

private fun response(
    body: String,
    statusCode: Int = 200,
): KoogHttpResponse<ByteArray> = response(body.encodeToByteArray(), statusCode)

private fun response(
    body: ByteArray,
    statusCode: Int = 200,
): KoogHttpResponse<ByteArray> = KoogHttpResponse(body, statusCode, emptyMap(), "request-1")

private fun providerFailure(statusCode: Int, message: String, requestId: String): KoogHttpClientException =
    KoogHttpClientException(
        clientName = "fixture",
        statusCode = statusCode,
        errorBody = "{\"error\":{\"message\":\"$message\",\"code\":\"$message\"}}",
        responseHeaders = mapOf("x-request-id" to listOf(requestId)),
        requestId = requestId,
    )

private const val FILE_JSON =
    """{"id":"file_1","bytes":4,"created_at":1,"filename":"zero.bin","purpose":"assistants"}"""
private const val CONTAINER_JSON =
    """{"id":"container_1","name":"chat-1","created_at":1}"""
private const val CONTAINER_FILE_JSON =
    """{"id":"cfile_1","bytes":4,"container_id":"container_1","created_at":1,"path":"/mnt/data/zero.bin","source":"user"}"""
private const val CONTAINER_FILE_LIST_JSON =
    """{"data":[$CONTAINER_FILE_JSON],"first_id":"cfile_1","last_id":"cfile_1","has_more":false}"""
private const val DELETION_JSON = """{"id":"deleted_1","deleted":true}"""
