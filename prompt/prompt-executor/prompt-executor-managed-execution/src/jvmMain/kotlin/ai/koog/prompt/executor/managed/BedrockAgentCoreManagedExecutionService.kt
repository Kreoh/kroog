package ai.koog.prompt.executor.managed

import ai.koog.prompt.provider.ManagedExecutionServiceKind
import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.AccessDeniedException
import aws.sdk.kotlin.services.bedrockagentcore.model.CodeInterpreterResult
import aws.sdk.kotlin.services.bedrockagentcore.model.CodeInterpreterStreamOutput
import aws.sdk.kotlin.services.bedrockagentcore.model.ConflictException
import aws.sdk.kotlin.services.bedrockagentcore.model.ContentBlock
import aws.sdk.kotlin.services.bedrockagentcore.model.InternalServerException
import aws.sdk.kotlin.services.bedrockagentcore.model.InvalidInputException
import aws.sdk.kotlin.services.bedrockagentcore.model.InvokeCodeInterpreterRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.InvokeCodeInterpreterResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.ResourceContentType
import aws.sdk.kotlin.services.bedrockagentcore.model.ResourceNotFoundException
import aws.sdk.kotlin.services.bedrockagentcore.model.ServiceQuotaExceededException
import aws.sdk.kotlin.services.bedrockagentcore.model.StartCodeInterpreterSessionRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.StartCodeInterpreterSessionResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.StopCodeInterpreterSessionRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.ThrottledException
import aws.sdk.kotlin.services.bedrockagentcore.model.ThrottlingException
import aws.sdk.kotlin.services.bedrockagentcore.model.UnauthorizedException
import aws.sdk.kotlin.services.bedrockagentcore.model.ValidationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Amazon Bedrock AgentCore Code Interpreter adapter.
 *
 * The injected [client] remains owned by the caller. The adapter never reads credentials or region configuration,
 * creates a replacement client, or closes the supplied client.
 *
 * Official API reference:
 * https://docs.aws.amazon.com/bedrock-agentcore/latest/APIReference/API_InvokeCodeInterpreter.html
 */
