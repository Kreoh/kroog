@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package ai.koog.prompt.executor.managed

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Official Vertex Agent Engine Code Execution v1 adapter.
 *
 * The adapter owns neither [httpClient] nor credentials. It requests one token per HTTP request and never stores the
 * token. Execute is the provider's unary operation exposed as a cold Flow; cancellation stops local waiting without
 * claiming remote execution cancellation.
 *
 * Wire reference:
 * https://docs.cloud.google.com/gemini-enterprise-agent-platform/reference/rest/v1/projects.locations.reasoningEngines.sandboxEnvironments
 */
public class VertexAgentEngineManagedExecutionService internal constructor(
    private val httpClient: KoogHttpClient,
    private val accessTokenProvider: VertexAccessTokenProvider,
    public val configuration: VertexAgentEngineConfiguration,
    private val sleep: suspend (Long) -> Unit,
    private val nowEpochMilliseconds: () -> Long,
    private val maxPayloadBytes: Long,
) : ManagedExecutionService {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val baseUrl = configuration.baseUrl.trimEnd('/')
    private val parent = configuration.reasoningEngineResource
    private val releaseMutex = Mutex()
    private val releasedOwnedSandboxes = mutableSetOf<String>()

    init {
        require(maxPayloadBytes > 0) { "Vertex payload limit must be positive" }
    }

    public constructor(
        httpClient: KoogHttpClient,
        accessTokenProvider: VertexAccessTokenProvider,
        configuration: VertexAgentEngineConfiguration,
    ) : this(
        httpClient = httpClient,
        accessTokenProvider = accessTokenProvider,
        configuration = configuration,
        sleep = { delay(it) },
        nowEpochMilliseconds = { Clock.System.now().toEpochMilliseconds() },
        maxPayloadBytes = VertexAgentEngineConfiguration.MAX_REQUEST_OR_RESPONSE_BYTES,
    )

    override suspend fun acquireSession(
        existing: ManagedExecutionSessionReference?,
    ): ManagedExecutionSession {
        if (existing != null) {
            val reference = requireVertexReference(existing)
            val wire = authenticatedRequest(VertexRequestContext.GET, contentType = false) { headers ->
                httpClient.get(
                    path = endpoint("v1/${reference.sandboxResourceName}"),
                    responseType = VertexSandboxEnvironmentWire::class,
                    headers = headers,
                )
            }
            val sandbox = sandboxDetails(wire)
            if (sandbox.name != reference.sandboxResourceName) {
                throw lifecycleProtocolFailure("Vertex get response returned a different sandbox")
            }
            val language = sandbox.codeLanguage ?: reference.codeLanguage
                ?: throw VertexAgentEngineLifecycleException(
                    kind = ManagedExecutionErrorKind.INVALID_REQUEST,
                    providerCode = "MISSING_SANDBOX_LANGUAGE",
                    statusCode = null,
                    message = "Vertex borrowed session reference must declare its sandbox language",
                )
            if (language != configuration.codeLanguage) {
                throw VertexAgentEngineLifecycleException(
                    kind = ManagedExecutionErrorKind.INVALID_REQUEST,
                    providerCode = "SANDBOX_LANGUAGE_MISMATCH",
                    statusCode = null,
                    message = "Vertex borrowed sandbox language must match the configured language",
                )
            }
            return ManagedExecutionSession(
                reference.copy(
                    expiresAtEpochMilliseconds = sandbox.expiresAtEpochMilliseconds,
                    codeLanguage = language,
                ),
                ManagedExecutionSessionOwnership.BORROWED,
            )
        }

        val request = VertexCreateSandboxWire(
            displayName = configuration.displayName,
            ttl = "${configuration.sandboxTtlSeconds}s",
            spec = VertexSandboxSpecWire(
                codeExecutionEnvironment = VertexCodeExecutionEnvironmentWire(
                    codeLanguage = configuration.codeLanguage.wireName
                )
            ),
        )
        val initial = authenticatedRequest(VertexRequestContext.CREATE, contentType = true) { headers ->
            httpClient.post(
                path = endpoint("v1/$parent/sandboxEnvironments"),
                requestBody = request,
                requestBodyType = VertexCreateSandboxWire::class,
                responseType = VertexOperationWire::class,
                headers = headers,
            )
        }
        val completed = pollOperation(
            initial = initial,
            requireResponse = true,
            context = VertexRequestContext.POLL_CREATE,
        )
        val sandbox = decodeOperationSandbox(completed)
        val reference = sandboxReference(sandbox)
        return ManagedExecutionSession(reference, ManagedExecutionSessionOwnership.OWNED)
    }

    override fun execute(
        session: ManagedExecutionSession,
        request: ManagedExecutionRequest,
    ): Flow<ManagedExecutionEvent> = flow {
        var sequence = 0L
        emit(
            ManagedExecutionEvent.Request(
                sequence = sequence++,
                executionId = request.executionId,
                session = session.reference,
                code = request.code,
                language = request.language,
            )
        )

        val reference = try {
            requireVertexReference(session.reference)
        } catch (failure: VertexAgentEngineLifecycleException) {
            emit(failure.toExecutionError(sequence, request, session.reference))
            return@flow
        }
        val executeRequest = try {
            buildExecuteRequest(session, reference, request)
        } catch (failure: VertexExecutionProtocolException) {
            emit(failure.toExecutionError(sequence, request, reference))
            return@flow
        }

        val response = try {
            authenticatedRequest(VertexRequestContext.EXECUTE, contentType = true) { headers ->
                httpClient.post(
                    path = endpoint("v1/${reference.sandboxResourceName}:execute"),
                    requestBody = executeRequest,
                    requestBodyType = VertexExecuteSandboxWire::class,
                    responseType = VertexExecuteSandboxResponseWire::class,
                    headers = headers,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: VertexAgentEngineLifecycleException) {
            emit(failure.toExecutionError(sequence, request, reference))
            return@flow
        }

        val generatedFiles = mutableListOf<ManagedExecutionGeneratedFile>()
        val seenFiles = mutableSetOf<String>()
        val stdout = StringBuilder()
        var outputBytes = 0L
        var fileOrdinal = 0

        try {
            val outputs = response.outputs
                ?: throw VertexExecutionProtocolException(
                    ManagedExecutionErrorKind.PROTOCOL_FAILURE,
                    "Vertex execution response omitted outputs",
                    "MISSING_OUTPUTS",
                )
            outputs.forEach { chunk ->
                val encodedData = chunk.data
                    ?: throw protocolFailure("Vertex output chunk omitted data", "MISSING_CHUNK_DATA")
                val decodedSizeLimit = maxPayloadBytes - outputBytes
                val bytes = decodeBase64WithinLimit(encodedData, decodedSizeLimit, "output chunk")
                outputBytes = checkedAdd(
                    outputBytes,
                    bytes.size.toLong(),
                    maxPayloadBytes,
                    "Vertex response exceeds the documented 100 MB limit",
                )
                val mediaType = validateMediaType(chunk.mimeType)
                val encodedFilename = chunk.metadata?.attributes?.get(FILE_NAME_ATTRIBUTE)

                if (encodedFilename != null) {
                    val filename = decodeFilenameAttribute(encodedFilename)
                    val emitted = emitGeneratedFile(
                        sequence = sequence,
                        request = request,
                        reference = reference,
                        filename = filename,
                        mediaType = mediaType,
                        bytes = bytes,
                        fileOrdinal = fileOrdinal,
                        seenFiles = seenFiles,
                    )
                    if (emitted != null) {
                        emit(emitted.chunk)
                        emit(emitted.complete)
                        sequence += 2
                        fileOrdinal++
                        generatedFiles += emitted.file
                    }
                } else if (mediaType == JSON_MEDIA_TYPE) {
                    val parsed = parseJsonOutput(bytes)
                    parsed.stdout?.takeIf(String::isNotEmpty)?.let { text ->
                        emit(
                            ManagedExecutionEvent.Stdout(
                                sequence = sequence++,
                                executionId = request.executionId,
                                session = reference,
                                text = text,
                            )
                        )
                        stdout.append(text)
                    }
                    parsed.stderr?.takeIf(String::isNotEmpty)?.let { text ->
                        emit(
                            ManagedExecutionEvent.Stderr(
                                sequence = sequence++,
                                executionId = request.executionId,
                                session = reference,
                                text = text,
                            )
                        )
                    }
                    parsed.files.forEach { outputFile ->
                        val filename = validateFilename(outputFile.name ?: outputFile.fileName)
                        val fileBytes = decodeBase64WithinLimit(
                            outputFile.content
                                ?: throw protocolFailure(
                                    "Vertex JSON output file omitted content",
                                    "MISSING_FILE_CONTENT",
                                ),
                            maxPayloadBytes,
                            "JSON output file",
                        )
                        val fileMediaType = validateMediaType(
                            outputFile.mimeType ?: outputFile.legacyMimeType ?: BINARY_MEDIA_TYPE
                        )
                        val emitted = emitGeneratedFile(
                            sequence = sequence,
                            request = request,
                            reference = reference,
                            filename = filename,
                            mediaType = fileMediaType,
                            bytes = fileBytes,
                            fileOrdinal = fileOrdinal,
                            seenFiles = seenFiles,
                        )
                        if (emitted != null) {
                            emit(emitted.chunk)
                            emit(emitted.complete)
                            sequence += 2
                            fileOrdinal++
                            generatedFiles += emitted.file
                        }
                    }
                } else {
                    throw protocolFailure(
                        "Vertex raw output chunk omitted file_name metadata",
                        "MISSING_FILE_NAME",
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: VertexExecutionProtocolException) {
            emit(failure.toExecutionError(sequence, request, reference))
            return@flow
        } catch (_: Exception) {
            emit(
                VertexExecutionProtocolException(
                    ManagedExecutionErrorKind.PROTOCOL_FAILURE,
                    "Vertex execution response could not be decoded",
                    "MALFORMED_RESPONSE",
                ).toExecutionError(sequence, request, reference)
            )
            return@flow
        }

        emit(
            ManagedExecutionEvent.Result(
                sequence = sequence,
                executionId = request.executionId,
                session = reference,
                output = stdout.toString().takeIf(String::isNotEmpty),
                exitCode = null,
                generatedFiles = generatedFiles,
            )
        )
    }

    override suspend fun releaseSession(session: ManagedExecutionSession) {
        if (session.ownership == ManagedExecutionSessionOwnership.BORROWED) return
        val reference = requireVertexReference(session.reference)
        val callerContext = currentCoroutineContext()

        withContext(NonCancellable) {
            releaseMutex.withLock {
                if (reference.sandboxResourceName in releasedOwnedSandboxes) return@withLock
                val response = authenticatedRequest(VertexRequestContext.DELETE, contentType = false) { headers ->
                    httpClient.delete(
                        path = endpoint("v1/${reference.sandboxResourceName}"),
                        headers = headers,
                    )
                }
                if (response.statusCode !in 200..299) {
                    throw httpLifecycleFailure(VertexRequestContext.DELETE, response.statusCode)
                }
                val initial = try {
                    json.decodeFromString<VertexOperationWire>(response.body.decodeToString(throwOnInvalidSequence = true))
                } catch (_: Exception) {
                    throw lifecycleProtocolFailure("Vertex delete response was malformed")
                }
                pollOperation(
                    initial = initial,
                    requireResponse = false,
                    context = VertexRequestContext.POLL_DELETE,
                )
                releasedOwnedSandboxes += reference.sandboxResourceName
            }
        }
        callerContext.ensureActive()
    }

    /** Gets one sandbox after validating that it belongs to this configured Vertex parent. */
    public suspend fun getSandbox(
        reference: ManagedExecutionSessionReference.VertexAgentEngine,
    ): VertexAgentEngineSandbox {
        val validated = requireVertexReference(reference)
        val wire = authenticatedRequest(VertexRequestContext.GET, contentType = false) { headers ->
            httpClient.get(
                path = endpoint("v1/${validated.sandboxResourceName}"),
                responseType = VertexSandboxEnvironmentWire::class,
                headers = headers,
            )
        }
        return sandboxDetails(wire)
    }

    /** Lists one official v1 page of sandboxes under this configured reasoning engine. */
    public suspend fun listSandboxes(
        pageSize: Int = 100,
        pageToken: String? = null,
    ): VertexAgentEngineSandboxPage {
        require(pageSize in 1..100) { "Vertex sandbox page size must be between 1 and 100" }
        require(pageToken == null || pageToken.isNotBlank()) { "Vertex sandbox page token must be non-blank" }
        val parameters = buildMap {
            put("pageSize", pageSize.toString())
            pageToken?.let { put("pageToken", it) }
        }
        val response = authenticatedRequest(VertexRequestContext.LIST, contentType = false) { headers ->
            httpClient.get(
                path = endpoint("v1/$parent/sandboxEnvironments"),
                responseType = VertexListSandboxesResponseWire::class,
                parameters = parameters,
                headers = headers,
            )
        }
        return VertexAgentEngineSandboxPage(
            sandboxes = response.sandboxEnvironments.map(::sandboxDetails),
            nextPageToken = response.nextPageToken,
        )
    }

    private fun buildExecuteRequest(
        session: ManagedExecutionSession,
        reference: ManagedExecutionSessionReference.VertexAgentEngine,
        request: ManagedExecutionRequest,
    ): VertexExecuteSandboxWire {
        if (request.executionId.isBlank()) {
            throw invalidExecutionRequest("Managed execution ID must be non-blank", "INVALID_EXECUTION_ID")
        }
        if (request.code.isBlank()) {
            throw invalidExecutionRequest("Vertex execution code must be non-blank", "EMPTY_CODE")
        }
        val language = VertexAgentEngineCodeLanguage.entries.singleOrNull {
            it.requestName == request.language
        } ?: throw invalidExecutionRequest(
            "Vertex Agent Engine supports only python and javascript",
            "UNSUPPORTED_LANGUAGE",
        )
        val sandboxLanguage = reference.codeLanguage
            ?: (if (session.ownership == ManagedExecutionSessionOwnership.OWNED) configuration.codeLanguage else null)
            ?: throw invalidExecutionRequest(
                "Vertex borrowed session reference must declare its sandbox language",
                "MISSING_SANDBOX_LANGUAGE",
            )
        if (language != sandboxLanguage || sandboxLanguage != configuration.codeLanguage) {
            throw invalidExecutionRequest(
                "Vertex execution language must match the sandbox and configured language",
                "SANDBOX_LANGUAGE_MISMATCH",
            )
        }

        val codeBytes = json.encodeToString(VertexCodeInputWire(request.code)).encodeToByteArray()
        var requestBytes = checkedAdd(
            0,
            codeBytes.size.toLong(),
            maxPayloadBytes,
            "Vertex request exceeds the documented 100 MB limit",
            ManagedExecutionErrorKind.INVALID_REQUEST,
        )
        val chunks = mutableListOf(
            VertexChunkWire(
                data = Base64.encode(codeBytes),
                mimeType = JSON_MEDIA_TYPE,
            )
        )
        request.files.forEach { file ->
            val filename = validateInputFilename(file.filename)
            val mediaType = validateInputMediaType(file.mediaType)
            requestBytes = checkedAdd(
                requestBytes,
                file.bytes.size.toLong(),
                maxPayloadBytes,
                "Vertex request exceeds the documented 100 MB limit",
                ManagedExecutionErrorKind.INVALID_REQUEST,
            )
            val filenameBytes = filename.encodeToByteArray()
            requestBytes = checkedAdd(
                requestBytes,
                filenameBytes.size.toLong(),
                maxPayloadBytes,
                "Vertex request exceeds the documented 100 MB limit",
                ManagedExecutionErrorKind.INVALID_REQUEST,
            )
            chunks += VertexChunkWire(
                data = Base64.encode(file.bytes),
                mimeType = mediaType,
                metadata = VertexChunkMetadataWire(
                    attributes = mapOf(FILE_NAME_ATTRIBUTE to Base64.encode(filenameBytes))
                ),
            )
        }
        return VertexExecuteSandboxWire(chunks)
    }

    private suspend fun pollOperation(
        initial: VertexOperationWire,
        requireResponse: Boolean,
        context: VertexRequestContext,
    ): VertexOperationWire {
        val operationName = validateOperationName(
            initial.name ?: throw lifecycleProtocolFailure("Vertex operation omitted its name")
        )
        val startedAt = nowEpochMilliseconds()
        var operation = initial
        while (!operation.done) {
            val elapsed = elapsedMillis(startedAt, nowEpochMilliseconds())
            if (elapsed >= configuration.operationDeadlineMillis) {
                throw VertexAgentEngineLifecycleException(
                    kind = ManagedExecutionErrorKind.PROVIDER_FAILURE,
                    providerCode = "OPERATION_DEADLINE_EXCEEDED",
                    statusCode = null,
                    message = "Vertex operation exceeded the configured polling deadline",
                )
            }
            val remaining = configuration.operationDeadlineMillis - elapsed
            sleep(min(configuration.operationPollIntervalMillis, remaining))
            operation = authenticatedRequest(context, contentType = false) { headers ->
                httpClient.get(
                    path = endpoint("v1/$operationName"),
                    responseType = VertexOperationWire::class,
                    headers = headers,
                )
            }
        }
        operation.error?.let { error ->
            throw VertexAgentEngineLifecycleException(
                kind = mapRpcErrorKind(context, error.code),
                providerCode = error.code?.let { "RPC_$it" },
                statusCode = null,
                message = "Vertex operation failed",
            )
        }
        if (requireResponse && operation.response == null) {
            throw lifecycleProtocolFailure("Vertex completed operation omitted its response")
        }
        return operation
    }

    private fun decodeOperationSandbox(operation: VertexOperationWire): VertexSandboxEnvironmentWire = try {
        json.decodeFromJsonElement(
            operation.response ?: throw lifecycleProtocolFailure("Vertex create operation omitted its response")
        )
    } catch (failure: VertexAgentEngineLifecycleException) {
        throw failure
    } catch (_: Exception) {
        throw lifecycleProtocolFailure("Vertex create operation response was malformed")
    }

    private fun sandboxReference(
        sandbox: VertexSandboxEnvironmentWire,
    ): ManagedExecutionSessionReference.VertexAgentEngine {
        val details = sandboxDetails(sandbox)
        return ManagedExecutionSessionReference.VertexAgentEngine(
            project = configuration.project,
            location = configuration.location,
            reasoningEngineResource = configuration.reasoningEngineResource,
            sandboxResourceName = details.name,
            expiresAtEpochMilliseconds = details.expiresAtEpochMilliseconds,
            codeLanguage = details.codeLanguage ?: configuration.codeLanguage,
        )
    }

    private fun sandboxDetails(sandbox: VertexSandboxEnvironmentWire): VertexAgentEngineSandbox {
        val name = sandbox.name ?: throw lifecycleProtocolFailure("Vertex sandbox response omitted its name")
        try {
            validateSandboxName(name)
        } catch (_: IllegalArgumentException) {
            throw lifecycleProtocolFailure("Vertex sandbox response name was invalid")
        }
        val displayName = sandbox.displayName
            ?: throw lifecycleProtocolFailure("Vertex sandbox response omitted its display name")
        val expiresAt = sandbox.expireTime?.let { value ->
            try {
                Instant.parse(value).toEpochMilliseconds()
            } catch (_: Exception) {
                throw lifecycleProtocolFailure("Vertex sandbox expiration was malformed")
            }
        }
        val codeLanguage = sandbox.spec?.codeExecutionEnvironment?.codeLanguage?.let(::parseCodeLanguage)
        return VertexAgentEngineSandbox(
            name = name,
            displayName = displayName,
            state = sandbox.state,
            expiresAtEpochMilliseconds = expiresAt,
            codeLanguage = codeLanguage,
        )
    }

    private fun requireVertexReference(
        reference: ManagedExecutionSessionReference,
    ): ManagedExecutionSessionReference.VertexAgentEngine {
        val vertex = reference as? ManagedExecutionSessionReference.VertexAgentEngine
            ?: throw VertexAgentEngineLifecycleException(
                kind = ManagedExecutionErrorKind.INVALID_REQUEST,
                providerCode = "WRONG_SESSION_PROVIDER",
                statusCode = null,
                message = "Vertex execution requires a Vertex Agent Engine session",
            )
        try {
            require(vertex.project == configuration.project) {
                "Vertex session project must match the configured project"
            }
            require(vertex.location == configuration.location) {
                "Vertex session location must match the configured location"
            }
            require(vertex.reasoningEngineResource == configuration.reasoningEngineResource) {
                "Vertex session reasoning engine must match the configured reasoning engine"
            }
            validateSandboxName(vertex.sandboxResourceName)
        } catch (_: IllegalArgumentException) {
            throw VertexAgentEngineLifecycleException(
                kind = ManagedExecutionErrorKind.INVALID_REQUEST,
                providerCode = "INVALID_SESSION_REFERENCE",
                statusCode = null,
                message = "Vertex session reference is invalid",
            )
        }
        return vertex
    }

    private fun parseCodeLanguage(value: String): VertexAgentEngineCodeLanguage =
        VertexAgentEngineCodeLanguage.entries.singleOrNull { it.wireName == value }
            ?: throw lifecycleProtocolFailure("Vertex sandbox language was malformed")

    private fun validateSandboxName(name: String) {
        val parts = parseSandboxResource(name)
        val reasoningParts = configuration.reasoningEngineResource.split('/')
        require(parts.project == configuration.project) {
            "Vertex sandbox project must match the configured project"
        }
        require(parts.location == configuration.location) {
            "Vertex sandbox location must match the configured location"
        }
        require(parts.reasoningEngine == reasoningParts.last()) {
            "Vertex sandbox reasoning engine must match the configured reasoning engine"
        }
    }

    private fun validateOperationName(name: String): String {
        val match = OPERATION_RESOURCE.matchEntire(name)
            ?: throw lifecycleProtocolFailure("Vertex operation name was malformed")
        if (match.groupValues[1] != configuration.project || match.groupValues[2] != configuration.location) {
            throw lifecycleProtocolFailure("Vertex operation name did not match the configured project and location")
        }
        return name
    }

    private suspend fun <T> authenticatedRequest(
        context: VertexRequestContext,
        contentType: Boolean,
        block: suspend (Map<String, String>) -> T,
    ): T {
        val token = try {
            accessTokenProvider.getAccessToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw VertexAgentEngineLifecycleException(
                kind = ManagedExecutionErrorKind.PROVIDER_FAILURE,
                providerCode = "AUTHENTICATION_FAILED",
                statusCode = null,
                message = "Vertex access token acquisition failed",
            )
        }
        if (token.isBlank() || token.any { it.isWhitespace() || it.isISOControl() }) {
            throw VertexAgentEngineLifecycleException(
                kind = ManagedExecutionErrorKind.INVALID_REQUEST,
                providerCode = "INVALID_ACCESS_TOKEN",
                statusCode = null,
                message = "Vertex access token was invalid",
            )
        }
        val headers = buildMap {
            put(AUTHORIZATION_HEADER, "Bearer $token")
            if (contentType) put(CONTENT_TYPE_HEADER, JSON_MEDIA_TYPE)
        }
        return try {
            block(headers)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: VertexAgentEngineLifecycleException) {
            throw failure
        } catch (_: SerializationException) {
            throw VertexAgentEngineLifecycleException(
                kind = ManagedExecutionErrorKind.PROTOCOL_FAILURE,
                providerCode = "MALFORMED_PROVIDER_RESPONSE",
                statusCode = null,
                message = "Vertex response was malformed",
            )
        } catch (failure: KoogHttpClientException) {
            throw httpLifecycleFailure(context, failure.statusCode)
        } catch (_: Exception) {
            throw VertexAgentEngineLifecycleException(
                kind = ManagedExecutionErrorKind.PROVIDER_FAILURE,
                providerCode = "HTTP_CLIENT_FAILURE",
                statusCode = null,
                message = "Vertex request failed",
            )
        }
    }

    private fun endpoint(path: String): String = "$baseUrl/$path"

    private fun httpLifecycleFailure(
        context: VertexRequestContext,
        statusCode: Int?,
    ): VertexAgentEngineLifecycleException =
        VertexAgentEngineLifecycleException(
            kind = mapHttpErrorKind(context, statusCode),
            providerCode = statusCode?.let { "HTTP_$it" },
            statusCode = statusCode,
            message = requestFailureMessage(context, statusCode),
        )

    private fun lifecycleProtocolFailure(message: String): VertexAgentEngineLifecycleException =
        VertexAgentEngineLifecycleException(
            kind = ManagedExecutionErrorKind.PROTOCOL_FAILURE,
            providerCode = "MALFORMED_PROVIDER_RESPONSE",
            statusCode = null,
            message = message,
        )
}

private enum class VertexRequestContext {
    CREATE,
    POLL_CREATE,
    GET,
    LIST,
    EXECUTE,
    DELETE,
    POLL_DELETE,
}

private data class ParsedVertexJsonOutput(
    val stdout: String?,
    val stderr: String?,
    val files: List<VertexJsonOutputFileWire>,
)

private data class EmittedVertexFile(
    val chunk: ManagedExecutionEvent.GeneratedFileChunk,
    val complete: ManagedExecutionEvent.GeneratedFileComplete,
    val file: ManagedExecutionGeneratedFile,
)

private class VertexExecutionProtocolException(
    val kind: ManagedExecutionErrorKind,
    override val message: String,
    val providerCode: String,
) : Exception(message)

private fun parseJsonOutput(bytes: ByteArray): ParsedVertexJsonOutput {
    val root = try {
        EXECUTION_JSON.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
    } catch (_: Exception) {
        throw protocolFailure("Vertex JSON output chunk was malformed", "MALFORMED_OUTPUT_JSON")
    }
    val stdout = root.requiredString("msg_out")
    val stderr = root.requiredString("msg_err")
    val filesElement = root["output_files"]
    val files = when (filesElement) {
        null -> emptyList()
        is JsonArray -> filesElement.map { element ->
            try {
                EXECUTION_JSON.decodeFromJsonElement<VertexJsonOutputFileWire>(element)
            } catch (_: Exception) {
                throw protocolFailure(
                    "Vertex JSON output file metadata was malformed",
                    "MALFORMED_OUTPUT_FILE",
                )
            }
        }
        else -> throw protocolFailure(
            "Vertex output_files must be an array",
            "MALFORMED_OUTPUT_FILES",
        )
    }
    return ParsedVertexJsonOutput(stdout, stderr, files)
}

private fun JsonObject.requiredString(name: String): String {
    val value = this[name] as? JsonPrimitive
        ?: throw protocolFailure("Vertex JSON output omitted $name", "MISSING_OUTPUT_FIELD")
    if (!value.isString) {
        throw protocolFailure("Vertex JSON output field $name was not a string", "INVALID_OUTPUT_FIELD")
    }
    return value.content
}

private fun emitGeneratedFile(
    sequence: Long,
    request: ManagedExecutionRequest,
    reference: ManagedExecutionSessionReference.VertexAgentEngine,
    filename: String,
    mediaType: String,
    bytes: ByteArray,
    fileOrdinal: Int,
    seenFiles: MutableSet<String>,
): EmittedVertexFile? {
    val identity = "$filename:${sha256Hex(bytes)}"
    if (!seenFiles.add(identity)) return null
    val fileId = "${request.executionId}:vertex-output:$fileOrdinal"
    val fileReference = ManagedExecutionFileReference.VertexAgentEngine(
        sandboxResourceName = reference.sandboxResourceName,
        path = filename,
        providerFileId = null,
    )
    val file = ManagedExecutionGeneratedFile(
        fileId = fileId,
        reference = fileReference,
        filename = filename,
        mediaType = mediaType,
        sizeBytes = bytes.size.toLong(),
    )
    return EmittedVertexFile(
        chunk = ManagedExecutionEvent.GeneratedFileChunk(
            sequence = sequence,
            executionId = request.executionId,
            session = reference,
            fileId = fileId,
            reference = fileReference,
            offset = 0,
            bytes = bytes,
        ),
        complete = ManagedExecutionEvent.GeneratedFileComplete(
            sequence = sequence + 1,
            executionId = request.executionId,
            session = reference,
            file = file,
        ),
        file = file,
    )
}

private fun validateFilename(value: String?): String {
    val filename = value ?: throw protocolFailure("Vertex file omitted its filename", "MISSING_FILE_NAME")
    if (
        filename.isBlank() ||
        filename == "." ||
        filename == ".." ||
        '/' in filename ||
        '\\' in filename ||
        filename.any(Char::isISOControl) ||
        filename.encodeToByteArray().size > MAX_FILENAME_BYTES
    ) {
        throw protocolFailure("Vertex filename was unsafe", "INVALID_FILE_NAME")
    }
    return filename
}

private fun validateInputFilename(value: String): String = try {
    validateFilename(value)
} catch (failure: VertexExecutionProtocolException) {
    throw invalidExecutionRequest(failure.message, failure.providerCode)
}

private fun validateMediaType(value: String?): String {
    val mediaType = value ?: throw protocolFailure("Vertex chunk omitted mimeType", "MISSING_MIME_TYPE")
    val parts = mediaType.split('/')
    if (
        mediaType.isBlank() ||
        mediaType.any { it.isWhitespace() || it.isISOControl() } ||
        parts.size != 2 ||
        parts.any(String::isBlank)
    ) {
        throw protocolFailure("Vertex chunk mimeType was invalid", "INVALID_MIME_TYPE")
    }
    return mediaType
}

private fun validateInputMediaType(value: String): String = try {
    validateMediaType(value)
} catch (failure: VertexExecutionProtocolException) {
    throw invalidExecutionRequest(failure.message, failure.providerCode)
}

private fun decodeFilenameAttribute(encoded: String): String {
    val bytes = decodeBase64WithinLimit(encoded, MAX_FILENAME_BYTES.toLong(), "file_name metadata")
    val filename = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: Exception) {
        throw protocolFailure("Vertex file_name metadata was not UTF-8", "INVALID_FILE_NAME")
    }
    return validateFilename(filename)
}

private fun decodeBase64WithinLimit(
    encoded: String,
    remainingBytes: Long,
    label: String,
): ByteArray {
    if (remainingBytes < 0 || estimatedDecodedSize(encoded.length) > remainingBytes) {
        throw protocolFailure("$label exceeded the documented size limit", "SIZE_LIMIT_EXCEEDED")
    }
    val decoded = try {
        Base64.decode(encoded)
    } catch (_: IllegalArgumentException) {
        throw protocolFailure("$label contained malformed base64", "MALFORMED_BASE64")
    }
    if (decoded.size.toLong() > remainingBytes) {
        throw protocolFailure("$label exceeded the documented size limit", "SIZE_LIMIT_EXCEEDED")
    }
    return decoded
}

private fun estimatedDecodedSize(encodedLength: Int): Long =
    (encodedLength.toLong() / 4L) * 3L + if (encodedLength % 4 == 0) 0L else 3L

private fun checkedAdd(
    total: Long,
    addition: Long,
    limit: Long,
    message: String,
    kind: ManagedExecutionErrorKind = ManagedExecutionErrorKind.PROTOCOL_FAILURE,
): Long {
    if (addition < 0 || total < 0 || addition > limit - total) {
        throw VertexExecutionProtocolException(kind, message, "SIZE_LIMIT_EXCEEDED")
    }
    return total + addition
}

private fun invalidExecutionRequest(
    message: String,
    providerCode: String,
): VertexExecutionProtocolException = VertexExecutionProtocolException(
    kind = ManagedExecutionErrorKind.INVALID_REQUEST,
    message = message,
    providerCode = providerCode,
)

private fun protocolFailure(
    message: String,
    providerCode: String,
): VertexExecutionProtocolException = VertexExecutionProtocolException(
    kind = ManagedExecutionErrorKind.PROTOCOL_FAILURE,
    message = message,
    providerCode = providerCode,
)

private fun VertexExecutionProtocolException.toExecutionError(
    sequence: Long,
    request: ManagedExecutionRequest,
    reference: ManagedExecutionSessionReference,
): ManagedExecutionEvent.Error = ManagedExecutionEvent.Error(
    sequence = sequence,
    executionId = request.executionId,
    session = reference,
    kind = kind,
    message = message,
    providerCode = providerCode,
)

private fun VertexAgentEngineLifecycleException.toExecutionError(
    sequence: Long,
    request: ManagedExecutionRequest,
    reference: ManagedExecutionSessionReference,
): ManagedExecutionEvent.Error = ManagedExecutionEvent.Error(
    sequence = sequence,
    executionId = request.executionId,
    session = reference,
    kind = kind,
    message = message ?: "Vertex execution failed",
    providerCode = providerCode,
)

private fun mapHttpErrorKind(
    context: VertexRequestContext,
    statusCode: Int?,
): ManagedExecutionErrorKind = when (statusCode) {
    400, 409, 422 -> ManagedExecutionErrorKind.INVALID_REQUEST
    404, 410 -> when (context) {
        VertexRequestContext.GET,
        VertexRequestContext.EXECUTE,
        VertexRequestContext.DELETE,
        -> ManagedExecutionErrorKind.SESSION_EXPIRED

        VertexRequestContext.CREATE,
        VertexRequestContext.POLL_CREATE,
        VertexRequestContext.LIST,
        VertexRequestContext.POLL_DELETE,
        -> ManagedExecutionErrorKind.PROVIDER_FAILURE
    }
    else -> ManagedExecutionErrorKind.PROVIDER_FAILURE
}

private fun mapRpcErrorKind(
    context: VertexRequestContext,
    code: Int?,
): ManagedExecutionErrorKind = when (code) {
    3, 6, 9 -> ManagedExecutionErrorKind.INVALID_REQUEST
    5 -> when (context) {
        VertexRequestContext.GET,
        VertexRequestContext.EXECUTE,
        VertexRequestContext.DELETE,
        -> ManagedExecutionErrorKind.SESSION_EXPIRED

        VertexRequestContext.CREATE,
        VertexRequestContext.POLL_CREATE,
        VertexRequestContext.LIST,
        VertexRequestContext.POLL_DELETE,
        -> ManagedExecutionErrorKind.PROVIDER_FAILURE
    }
    else -> ManagedExecutionErrorKind.PROVIDER_FAILURE
}

private fun requestFailureMessage(
    context: VertexRequestContext,
    statusCode: Int?,
): String = when {
    statusCode !in setOf(404, 410) -> "Vertex request failed"
    context == VertexRequestContext.GET -> "Vertex sandbox was not found"
    context == VertexRequestContext.EXECUTE -> "Vertex sandbox was not found during execution"
    context == VertexRequestContext.DELETE -> "Vertex sandbox was not found during deletion"
    context == VertexRequestContext.CREATE -> "Vertex sandbox parent was not found during creation"
    context == VertexRequestContext.LIST -> "Vertex sandbox parent was not found during listing"
    else -> "Vertex operation was not found during polling"
}

private fun elapsedMillis(startedAt: Long, current: Long): Long =
    if (current <= startedAt) 0 else current - startedAt

private val EXECUTION_JSON = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private val OPERATION_RESOURCE = Regex(
    "projects/([A-Za-z0-9._~-]+)/locations/([A-Za-z0-9._~-]+)/operations/([A-Za-z0-9._~-]+)"
)

private const val AUTHORIZATION_HEADER = "Authorization"
private const val CONTENT_TYPE_HEADER = "Content-Type"
private const val FILE_NAME_ATTRIBUTE = "file_name"
private const val JSON_MEDIA_TYPE = "application/json"
private const val BINARY_MEDIA_TYPE = "application/octet-stream"
private const val MAX_FILENAME_BYTES = 255
