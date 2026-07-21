package ai.koog.http.client.ktor

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.KoogHttpMultipartPart
import ai.koog.http.client.KoogHttpResponse
import ai.koog.http.client.mergeHeaders
import ai.koog.utils.io.SuitableForIO
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.CancellationException
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmOverloads
import kotlin.reflect.KClass

/**
 * KtorHttpClient is an implementation of the KoogHttpClient interface, utilizing Ktor's HttpClient
 * to perform HTTP operations, including GET, POST requests and Server-Sent Events (SSE) streaming.
 *
 * This client provides enhanced logging, flexible request and response handling, and supports
 * configurability for underlying Ktor HttpClient instances.
 *
 * @constructor Creates a KtorHttpClient instance with an optional base Ktor HttpClient and configuration block.

 * @property clientName The name of the client, used for logging and traceability.
 * @property logger A logging instance of type KLogger for recording client-related events and errors.
 * @property ktorClient The configured Ktor HttpClient instance used for making HTTP requests.
 * The configuration is applied using the Ktor `HttpClient.config` method.
 */
public class KtorKoogHttpClient internal constructor(
    override val clientName: String,
    private val logger: KLogger,
    public val ktorClient: HttpClient
) : KoogHttpClient {

    /**
     * Secondary constructor for creating a KtorKoogHttpClient with a base Ktor HttpClient and a configurer function.
     *
     * @param clientName The name of the client, used for logging and traceability.
     * @param logger A logging instance of type KLogger for recording client-related events and errors.
     * @param baseClient The base Ktor HttpClient instance used as base to construct [ktorClient] via applying [configurer]
     * @param configurer A lambda function to configure the base Ktor HttpClient instance.
     */
    public constructor(
        clientName: String,
        logger: KLogger,
        baseClient: HttpClient = HttpClient(),
        configurer: HttpClientConfig<out HttpClientEngineConfig>.() -> Unit
    ) : this(clientName, logger, baseClient.config(configurer))

    private suspend fun <R : Any> processResponse(response: HttpResponse, responseType: KClass<R>): R {
        if (response.status.isSuccess()) {
            if (responseType == String::class) {
                @Suppress("UNCHECKED_CAST")
                return response.bodyAsText() as R
            } else {
                return response.body(TypeInfo(responseType))
            }
        }
        throw KoogHttpClientException(
            clientName = clientName,
            statusCode = response.status.value,
            errorBody = response.bodyAsText(),
            responseHeaders = response.headers.asMap(),
            requestId = response.requestId(),
        )
    }

    private suspend fun processByteResponse(response: HttpResponse): KoogHttpResponse<ByteArray> {
        if (response.status.isSuccess()) {
            return KoogHttpResponse(
                body = response.body(),
                statusCode = response.status.value,
                headers = response.headers.asMap(),
                requestId = response.requestId(),
            )
        }
        throw KoogHttpClientException(
            clientName = clientName,
            statusCode = response.status.value,
            errorBody = response.bodyAsText(),
            responseHeaders = response.headers.asMap(),
            requestId = response.requestId(),
        )
    }

    private fun HttpResponse.requestId(): String? =
        REQUEST_ID_HEADERS.firstNotNullOfOrNull { headerName -> headers[headerName] }

    private fun Headers.asMap(): Map<String, List<String>> = entries().associate { it.key to it.value }

    private fun HttpRequestBuilder.applyRequestHeaders(headers: Map<String, String>) {
        headers.forEach { (name, value) ->
            if (name.equals(HttpHeaders.ContentType, ignoreCase = true)) {
                contentType(ContentType.parse(value))
            } else {
                this.headers.remove(name)
                this.headers.append(name, value)
            }
        }
    }

    override suspend fun <R : Any> get(
        path: String,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>
    ): R = withContext(Dispatchers.SuitableForIO) {
        val response = ktorClient.get(path) {
            parameters.forEach { (key, value) ->
                parameter(key, value)
            }
            applyRequestHeaders(headers)
        }
        processResponse(response, responseType)
    }

    override suspend fun getBytes(
        path: String,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): KoogHttpResponse<ByteArray> = withContext(Dispatchers.SuitableForIO) {
        val response = ktorClient.get(path) {
            parameters.forEach { (key, value) -> parameter(key, value) }
            applyRequestHeaders(headers)
        }
        processByteResponse(response)
    }

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>
    ): R = withContext(Dispatchers.SuitableForIO) {
        val response = ktorClient.post(path) {
            if (requestBodyType == String::class) {
                @Suppress("UNCHECKED_CAST")
                setBody(requestBody as String)
            } else {
                setBody(requestBody, TypeInfo(requestBodyType))
            }
            parameters.forEach { (key, value) ->
                parameter(key, value)
            }
            applyRequestHeaders(headers)
        }

        processResponse(response, responseType)
    }

    override suspend fun <T : Any> postBytes(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): KoogHttpResponse<ByteArray> = withContext(Dispatchers.SuitableForIO) {
        val response = ktorClient.post(path) {
            if (requestBodyType == String::class) {
                @Suppress("UNCHECKED_CAST")
                setBody(requestBody as String)
            } else {
                setBody(requestBody, TypeInfo(requestBodyType))
            }
            parameters.forEach { (key, value) -> parameter(key, value) }
            applyRequestHeaders(headers)
        }
        processByteResponse(response)
    }

    override suspend fun postMultipart(
        path: String,
        parts: List<KoogHttpMultipartPart>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): KoogHttpResponse<ByteArray> = withContext(Dispatchers.SuitableForIO) {
        val response = ktorClient.post(path) {
            parameters.forEach { (key, value) -> parameter(key, value) }
            applyRequestHeaders(headers)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        parts.forEach { part ->
                            when (part) {
                                is KoogHttpMultipartPart.Text -> append(part.name, part.value)
                                is KoogHttpMultipartPart.File -> append(
                                    key = part.name,
                                    value = part.bytes,
                                    headers = Headers.build {
                                        append(
                                            HttpHeaders.ContentDisposition,
                                            "form-data; name=\"${part.name}\"; filename=\"${part.filename}\""
                                        )
                                        append(HttpHeaders.ContentType, part.mediaType)
                                    }
                                )
                            }
                        }
                    }
                )
            )
        }
        processByteResponse(response)
    }

    override suspend fun delete(
        path: String,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): KoogHttpResponse<ByteArray> = withContext(Dispatchers.SuitableForIO) {
        val response = ktorClient.request(path) {
            method = HttpMethod.Delete
            parameters.forEach { (key, value) -> parameter(key, value) }
            applyRequestHeaders(headers)
        }
        processByteResponse(response)
    }

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> = flow {
        logger.debug { "Opening sse connection for $clientName" }

        @Suppress("TooGenericExceptionCaught")
        try {
            ktorClient.sse(
                urlString = path,
                request = {
                    method = HttpMethod.Post
                    parameters.forEach { (key, value) ->
                        parameter(key, value)
                    }
                    applyRequestHeaders(
                        mergeHeaders(
                            mapOf(
                                HttpHeaders.Accept to ContentType.Text.EventStream.toString(),
                                HttpHeaders.CacheControl to "no-cache",
                                HttpHeaders.Connection to "keep-alive",
                            ),
                            headers,
                        )
                    )
                    if (requestBodyType == String::class) {
                        @Suppress("UNCHECKED_CAST")
                        setBody(requestBody as String)
                    } else {
                        setBody(requestBody, TypeInfo(requestBodyType))
                    }
                }
            ) {
                incoming.collect { event ->
                    event
                        .takeIf { dataFilter.invoke(it.data) }
                        ?.data?.trim()
                        ?.let(decodeStreamingResponse)
                        ?.let(processStreamingChunk)
                        ?.let { emit(it) }
                }
            }
        } catch (e: SSEClientException) {
            val errorBody = try {
                e.response?.bodyAsText()
            } catch (ignored: Exception) {
                logger.debug(ignored) { "Unable to read SSE error response body (may already be consumed)" }
                null
            }
            throw KoogHttpClientException(
                clientName = clientName,
                statusCode = e.response?.status?.value,
                errorBody = errorBody,
                responseHeaders = e.response?.headers?.asMap().orEmpty(),
                requestId = e.response?.requestId(),
                message = e.message,
                cause = e
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw KoogHttpClientException(
                clientName = clientName,
                message = "Exception during streaming: ${e.message}",
                cause = e,
            )
        }
    }

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> = flow {
        logger.debug { "Opening lines flow for $clientName" }

        try {
            ktorClient.preparePost(path) {
                parameters.forEach { (key, value) ->
                    parameter(key, value)
                }
                applyRequestHeaders(headers)
                if (requestBodyType == String::class) {
                    @Suppress("UNCHECKED_CAST")
                    setBody(requestBody as String)
                } else {
                    setBody(requestBody, TypeInfo(requestBodyType))
                }
            }.execute { response: HttpResponse ->
                if (!response.status.isSuccess()) {
                    throw KoogHttpClientException(
                        clientName = clientName,
                        statusCode = response.status.value,
                        errorBody = response.bodyAsText(),
                        responseHeaders = response.headers.asMap(),
                        requestId = response.requestId(),
                    )
                }

                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank()) continue
                    emit(line)
                }
            }
        } catch (e: KoogHttpClientException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw KoogHttpClientException(
                clientName = clientName,
                message = "Exception during streaming: ${e.message}",
                cause = e,
            )
        }
    }

    override fun close() {
        logger.debug { "Closing $clientName" }
        ktorClient.close()
    }

    private companion object {
        private val REQUEST_ID_HEADERS = listOf("x-request-id", "request-id", "apim-request-id", "x-ms-request-id")
    }

    /**
     * [KoogHttpClient.Factory] implementation backed by Ktor [HttpClient].
     *
     * @property baseClient Base Ktor client used to create configured clients.
     * @property withSse Whether created clients should install Ktor SSE support.
     * @property logger Logger used by created clients.
     */
    public class Factory @JvmOverloads public constructor(
        private val baseClient: HttpClient = HttpClient(),
        private val withSse: Boolean = true,
        private val logger: KLogger = KotlinLogging.logger {}
    ) : KoogHttpClient.Factory {

        @JvmOverloads
        override fun create(
            clientName: String,
            baseUrl: String,
            headers: Map<String, String>,
            queryParameters: Map<String, String>,
            requestTimeoutMillis: Long,
            connectTimeoutMillis: Long,
            socketTimeoutMillis: Long,
            json: Json
        ): KtorKoogHttpClient = KtorKoogHttpClient(
            clientName = clientName,
            logger = logger,
            baseClient = baseClient
        ) {
            val normalizedBaseUrl = URLBuilder(urlString = baseUrl).apply {
                if (!encodedPath.endsWith("/")) {
                    encodedPath += "/"
                }
            }.buildString()

            defaultRequest {
                url.takeFrom(normalizedBaseUrl)
                contentType(ContentType.Application.Json)
                headers.forEach { (name, value) -> header(name, value) }
                queryParameters.forEach { (name, value) -> url.parameters.append(name, value) }
            }

            if (withSse) {
                this.install(SSE)
            }

            this.install(ContentNegotiation) {
                json(json = json)
            }

            this.install(HttpTimeout) {
                this.requestTimeoutMillis = requestTimeoutMillis
                this.connectTimeoutMillis = connectTimeoutMillis
                this.socketTimeoutMillis = socketTimeoutMillis
            }
        }
    }
}

/**
 * Creates a new instance of `KoogHttpClient` using a Ktor-based HTTP client for performing HTTP operations.
 *
 * This function allows configuring the underlying Ktor `HttpClient` through the provided configuration lambda
 * and enables enhanced logging, flexibility, and customization in HTTP interactions.
 *
 * @param clientName The name of the client instance, used for identifying or logging client operations.
 * @param logger A `KLogger` instance used for logging client events and errors.
 * @param baseClient The base Ktor `HttpClient` instance to be used. Defaults to a new Ktor `HttpClient` instance.
 * @param configurer A lambda function to configure the base Ktor `HttpClient` instance. It is applied using
 * Ktor’s `HttpClientConfig`.
 * @return An instance of `KoogHttpClient` configured with the provided parameters.
 */
@JvmOverloads
public fun KoogHttpClient.Companion.fromKtorClient(
    clientName: String,
    logger: KLogger,
    baseClient: HttpClient = HttpClient(),
    configurer: HttpClientConfig<out HttpClientEngineConfig>.() -> Unit = {}
): KoogHttpClient = KtorKoogHttpClient(clientName, logger, baseClient, configurer)