public class BedrockAgentCoreManagedExecutionService internal constructor(
    private val transport: BedrockAgentCoreTransport,
    public val configuration: BedrockAgentCoreConfiguration,
) : ManagedExecutionService {
    override val serviceKind: ManagedExecutionServiceKind = ManagedExecutionServiceKind.BEDROCK_AGENT_CORE

    private val releaseMutex = Mutex()
    private val releasedOwnedSessions = mutableSetOf<SessionKey>()

    public constructor(
        client: BedrockAgentCoreClient,
        configuration: BedrockAgentCoreConfiguration,
    ) : this(
        transport = SdkBedrockAgentCoreTransport(client),
        configuration = configuration,
    ) {
        require(client.config.region == configuration.region) {
            "Bedrock AgentCore client region must match the adapter region"
        }
    }

    override suspend fun acquireSession(
        existing: ManagedExecutionSessionReference?,
    ): ManagedExecutionSession {
        if (existing != null) {
            return ManagedExecutionSession(
                reference = requireReference(existing),
                ownership = ManagedExecutionSessionOwnership.BORROWED,
            )
        }

        val startRequest = BedrockAgentCoreSdkMapper.startRequest(configuration)
        val response = try {
            transport.start(startRequest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw failure.toLifecycleException(BedrockOperation.START)
        }
        val sessionId = response.sessionId.requireSessionId("start response")
        val interpreter = response.codeInterpreterIdentifier
        if (interpreter != configuration.codeInterpreterIdentifier) {
            throw lifecycleProtocolFailure("Bedrock AgentCore start response returned a different interpreter")
        }
        val createdAt = response.createdAt
        val createdAtMillis = try {
            Math.addExact(
                Math.multiplyExact(createdAt.epochSeconds, 1_000L),
                createdAt.nanosecondsOfSecond / 1_000_000L,
            )
        } catch (_: ArithmeticException) {
            throw lifecycleProtocolFailure("Bedrock AgentCore start response returned an invalid creation time")
        }
        return ManagedExecutionSession(
            reference = ManagedExecutionSessionReference.BedrockAgentCore(
                region = configuration.region,
                codeInterpreterIdentifier = interpreter,
                sessionId = sessionId,
                createdAtEpochMilliseconds = createdAtMillis,
                timeoutSeconds = configuration.sessionTimeoutSeconds,
            ),
            ownership = ManagedExecutionSessionOwnership.OWNED,
        )
    }

    override fun execute(
        session: ManagedExecutionSession,
        request: ManagedExecutionRequest,
    ): Flow<ManagedExecutionEvent> = flow {
        try {
            val reference = requireReference(session.reference)
            validateExecutionRequest(request, configuration.maxInputBytes)
            var sequence = 0L
            emit(
                ManagedExecutionEvent.Request(
                    sequence = sequence++,
                    executionId = request.executionId,
                    session = reference,
                    code = request.code,
                    language = request.language.lowercase(),
                )
            )

            val state = BedrockExecutionState(
                reference = reference,
                request = request,
                nextSequence = sequence,
                maxOutputBytes = configuration.maxOutputBytes,
                emitEvent = { event ->
                    try {
                        emit(event)
                    } catch (failure: Throwable) {
                        throw DownstreamEmissionFailure(failure)
                    }
                },
            )
            try {
                if (request.files.isNotEmpty()) {
                    invokeAndRequireSuccess(
                        BedrockAgentCoreSdkMapper.writeFilesRequest(reference, request.files)
                    )
                }
                invokeExecute(BedrockAgentCoreSdkMapper.executeRequest(reference, request), reference, state)
                state.retrievePendingFiles(transport)
            } catch (failure: DownstreamEmissionFailure) {
                throw failure.downstream
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: BedrockStreamFailure) {
                state.recordFailure(failure.kind, failure.code, failure.safeMessage)
            } catch (failure: Throwable) {
                val mapped = failure.toExecutionFailure()
                state.recordFailure(mapped.kind, mapped.code, mapped.safeMessage)
            }
            if (!state.hasSuccessfulTerminal) {
                cleanupOwnedSession(session)
            }
            state.emitTerminal { emit(it) }
        } catch (cancelled: CancellationException) {
            cleanupOwnedSession(session)
            throw cancelled
        } catch (failure: Throwable) {
            cleanupOwnedSession(session)
            throw failure
        }
    }

    override suspend fun releaseSession(session: ManagedExecutionSession) {
        if (session.ownership == ManagedExecutionSessionOwnership.BORROWED) return
        val reference = requireReference(session.reference)
        val key = reference.toKey()
        releaseMutex.withLock {
            if (key in releasedOwnedSessions) return
            try {
                transport.stop(BedrockAgentCoreSdkMapper.stopRequest(configuration, reference))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                throw failure.toLifecycleException(BedrockOperation.STOP)
            }
            releasedOwnedSessions += key
        }
    }

    private suspend fun invokeExecute(
        request: InvokeCodeInterpreterRequest,
        reference: ManagedExecutionSessionReference.BedrockAgentCore,
        state: BedrockExecutionState,
    ) {
        var responseSeen = false
        transport.invoke(request) { response ->
            if (responseSeen) throw protocolFailure("Bedrock AgentCore invoke returned multiple scoped responses")
            responseSeen = true
            val responseSessionId = response.sessionId.requireStreamSessionId("invoke response")
            if (responseSessionId != reference.sessionId) {
                throw protocolFailure("Bedrock AgentCore invoke returned a different session")
            }
            val stream = response.stream
                ?: throw protocolFailure("Bedrock AgentCore invoke response omitted its result stream")
            stream.collect { output -> state.accept(output) }
        }
        if (!responseSeen) throw protocolFailure("Bedrock AgentCore invoke returned no scoped response")
    }

    private suspend fun invokeAndRequireSuccess(request: InvokeCodeInterpreterRequest) {
        var responseSeen = false
        var resultSeen = false
        transport.invoke(request) { response ->
            if (responseSeen) throw protocolFailure("Bedrock AgentCore file tool returned multiple scoped responses")
            responseSeen = true
            if (response.sessionId.requireStreamSessionId("file tool response") != request.sessionId) {
                throw protocolFailure("Bedrock AgentCore file tool returned a different session")
            }
            val stream = response.stream
                ?: throw protocolFailure("Bedrock AgentCore file tool omitted its result stream")
            stream.collect { output ->
                when (output) {
                    is CodeInterpreterStreamOutput.Result -> {
                        resultSeen = true
                        if (output.value.isError == true) {
                            throw BedrockStreamFailure(
                                ManagedExecutionErrorKind.EXECUTION_FAILED,
                                "CODE_INTERPRETER_TOOL_ERROR",
                                "Bedrock AgentCore file operation failed",
                            )
                        }
                    }

                    CodeInterpreterStreamOutput.SdkUnknown -> {
                        throw protocolFailure("Bedrock AgentCore file tool returned an unknown stream event")
                    }
                }
            }
        }
        if (!responseSeen || !resultSeen) {
            throw protocolFailure("Bedrock AgentCore file tool completed without a result")
        }
    }

    private suspend fun cleanupOwnedSession(session: ManagedExecutionSession) {
        if (session.ownership != ManagedExecutionSessionOwnership.OWNED) return
        withContext(NonCancellable) {
            withTimeoutOrNull(configuration.cancellationCleanupTimeoutMillis) {
                try {
                    releaseSession(session)
                } catch (_: Throwable) {
                    // Cancellation cleanup is best effort. The caller's cancellation remains authoritative.
                }
            }
        }
    }

    private fun requireReference(
        value: ManagedExecutionSessionReference,
    ): ManagedExecutionSessionReference.BedrockAgentCore {
        val reference = value as? ManagedExecutionSessionReference.BedrockAgentCore
            ?: throw BedrockAgentCoreLifecycleException(
                kind = ManagedExecutionErrorKind.INVALID_REQUEST,
                providerCode = "WRONG_SESSION_PROVIDER",
                message = "Bedrock AgentCore requires a Bedrock AgentCore session reference",
            )
        if (reference.region != configuration.region) {
            throw BedrockAgentCoreLifecycleException(
                kind = ManagedExecutionErrorKind.INVALID_REQUEST,
                providerCode = "SESSION_REGION_MISMATCH",
                message = "Bedrock AgentCore session region must match the adapter region",
            )
        }
        if (reference.codeInterpreterIdentifier != configuration.codeInterpreterIdentifier) {
            throw BedrockAgentCoreLifecycleException(
                kind = ManagedExecutionErrorKind.INVALID_REQUEST,
                providerCode = "INTERPRETER_MISMATCH",
                message = "Bedrock AgentCore session interpreter must match the configured interpreter",
            )
        }
        reference.sessionId.requireSessionId("session reference")
        require(reference.createdAtEpochMilliseconds >= 0) {
            "Bedrock AgentCore session creation time must be non-negative"
        }
        val officialTimeouts = IntRange(
            BedrockAgentCoreConfiguration.MIN_SESSION_TIMEOUT_SECONDS,
            BedrockAgentCoreConfiguration.MAX_SESSION_TIMEOUT_SECONDS,
        )
        require(reference.timeoutSeconds in officialTimeouts) {
            "Bedrock AgentCore session timeout is outside the official bounds"
        }
        return reference
    }
}

internal interface BedrockAgentCoreTransport {
    suspend fun start(request: StartCodeInterpreterSessionRequest): StartCodeInterpreterSessionResponse

    suspend fun invoke(
        request: InvokeCodeInterpreterRequest,
        block: suspend (InvokeCodeInterpreterResponse) -> Unit,
    )

    suspend fun stop(request: StopCodeInterpreterSessionRequest)
}

private class SdkBedrockAgentCoreTransport(
    private val client: BedrockAgentCoreClient,
) : BedrockAgentCoreTransport {
    override suspend fun start(request: StartCodeInterpreterSessionRequest): StartCodeInterpreterSessionResponse =
        client.startCodeInterpreterSession(request)

    override suspend fun invoke(
        request: InvokeCodeInterpreterRequest,
        block: suspend (InvokeCodeInterpreterResponse) -> Unit,
    ) {
        client.invokeCodeInterpreter(request) { response -> block(response) }
    }

    override suspend fun stop(request: StopCodeInterpreterSessionRequest) {
        client.stopCodeInterpreterSession(request)
    }
}

private class BedrockExecutionState(
    private val reference: ManagedExecutionSessionReference.BedrockAgentCore,
    private val request: ManagedExecutionRequest,
    private var nextSequence: Long,
    private val maxOutputBytes: Long,
    private val emitEvent: suspend (ManagedExecutionEvent) -> Unit,
) {
    private val generatedFiles = mutableListOf<ManagedExecutionGeneratedFile>()
    private val pendingFiles = mutableListOf<PendingFile>()
    private val output = StringBuilder()
    private var outputBytes = 0L
    private var resultSeen = false
    private var failure: BedrockStreamFailure? = null
    private var exitCode: Int? = null
    private var executionTimeSeconds: Double? = null
    private var taskId: String? = null
    private var taskStatus: String? = null
    private var fileOrdinal = 0

    val hasSuccessfulTerminal: Boolean
        get() = resultSeen && failure == null

    suspend fun accept(outputEvent: CodeInterpreterStreamOutput) {
        when (outputEvent) {
            is CodeInterpreterStreamOutput.Result -> acceptResult(outputEvent.value)
            CodeInterpreterStreamOutput.SdkUnknown -> {
                throw protocolFailure("Bedrock AgentCore returned an unknown Code Interpreter stream event")
            }
        }
    }

    suspend fun retrievePendingFiles(transport: BedrockAgentCoreTransport) {
        pendingFiles.forEach { pending ->
            var responseSeen = false
            var resultSeen = false
            transport.invoke(BedrockAgentCoreSdkMapper.readFilesRequest(reference, listOf(pending.path))) { response ->
                if (responseSeen) {
                    throw protocolFailure("Bedrock AgentCore readFiles returned multiple scoped responses")
                }
                responseSeen = true
                if (response.sessionId.requireStreamSessionId("readFiles response") != reference.sessionId) {
                    throw protocolFailure("Bedrock AgentCore readFiles returned a different session")
                }
                val stream = response.stream
                    ?: throw protocolFailure("Bedrock AgentCore readFiles omitted its result stream")
                stream.collect { outputEvent ->
                    when (outputEvent) {
                        is CodeInterpreterStreamOutput.Result -> {
                            resultSeen = true
                            if (outputEvent.value.isError == true) {
                                throw BedrockStreamFailure(
                                    ManagedExecutionErrorKind.EXECUTION_FAILED,
                                    "READ_FILES_ERROR",
                                    "Bedrock AgentCore could not retrieve a generated file",
                                )
                            }
                            val content = outputEvent.value.content.orEmpty()
                            if (content.isEmpty()) {
                                throw protocolFailure("Bedrock AgentCore readFiles returned no file data")
                            }
                            content.forEach { block ->
                                emitFileBytes(pending, requireReadFileChunk(block, pending.path))
                            }
                        }

                        CodeInterpreterStreamOutput.SdkUnknown -> {
                            throw protocolFailure("Bedrock AgentCore readFiles returned an unknown stream event")
                        }
                    }
                }
            }
            if (!responseSeen || !resultSeen) {
                throw protocolFailure("Bedrock AgentCore readFiles completed without a terminal result")
            }
            if (pending.emittedBytes == 0L) {
                throw protocolFailure("Bedrock AgentCore readFiles returned no file data")
            }
            if (pending.sizeBytes != null && pending.sizeBytes != pending.emittedBytes) {
                throw protocolFailure("Bedrock AgentCore readFiles returned a file with an unexpected size")
            }
            emitFileComplete(pending, pending.emittedBytes)
        }
        pendingFiles.clear()
    }

    fun recordFailure(kind: ManagedExecutionErrorKind, code: String, safeMessage: String) {
        if (failure == null) failure = BedrockStreamFailure(kind, code, safeMessage)
    }

    suspend fun emitTerminal(
        emitTerminalEvent: suspend (ManagedExecutionEvent.Terminal) -> Unit,
    ) {
        val terminalFailure = failure
        if (terminalFailure != null) {
            emitTerminalEvent(
                ManagedExecutionEvent.Error(
                    sequence = nextSequence,
                    executionId = request.executionId,
                    session = reference,
                    kind = terminalFailure.kind,
                    message = terminalFailure.safeMessage,
                    providerCode = terminalFailure.code,
                )
            )
            return
        }
        if (!resultSeen) {
            emitTerminalEvent(
                ManagedExecutionEvent.Error(
                    sequence = nextSequence,
                    executionId = request.executionId,
                    session = reference,
                    kind = ManagedExecutionErrorKind.PROTOCOL_FAILURE,
                    message = "Bedrock AgentCore stream completed without a result",
                    providerCode = "MISSING_RESULT",
                )
            )
            return
        }
        emitTerminalEvent(
            ManagedExecutionEvent.Result(
                sequence = nextSequence,
                executionId = request.executionId,
                session = reference,
                output = output.toString().takeIf(String::isNotEmpty),
                exitCode = exitCode,
                executionTimeSeconds = executionTimeSeconds,
                taskId = taskId,
                taskStatus = taskStatus,
                generatedFiles = generatedFiles.toList(),
            )
        )
    }

    private suspend fun acceptResult(result: CodeInterpreterResult) {
        resultSeen = true
        val structured = result.structuredContent
        structured?.stdout?.takeIf(String::isNotEmpty)?.let { text ->
            accountText(text)
            output.append(text)
            emitEvent(
                ManagedExecutionEvent.Stdout(
                    sequence = nextSequence++,
                    executionId = request.executionId,
                    session = reference,
                    text = text,
                )
            )
        }
        structured?.stderr?.takeIf(String::isNotEmpty)?.let { text ->
            accountText(text)
            emitEvent(
                ManagedExecutionEvent.Stderr(
                    sequence = nextSequence++,
                    executionId = request.executionId,
                    session = reference,
                    text = text,
                )
            )
        }
        structured?.exitCode?.let { exitCode = it }
        structured?.executionTime?.let { executionTimeSeconds = it }
        structured?.taskId?.let { taskId = validateSafeMetadata(it, "task ID") }
        structured?.taskStatus?.value?.let { taskStatus = validateSafeMetadata(it, "task status") }

        result.content.orEmpty().forEach { content -> acceptContent(content) }
        if (result.isError == true) {
            recordFailure(
                ManagedExecutionErrorKind.EXECUTION_FAILED,
                "CODE_INTERPRETER_ERROR",
                "Bedrock AgentCore code execution failed",
            )
        }
        if (result.content.isEmpty() && structured == null && result.isError == null) {
            throw protocolFailure("Bedrock AgentCore result omitted all result fields")
        }
    }

    private suspend fun acceptContent(content: ContentBlock) {
        content.text?.takeIf(String::isNotEmpty)?.let { text ->
            accountText(text)
            emitEvent(
                ManagedExecutionEvent.CumulativeOutput(
                    sequence = nextSequence++,
                    executionId = request.executionId,
                    session = reference,
                    output = text,
                )
            )
        }
        content.resource?.text?.takeIf(String::isNotEmpty)?.let { text ->
            accountText(text)
            emitEvent(
                ManagedExecutionEvent.CumulativeOutput(
                    sequence = nextSequence++,
                    executionId = request.executionId,
                    session = reference,
                    output = text,
                )
            )
        }

        val path = content.uri ?: content.resource?.uri ?: content.name
        val bytes = content.data ?: content.resource?.blob
        if (bytes != null) {
            val validatedPath = validateOutputPath(path)
            val pending = pendingFile(content, validatedPath, bytes.size.toLong())
            emitFileBytes(pending, bytes)
            emitFileComplete(pending, bytes.size.toLong())
        } else if (content.uri != null || content.resource?.uri != null) {
            pendingFiles += pendingFile(content, validateOutputPath(path), content.size)
        }
    }

    private fun pendingFile(content: ContentBlock, path: String, sizeBytes: Long?): PendingFile {
        val reference = ManagedExecutionFileReference.BedrockAgentCore(
            region = this.reference.region,
            codeInterpreterIdentifier = this.reference.codeInterpreterIdentifier,
            sessionId = this.reference.sessionId,
            path = path,
        )
        return PendingFile(
            fileId = path,
            path = path,
            reference = reference,
            filename = content.name?.let(::validateFilename),
            mediaType = (content.mimeType ?: content.resource?.mimeType)?.let(::validateMediaType),
            sizeBytes = sizeBytes,
            ordinal = fileOrdinal++,
        )
    }

    private suspend fun emitFileBytes(pending: PendingFile, bytes: ByteArray) {
        val byteCount = bytes.size.toLong()
        val expectedSize = pending.sizeBytes
        if (
            expectedSize != null &&
            (
                expectedSize < 0 ||
                    pending.emittedBytes > expectedSize ||
                    byteCount > expectedSize - pending.emittedBytes
                )
        ) {
            throw protocolFailure("Bedrock AgentCore readFiles returned a file with an unexpected size")
        }
        accountBytes(byteCount)
        val offset = pending.emittedBytes
        emitEvent(
            ManagedExecutionEvent.GeneratedFileChunk(
                sequence = nextSequence++,
                executionId = request.executionId,
                session = reference,
                fileId = pending.fileId,
                reference = pending.reference,
                offset = offset,
                bytes = bytes,
            )
        )
        pending.emittedBytes += byteCount
    }

    private suspend fun emitFileComplete(pending: PendingFile, sizeBytes: Long?) {
        val file = ManagedExecutionGeneratedFile(
            fileId = pending.fileId,
            reference = pending.reference,
            filename = pending.filename,
            mediaType = pending.mediaType,
            sizeBytes = sizeBytes,
        )
        generatedFiles += file
        emitEvent(
            ManagedExecutionEvent.GeneratedFileComplete(
                sequence = nextSequence++,
                executionId = request.executionId,
                session = reference,
                file = file,
            )
        )
    }

    private fun accountText(text: String) {
        accountBytes(text.encodeToByteArray().size.toLong())
    }

    private fun accountBytes(count: Long) {
        if (count < 0 || outputBytes > maxOutputBytes - count) {
            throw protocolFailure("Bedrock AgentCore output exceeded the configured byte limit")
        }
        outputBytes += count
    }
}

private data class PendingFile(
    val fileId: String,
    val path: String,
    val reference: ManagedExecutionFileReference.BedrockAgentCore,
    val filename: String?,
    val mediaType: String?,
    val sizeBytes: Long?,
    val ordinal: Int,
    var emittedBytes: Long = 0,
)

private fun requireReadFileChunk(content: ContentBlock, requestedPath: String): ByteArray {
    val direct = content.data
    val resource = content.resource
    val nested = resource?.blob
    if ((direct == null) == (nested == null)) {
        throw protocolFailure("Bedrock AgentCore readFiles returned missing or ambiguous file data")
    }
    if (direct != null) {
        if (resource != null) {
            throw protocolFailure("Bedrock AgentCore readFiles returned ambiguous file data")
        }
        val identifiers = listOfNotNull(content.uri, content.name)
        if (identifiers.isEmpty() || requestedPath !in identifiers || identifiers.any { it != requestedPath }) {
            throw protocolFailure("Bedrock AgentCore readFiles returned data for an unrelated path")
        }
        if (direct.isEmpty()) {
            throw protocolFailure("Bedrock AgentCore readFiles returned empty file data")
        }
        return direct
    }

    if (resource?.type != ResourceContentType.Blob || resource.uri != requestedPath) {
        throw protocolFailure("Bedrock AgentCore readFiles returned data for an unrelated path")
    }
    if (content.uri != null && content.uri != requestedPath) {
        throw protocolFailure("Bedrock AgentCore readFiles returned ambiguous path metadata")
    }
    if (nested!!.isEmpty()) {
        throw protocolFailure("Bedrock AgentCore readFiles returned empty file data")
    }
    return nested
}

private data class SessionKey(
    val region: String,
    val codeInterpreterIdentifier: String,
    val sessionId: String,
)

private fun ManagedExecutionSessionReference.BedrockAgentCore.toKey(): SessionKey =
    SessionKey(region, codeInterpreterIdentifier, sessionId)

private fun validateExecutionRequest(request: ManagedExecutionRequest, maxInputBytes: Long) {
    require(request.executionId.isNotBlank() && request.executionId.none(Char::isISOControl)) {
        "Managed execution ID must be non-blank and contain no control characters"
    }
    request.language.toProgrammingLanguage()
    var total = request.code.encodeToByteArray().size.toLong()
    require(total <= maxInputBytes) { "Bedrock AgentCore code exceeds the configured input byte limit" }
    val names = mutableSetOf<String>()
    request.files.forEach { file ->
        val filename = validateFilename(file.filename)
        require(names.add(filename)) { "Bedrock AgentCore input filenames must be unique" }
        validateMediaType(file.mediaType)
        val count = file.bytes.size.toLong()
        require(total <= maxInputBytes - count) {
            "Bedrock AgentCore input exceeds the configured byte limit"
        }
        total += count
    }
}

private fun validateFilename(value: String): String {
    require(
        value.isNotBlank() &&
            value.length <= MAX_PATH_LENGTH &&
            value.none(Char::isISOControl) &&
            !value.startsWith("/") &&
            value.split('/').none { it.isEmpty() || it == "." || it == ".." }
    ) {
        "Bedrock AgentCore filename must be a safe non-empty relative path"
    }
    return value
}

private fun validateOutputPath(value: String?): String {
    require(value != null && value.isNotBlank() && value.length <= MAX_PATH_LENGTH) {
        "Bedrock AgentCore generated file omitted a usable path or URI"
    }
    require(value.none(Char::isISOControl)) {
        "Bedrock AgentCore generated file path contains control characters"
    }
    return value
}

private fun validateMediaType(value: String): String {
    require(
        value.length <= MAX_MEDIA_TYPE_LENGTH &&
            value.none { it.isWhitespace() || it.isISOControl() } &&
            MEDIA_TYPE.matches(value)
    ) {
        "Bedrock AgentCore media type must be a valid type/subtype value"
    }
    return value
}

private fun validateSafeMetadata(value: String, label: String): String {
    if (value.length > MAX_METADATA_LENGTH || value.any(Char::isISOControl)) {
        throw protocolFailure("Bedrock AgentCore returned an invalid $label")
    }
    return value
}

private fun String?.requireSessionId(context: String): String {
    if (this == null || !SESSION_ID.matches(this)) {
        throw lifecycleProtocolFailure("Bedrock AgentCore $context returned an invalid session ID")
    }
    return this
}

private fun String?.requireStreamSessionId(context: String): String {
    if (this == null || !SESSION_ID.matches(this)) {
        throw protocolFailure("Bedrock AgentCore $context returned an invalid session ID")
    }
    return this
}

private enum class BedrockOperation {
    START,
    STOP,
}

private data class BedrockStreamFailure(
    val kind: ManagedExecutionErrorKind,
    val code: String,
    val safeMessage: String,
) : Exception(safeMessage)

private class DownstreamEmissionFailure(
    val downstream: Throwable,
) : RuntimeException(null, null, false, false)

private fun protocolFailure(message: String): BedrockStreamFailure =
    BedrockStreamFailure(
        ManagedExecutionErrorKind.PROTOCOL_FAILURE,
        "MALFORMED_PROVIDER_RESPONSE",
        message,
    )

private fun lifecycleProtocolFailure(message: String): BedrockAgentCoreLifecycleException =
    BedrockAgentCoreLifecycleException(
        ManagedExecutionErrorKind.PROTOCOL_FAILURE,
        "MALFORMED_PROVIDER_RESPONSE",
        message,
    )

private fun Throwable.toExecutionFailure(): BedrockStreamFailure = when (this) {
    is AccessDeniedException,
    is UnauthorizedException,
    -> BedrockStreamFailure(
        ManagedExecutionErrorKind.PROVIDER_FAILURE,
        "ACCESS_DENIED",
        "Bedrock AgentCore denied the Code Interpreter request",
    )

    is ConflictException -> BedrockStreamFailure(
        ManagedExecutionErrorKind.PROVIDER_FAILURE,
        "CONFLICT",
        "Bedrock AgentCore reported a session conflict",
    )

    is ResourceNotFoundException -> BedrockStreamFailure(
        ManagedExecutionErrorKind.SESSION_EXPIRED,
        "SESSION_NOT_FOUND",
        "Bedrock AgentCore Code Interpreter session is unavailable",
    )

    is ServiceQuotaExceededException -> BedrockStreamFailure(
        ManagedExecutionErrorKind.PROVIDER_FAILURE,
        "QUOTA_EXCEEDED",
        "Bedrock AgentCore quota was exceeded",
    )

    is ThrottlingException,
    is ThrottledException,
    -> BedrockStreamFailure(
        ManagedExecutionErrorKind.PROVIDER_FAILURE,
        "THROTTLED",
        "Bedrock AgentCore throttled the Code Interpreter request",
    )

    is ValidationException,
    is InvalidInputException,
    -> BedrockStreamFailure(
        ManagedExecutionErrorKind.INVALID_REQUEST,
        "VALIDATION_ERROR",
        "Bedrock AgentCore rejected the Code Interpreter request",
    )

    is InternalServerException -> BedrockStreamFailure(
        ManagedExecutionErrorKind.PROVIDER_FAILURE,
        "INTERNAL_ERROR",
        "Bedrock AgentCore could not complete the Code Interpreter request",
    )

    else -> BedrockStreamFailure(
        ManagedExecutionErrorKind.PROVIDER_FAILURE,
        "SDK_FAILURE",
        "Bedrock AgentCore Code Interpreter request failed",
    )
}

private fun Throwable.toLifecycleException(operation: BedrockOperation): BedrockAgentCoreLifecycleException {
    val mapped = toExecutionFailure()
    val operationName = when (operation) {
        BedrockOperation.START -> "start"
        BedrockOperation.STOP -> "stop"
    }
    return BedrockAgentCoreLifecycleException(
        kind = mapped.kind,
        providerCode = mapped.code,
        message = "Bedrock AgentCore could not $operationName the Code Interpreter session",
    )
}

private val SESSION_ID = Regex("[0-9A-Za-z]{1,40}")
private val MEDIA_TYPE = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
private const val MAX_PATH_LENGTH = 100_000_000
private const val MAX_MEDIA_TYPE_LENGTH = 255
private const val MAX_METADATA_LENGTH = 4_096
