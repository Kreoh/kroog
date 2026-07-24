package ai.koog.prompt.message

import ai.koog.prompt.Prompt

/** Ordinary custom-tool name used by the client-managed presentation contract. */
public const val CLIENT_MANAGED_EXECUTION_TOOL_NAME: String = "managed_execution"

/** Stable pre-flight rejection category for execution presentation. */
public enum class ManagedExecutionPresentationReplayFailure {
    INVALID_ORIGIN_REFERENCE,
    MISSING_ORDINARY_TOOL_TRANSCRIPT,
    DUPLICATE_TOOL_CALL,
    DUPLICATE_TOOL_RESULT,
    RESULT_BEFORE_CALL,
    PRESENTATION_OUTSIDE_TOOL_TRANSCRIPT,
    TOOL_NAME_MISMATCH,
    CROSS_CALL_PAIRING,
    INTERLEAVED_PRESENTATION,
    MIXED_EXECUTION_ORIGIN,
    EXECUTION_ID_MISMATCH,
    SESSION_MISMATCH,
    PROVIDER_ITEM_MISMATCH,
    PROVIDER_SESSION_MISMATCH,
    MISSING_TERMINAL,
    DUPLICATE_TERMINAL,
    DUPLICATE_FILE_COMPLETION,
}

/** Typed pre-flight failure for execution presentation that cannot be safely mapped to a provider request. */
public class ClientManagedPresentationReplayException(
    public val toolCallId: String?,
    public val reason: ManagedExecutionPresentationReplayFailure =
        ManagedExecutionPresentationReplayFailure.MISSING_ORDINARY_TOOL_TRANSCRIPT,
) : IllegalArgumentException(reason.message)

private val ManagedExecutionPresentationReplayFailure.message: String
    get() = when (this) {
        ManagedExecutionPresentationReplayFailure.INVALID_ORIGIN_REFERENCE ->
            "Execution presentation origin does not match its managed resource references"
        ManagedExecutionPresentationReplayFailure.MISSING_ORDINARY_TOOL_TRANSCRIPT ->
            "Client-managed execution presentation must be paired with its ordinary tool call and result"
        ManagedExecutionPresentationReplayFailure.DUPLICATE_TOOL_CALL ->
            "Client-managed execution presentation has more than one ordinary tool call"
        ManagedExecutionPresentationReplayFailure.DUPLICATE_TOOL_RESULT ->
            "Client-managed execution presentation has more than one ordinary tool result"
        ManagedExecutionPresentationReplayFailure.RESULT_BEFORE_CALL ->
            "Client-managed execution tool result precedes its call"
        ManagedExecutionPresentationReplayFailure.PRESENTATION_OUTSIDE_TOOL_TRANSCRIPT ->
            "Client-managed execution presentation must occur between its ordinary tool call and result"
        ManagedExecutionPresentationReplayFailure.TOOL_NAME_MISMATCH ->
            "Client-managed execution tool call and result names differ"
        ManagedExecutionPresentationReplayFailure.CROSS_CALL_PAIRING ->
            "One managed execution is paired with more than one tool call"
        ManagedExecutionPresentationReplayFailure.INTERLEAVED_PRESENTATION ->
            "Managed execution presentation groups are interleaved"
        ManagedExecutionPresentationReplayFailure.MIXED_EXECUTION_ORIGIN ->
            "One execution mixes native and client-managed presentation"
        ManagedExecutionPresentationReplayFailure.EXECUTION_ID_MISMATCH ->
            "One managed tool call presents more than one execution"
        ManagedExecutionPresentationReplayFailure.SESSION_MISMATCH ->
            "One managed execution presents more than one provider session"
        ManagedExecutionPresentationReplayFailure.PROVIDER_ITEM_MISMATCH ->
            "One managed execution presents contradictory provider item identifiers"
        ManagedExecutionPresentationReplayFailure.PROVIDER_SESSION_MISMATCH ->
            "Managed file and session references identify different provider resources"
        ManagedExecutionPresentationReplayFailure.MISSING_TERMINAL ->
            "Managed execution presentation has no terminal result or error"
        ManagedExecutionPresentationReplayFailure.DUPLICATE_TERMINAL ->
            "Managed execution presentation has more than one terminal result or error"
        ManagedExecutionPresentationReplayFailure.DUPLICATE_FILE_COMPLETION ->
            "Managed execution presentation completes one file more than once"
    }

/** True when this part is presentation-only content from a client-managed execution service. */
public fun MessagePart.isClientManagedExecutionPresentation(): Boolean = when (this) {
    is MessagePart.HostedExecution -> origin == ExecutionOrigin.CLIENT_MANAGED
    is MessagePart.GeneratedFile -> origin == ExecutionOrigin.CLIENT_MANAGED
    else -> false
}

