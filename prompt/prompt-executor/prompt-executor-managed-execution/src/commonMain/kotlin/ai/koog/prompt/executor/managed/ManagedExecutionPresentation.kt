package ai.koog.prompt.executor.managed

import ai.koog.prompt.message.ExecutionOrigin
import ai.koog.prompt.message.ManagedExecutionOutputStream
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.message.ManagedExecutionFileReference as PresentationFileReference
import ai.koog.prompt.message.ManagedExecutionSessionReference as PresentationSessionReference

/** Request-local identities needed to present managed events without conflating provider and framework keys. */
public data class ManagedExecutionPresentationContext(
    val toolCallId: String,
    val observerEventIndex: Long,
    val providerItemId: String? = null,
    val index: Int? = null,
)

/**
 * Converts one managed event into its lossless serialisable presentation frame.
 *
 * File chunks remain individual delta frames, so callers retain Flow backpressure and never need to buffer a whole
 * file. The returned frame always has [ExecutionOrigin.CLIENT_MANAGED].
 */
public fun ManagedExecutionEvent.toStreamFrame(
    context: ManagedExecutionPresentationContext,
): StreamFrame = when (this) {
    is ManagedExecutionEvent.Request -> StreamFrame.HostedExecutionUpdate(
        update = MessagePart.HostedExecution.Request(
            code = code,
            language = language,
            executionId = executionId,
            providerItemId = context.providerItemId,
            origin = ExecutionOrigin.CLIENT_MANAGED,
            managedSession = session.toPresentationReference(),
            toolCallId = context.toolCallId,
            managedSequence = sequence,
        ),
        index = context.index,
    )

    is ManagedExecutionEvent.Stdout -> progressFrame(
        context = context,
        text = text,
        stream = ManagedExecutionOutputStream.STDOUT,
    )

    is ManagedExecutionEvent.Stderr -> progressFrame(
        context = context,
        text = text,
        stream = ManagedExecutionOutputStream.STDERR,
    )

    is ManagedExecutionEvent.CumulativeOutput -> StreamFrame.HostedExecutionUpdate(
        update = MessagePart.HostedExecution.CumulativeOutput(
            output = output,
            executionId = executionId,
            providerItemId = context.providerItemId,
            origin = ExecutionOrigin.CLIENT_MANAGED,
            managedSession = session.toPresentationReference(),
            toolCallId = context.toolCallId,
            managedSequence = sequence,
        ),
        index = context.index,
    )

    is ManagedExecutionEvent.GeneratedFileChunk -> StreamFrame.ManagedGeneratedFileBytes(
        origin = ExecutionOrigin.CLIENT_MANAGED,
        managedSession = session.toPresentationReference(),
        managedReference = reference.toPresentationReference(),
        managedSequence = sequence,
        observerEventIndex = context.observerEventIndex,
        toolCallId = context.toolCallId,
        fileId = fileId,
        providerFileId = reference.providerFileId,
        executionId = executionId,
        offset = offset,
        bytes = bytes,
        index = context.index,
    )

    is ManagedExecutionEvent.GeneratedFileComplete -> StreamFrame.GeneratedFileComplete(
        file = file.toPresentationFile(
            executionId = executionId,
            session = session,
            context = context,
            sequence = sequence,
        ),
        index = context.index,
    )

    is ManagedExecutionEvent.Result -> StreamFrame.HostedExecutionUpdate(
        update = MessagePart.HostedExecution.Result(
            output = output,
            exitCode = exitCode,
            generatedFiles = generatedFiles.map {
                it.toPresentationFile(executionId, session, context)
            },
            executionId = executionId,
            providerItemId = context.providerItemId,
            origin = ExecutionOrigin.CLIENT_MANAGED,
            managedSession = session.toPresentationReference(),
            toolCallId = context.toolCallId,
            managedSequence = sequence,
            executionTimeSeconds = executionTimeSeconds,
            taskId = taskId,
            taskStatus = taskStatus,
        ),
        index = context.index,
    )

    is ManagedExecutionEvent.Error -> StreamFrame.HostedExecutionUpdate(
        update = MessagePart.HostedExecution.Error(
            message = message,
            code = providerCode,
            executionId = executionId,
            providerItemId = context.providerItemId,
            origin = ExecutionOrigin.CLIENT_MANAGED,
            managedSession = session.toPresentationReference(),
            toolCallId = context.toolCallId,
            managedSequence = sequence,
            managedErrorKind = kind.name,
        ),
        index = context.index,
    )
}