/**
 * Fails before provider request mapping unless every managed presentation is one exact ordered custom-tool transcript.
 *
 * A valid group has one call, presentation from one execution and session, one terminal presentation, and one later
 * result with the same call ID and tool name. Provider prompt replay remains that ordinary custom-tool pair.
 */
public fun Prompt.validateClientManagedExecutionPresentation() {
    val orderedParts = buildList {
        messages.forEach { message ->
            message.parts.forEach { part ->
                add(IndexedPart(size, part))
                if (part is MessagePart.HostedExecution.Result) {
                    part.generatedFiles.forEach { file -> add(IndexedPart(size, file, nestedFile = true)) }
                }
            }
        }
    }
    val presentations = orderedParts.mapNotNull(IndexedPart::asPresentation)

    presentations.forEach { presentation ->
        val hasManagedOnlyIdentity =
            presentation.toolCallId != null ||
                presentation.session != null ||
                presentation.fileId != null ||
                presentation.fileReference != null
        if (
            (presentation.origin == ExecutionOrigin.CLIENT_MANAGED && presentation.session == null) ||
            (
                presentation.origin == ExecutionOrigin.CLIENT_MANAGED &&
                    presentation.fileCompletion &&
                    (presentation.fileId == null || presentation.fileReference == null)
                ) ||
            (presentation.origin == ExecutionOrigin.NATIVE_PROVIDER_HOSTED && hasManagedOnlyIdentity)
        ) {
            presentation.fail(ManagedExecutionPresentationReplayFailure.INVALID_ORIGIN_REFERENCE)
        }
        if (
            presentation.origin == ExecutionOrigin.CLIENT_MANAGED &&
            presentation.fileReference != null &&
            !presentation.fileReference.matches(requireNotNull(presentation.session))
        ) {
            presentation.fail(ManagedExecutionPresentationReplayFailure.PROVIDER_SESSION_MISMATCH)
        }
    }

    val originsByExecution = presentations
        .mapNotNull { presentation ->
            presentation.executionId?.let { executionId -> executionId to presentation.origin }
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    originsByExecution.values.forEach { origins ->
        if (origins.distinct().size > 1) {
            throw ClientManagedPresentationReplayException(
                toolCallId = null,
                reason = ManagedExecutionPresentationReplayFailure.MIXED_EXECUTION_ORIGIN,
            )
        }
    }

    val clientPresentations = presentations.filter {
        it.origin == ExecutionOrigin.CLIENT_MANAGED
    }
    validatePresentationGroupOrdering(clientPresentations)

    val executionOwners = mutableMapOf<String, String>()
    clientPresentations.groupBy(Presentation::requiredToolCallId).forEach { (toolCallId, group) ->
        validatePresentationGroup(
            toolCallId = toolCallId,
            group = group,
            orderedParts = orderedParts,
            executionOwners = executionOwners,
        )
    }
}

private fun ManagedExecutionFileReference.matches(session: ManagedExecutionSessionReference): Boolean = when {
    this is ManagedExecutionFileReference.VertexAgentEngine &&
        session is ManagedExecutionSessionReference.VertexAgentEngine ->
        sandboxResourceName == session.sandboxResourceName

    this is ManagedExecutionFileReference.BedrockAgentCore &&
        session is ManagedExecutionSessionReference.BedrockAgentCore ->
        sessionId == session.sessionId &&
            region == session.region &&
            codeInterpreterIdentifier == session.codeInterpreterIdentifier

    else -> false
}

private fun validatePresentationGroupOrdering(presentations: List<Presentation>) {
    val closedGroups = mutableSetOf<String>()
    var activeGroup: String? = null
    presentations.forEach { presentation ->
        val toolCallId = presentation.requiredToolCallId()
        if (toolCallId != activeGroup) {
            activeGroup?.let(closedGroups::add)
            if (toolCallId in closedGroups) {
                presentation.fail(ManagedExecutionPresentationReplayFailure.INTERLEAVED_PRESENTATION)
            }
            activeGroup = toolCallId
        }
    }
}

private fun validatePresentationGroup(
    toolCallId: String,
    group: List<Presentation>,
    orderedParts: List<IndexedPart>,
    executionOwners: MutableMap<String, String>,
) {
    val calls = orderedParts.filter {
        val part = it.part
        part is MessagePart.Tool.Call && part.id == toolCallId
    }
    val results = orderedParts.filter {
        val part = it.part
        part is MessagePart.Tool.Result && part.id == toolCallId
    }
    if (calls.isEmpty() || results.isEmpty()) {
        group.first().fail(ManagedExecutionPresentationReplayFailure.MISSING_ORDINARY_TOOL_TRANSCRIPT)
    }
    if (calls.size != 1) {
        group.first().fail(ManagedExecutionPresentationReplayFailure.DUPLICATE_TOOL_CALL)
    }
    if (results.size != 1) {
        group.first().fail(ManagedExecutionPresentationReplayFailure.DUPLICATE_TOOL_RESULT)
    }

    val call = calls.single()
    val result = results.single()
    if (result.index < call.index) {
        group.first().fail(ManagedExecutionPresentationReplayFailure.RESULT_BEFORE_CALL)
    }
    if (call.index >= group.first().index || result.index <= group.last().index) {
        group.first().fail(ManagedExecutionPresentationReplayFailure.PRESENTATION_OUTSIDE_TOOL_TRANSCRIPT)
    }
    val callTool = (call.part as MessagePart.Tool.Call).tool
    val resultTool = (result.part as MessagePart.Tool.Result).tool
    if (
        callTool != CLIENT_MANAGED_EXECUTION_TOOL_NAME ||
        resultTool != CLIENT_MANAGED_EXECUTION_TOOL_NAME
    ) {
        group.first().fail(ManagedExecutionPresentationReplayFailure.TOOL_NAME_MISMATCH)
    }

    val executionIds = group.mapNotNull(Presentation::executionId).distinct()
    if (executionIds.size != 1 || group.any { it.executionId == null }) {
        group.first().fail(ManagedExecutionPresentationReplayFailure.EXECUTION_ID_MISMATCH)
    }
    val executionId = executionIds.single()
    val previousOwner = executionOwners.put(executionId, toolCallId)
    if (previousOwner != null && previousOwner != toolCallId) {
        group.first().fail(ManagedExecutionPresentationReplayFailure.CROSS_CALL_PAIRING)
    }

    if (group.map(Presentation::session).distinct().size != 1) {
        group.first().fail(ManagedExecutionPresentationReplayFailure.SESSION_MISMATCH)
    }
    if (group.mapNotNull(Presentation::providerItemId).distinct().size > 1) {
        group.first().fail(ManagedExecutionPresentationReplayFailure.PROVIDER_ITEM_MISMATCH)
    }

    val terminalCount = group.count(Presentation::terminal)
    if (terminalCount == 0) {
        group.first().fail(ManagedExecutionPresentationReplayFailure.MISSING_TERMINAL)
    }
    if (terminalCount > 1) {
        group.first().fail(ManagedExecutionPresentationReplayFailure.DUPLICATE_TERMINAL)
    }

    val completedFileIds = group.filter { it.fileCompletion && !it.nestedFile }.mapNotNull(Presentation::fileId)
    if (completedFileIds.distinct().size != completedFileIds.size) {
        group.first().fail(ManagedExecutionPresentationReplayFailure.DUPLICATE_FILE_COMPLETION)
    }
}

private data class IndexedPart(
    val index: Int,
    val part: MessagePart,
    val nestedFile: Boolean = false,
) {
    fun asPresentation(): Presentation? = when (part) {
        is MessagePart.HostedExecution -> Presentation(
            index = index,
            origin = part.origin,
            toolCallId = part.toolCallId,
            executionId = part.executionId,
            session = part.managedSession,
            providerItemId = part.providerItemId,
            terminal = part is MessagePart.HostedExecution.Result || part is MessagePart.HostedExecution.Error,
        )

        is MessagePart.GeneratedFile -> Presentation(
            index = index,
            origin = part.origin,
            toolCallId = part.toolCallId,
            executionId = part.producingExecutionId,
            session = part.managedSession,
            providerItemId = part.providerItemId,
            fileId = part.fileId,
            fileReference = part.managedReference,
            fileCompletion = true,
            nestedFile = nestedFile,
        )

        else -> null
    }
}

private data class Presentation(
    val index: Int,
    val origin: ExecutionOrigin,
    val toolCallId: String?,
    val executionId: String?,
    val session: ManagedExecutionSessionReference?,
    val providerItemId: String?,
    val terminal: Boolean = false,
    val fileId: String? = null,
    val fileReference: ManagedExecutionFileReference? = null,
    val fileCompletion: Boolean = false,
    val nestedFile: Boolean = false,
) {
    fun requiredToolCallId(): String =
        toolCallId ?: fail(ManagedExecutionPresentationReplayFailure.MISSING_ORDINARY_TOOL_TRANSCRIPT)

    fun fail(reason: ManagedExecutionPresentationReplayFailure): Nothing =
        throw ClientManagedPresentationReplayException(toolCallId, reason)
}