private fun ManagedExecutionEvent.progressFrame(
    context: ManagedExecutionPresentationContext,
    text: String,
    stream: ManagedExecutionOutputStream,
): StreamFrame = StreamFrame.HostedExecutionUpdate(
    update = MessagePart.HostedExecution.Progress(
        message = text,
        executionId = executionId,
        providerItemId = context.providerItemId,
        origin = ExecutionOrigin.CLIENT_MANAGED,
        managedSession = session.toPresentationReference(),
        toolCallId = context.toolCallId,
        managedSequence = sequence,
        outputStream = stream,
    ),
    index = context.index,
)

/** Converts an executor session reference into its persistence-safe prompt representation. */
public fun ManagedExecutionSessionReference.toPresentationReference(): PresentationSessionReference = when (this) {
    is ManagedExecutionSessionReference.VertexAgentEngine -> PresentationSessionReference.VertexAgentEngine(
        project = project,
        location = location,
        reasoningEngineResource = reasoningEngineResource,
        sandboxResourceName = sandboxResourceName,
        expiresAtEpochMilliseconds = expiresAtEpochMilliseconds,
        codeLanguage = codeLanguage?.name,
    )

    is ManagedExecutionSessionReference.BedrockAgentCore -> PresentationSessionReference.BedrockAgentCore(
        region = region,
        codeInterpreterIdentifier = codeInterpreterIdentifier,
        sessionId = sessionId,
        createdAtEpochMilliseconds = createdAtEpochMilliseconds,
        timeoutSeconds = timeoutSeconds,
    )
}

/** Restores a complete executor session reference after process serialisation. */
public fun PresentationSessionReference.toManagedExecutionReference(): ManagedExecutionSessionReference = when (this) {
    is PresentationSessionReference.VertexAgentEngine -> ManagedExecutionSessionReference.VertexAgentEngine(
        project = project,
        location = location,
        reasoningEngineResource = reasoningEngineResource,
        sandboxResourceName = sandboxResourceName,
        expiresAtEpochMilliseconds = expiresAtEpochMilliseconds,
        codeLanguage = codeLanguage?.let(VertexAgentEngineCodeLanguage::valueOf),
    )

    is PresentationSessionReference.BedrockAgentCore -> ManagedExecutionSessionReference.BedrockAgentCore(
        region = region,
        codeInterpreterIdentifier = codeInterpreterIdentifier,
        sessionId = sessionId,
        createdAtEpochMilliseconds = createdAtEpochMilliseconds,
        timeoutSeconds = timeoutSeconds,
    )
}

private fun ManagedExecutionFileReference.toPresentationReference(): PresentationFileReference = when (this) {
    is ManagedExecutionFileReference.VertexAgentEngine -> PresentationFileReference.VertexAgentEngine(
        sandboxResourceName = sandboxResourceName,
        path = path,
        providerFileId = providerFileId,
    )

    is ManagedExecutionFileReference.BedrockAgentCore -> PresentationFileReference.BedrockAgentCore(
        sessionId = sessionId,
        path = path,
        providerFileId = providerFileId,
        region = region,
        codeInterpreterIdentifier = codeInterpreterIdentifier,
    )
}

private fun ManagedExecutionGeneratedFile.toPresentationFile(
    executionId: String,
    session: ManagedExecutionSessionReference,
    context: ManagedExecutionPresentationContext,
    sequence: Long? = null,
): MessagePart.GeneratedFile = MessagePart.GeneratedFile(
    providerFileId = reference.providerFileId ?: fileId,
    filename = filename,
    mediaType = mediaType,
    sizeBytes = sizeBytes,
    producingExecutionId = executionId,
    providerItemId = context.providerItemId,
    origin = ExecutionOrigin.CLIENT_MANAGED,
    fileId = fileId,
    managedReference = reference.toPresentationReference(),
    managedSession = session.toPresentationReference(),
    toolCallId = context.toolCallId,
    managedSequence = sequence,
)
