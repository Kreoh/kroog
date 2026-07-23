package ai.koog.agents.core.environment

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.config.MissingToolsConversionStrategy
import ai.koog.agents.core.agent.config.ToolCallDescriber
import ai.koog.agents.core.agent.context.AIAgentContext
import ai.koog.agents.core.agent.context.AIAgentLLMContext
import ai.koog.agents.core.agent.context.DetachedPromptExecutorAPI
import ai.koog.agents.core.agent.entity.AIAgentStateManager
import ai.koog.agents.core.agent.entity.AIAgentStorage
import ai.koog.agents.core.agent.execution.AgentExecutionInfo
import ai.koog.agents.core.agent.tools.AgentContextAwareTool
import ai.koog.agents.core.agent.tools.ManagedExecutionTool
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.agents.core.feature.pipeline.AIAgentPipeline
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import ai.koog.utils.time.KoogClock
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Represents an AI agent environment that operates within the context of a specific agent framework.
 *
 * This class acts as a decorator over an existing [AIAgentEnvironment], augmenting operations with contextual
 * processing using the provided [AIAgentContext].
 *
 * Metadata handling: when [executeTool] is called with a [ToolCallMetadata] argument, this environment
 * collects metadata contributions from every feature that registered a handler via
 * [ai.koog.agents.core.feature.pipeline.AIAgentPipelineAPI.provideToolCallMetadata] and merges them with
 * the caller-supplied metadata. On key collision, the caller's value wins, so an explicit call-site
 * override is never silently replaced by a feature contribution. After the merge, the framework injects
 * the live [AIAgentContext] under [AgentContextAwareTool.AgentContextKey]; the framework's value always
 * wins over caller and feature entries so a tool always observes the real context driving the current
 * call. The merged metadata is then passed to the wrapped environment, which threads it into
 * [ai.koog.agents.core.tools.ToolBase.execute].
 *
 * @constructor Constructs a new instance of [ContextualAgentEnvironment] with a decorated [environment] and a
 * contextual [context].
 *
 * @param environment The underlying agent environment responsible for managing tool execution.
 * @param context The context that augments the environment with additional behavioral and execution information.
 */
@InternalAgentsApi
public class ContextualAgentEnvironment(
    private val environment: AIAgentEnvironment,
    private val context: AIAgentContext,
) : AIAgentEnvironment {

    private companion object {
        private const val MANAGED_EXECUTION_DATA_REDACTED = "<managed-execution-data-redacted>"
        private val managedExecutionPipelineArgs = JSONObject(
            mapOf("data" to JSONPrimitive(MANAGED_EXECUTION_DATA_REDACTED))
        )
        private val managedExecutionPipelineResult = JSONPrimitive(MANAGED_EXECUTION_DATA_REDACTED)
        private val logger = KotlinLogging.logger { }

        private fun managedExecutionPipelineError(): Throwable =
            IllegalStateException(MANAGED_EXECUTION_DATA_REDACTED)
    }

    override suspend fun executeTool(toolCall: MessagePart.Tool.Call): ReceivedToolResult =
        executeTool(toolCall, ToolCallMetadata.EMPTY)

    override suspend fun executeTool(
        toolCall: MessagePart.Tool.Call,
        metadata: ToolCallMetadata,
    ): ReceivedToolResult {
        @OptIn(ExperimentalUuidApi::class)
        val eventId = Uuid.random().toString()
        val tool = context.llm.toolRegistry.getToolOrNull(toolCall.tool)
        val toolDescription = tool?.descriptor?.description
        val isManagedExecution = tool is ManagedExecutionTool<*, *>
        val callbackContext = if (isManagedExecution) {
            createManagedCallbackContext()
        } else {
            context
        }

        val toolArgs = try {
            toolCall.argsJson.toKoogJSONObject()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isManagedExecution) {
                logger.error {
                    "Failed to parse arguments for managed tool '${toolCall.tool}' with call id '${toolCall.id}' " +
                        "(data: $MANAGED_EXECUTION_DATA_REDACTED)"
                }
            } else {
                logger.error { "Failed to execute tool call with id '${toolCall.id}' while parsing args: ${e.message}" }
            }

            val tool = toolCall.tool
            val toolArgs = JSONObject(emptyMap())
            val message = "Failed to parse tool arguments: ${e.message}"
            context.pipeline.onToolValidationFailed(
                eventId = eventId,
                executionInfo = callbackContext.executionInfo,
                context = callbackContext,
                runId = callbackContext.runId,
                toolCallId = toolCall.id,
                toolName = tool,
                toolDescription = if (isManagedExecution) MANAGED_EXECUTION_DATA_REDACTED else toolDescription,
                toolArgs = if (isManagedExecution) managedExecutionPipelineArgs else toolArgs,
                message = if (isManagedExecution) MANAGED_EXECUTION_DATA_REDACTED else message,
                error = if (isManagedExecution) managedExecutionPipelineError() else e,
            )
            return ReceivedToolResult(
                id = toolCall.id,
                tool = tool,
                toolArgs = toolArgs,
                toolDescription = null,
                output = message,
                resultKind = ToolResultKind.ValidationError(e),
                result = null
            )
        }

        if (isManagedExecution) {
            logger.trace {
                "Executing managed tool call (" +
                    "tool call id: ${toolCall.id}, " +
                    "tool: ${toolCall.tool}, " +
                    "data: $MANAGED_EXECUTION_DATA_REDACTED)"
            }
        } else {
            logger.trace {
                "Executing tool call (" +
                    "event id: $eventId, " +
                    "run id: ${context.runId}, " +
                    "tool call id: ${toolCall.id}, " +
                    "tool: ${toolCall.tool}, " +
                    "args: $toolArgs)"
            }
        }

        context.pipeline.onToolCallStarting(
            eventId = eventId,
            executionInfo = callbackContext.executionInfo,
            context = callbackContext,
            runId = callbackContext.runId,
            toolCallId = toolCall.id,
            toolName = toolCall.tool,
            toolDescription = if (isManagedExecution) MANAGED_EXECUTION_DATA_REDACTED else toolDescription,
            toolArgs = if (isManagedExecution) managedExecutionPipelineArgs else toolArgs,
        )

        val featureMetadata = context.pipeline.collectToolCallMetadata(
            eventId = eventId,
            executionInfo = callbackContext.executionInfo,
            runId = callbackContext.runId,
            toolCallId = toolCall.id,
            toolName = toolCall.tool,
            toolDescription = if (isManagedExecution) MANAGED_EXECUTION_DATA_REDACTED else toolDescription,
            toolArgs = if (isManagedExecution) managedExecutionPipelineArgs else toolArgs,
            context = callbackContext
        )

        // Caller-supplied metadata wins on key collision, so an explicit call-site override is never
        // silently replaced by a feature contribution. The framework's live AIAgentContext is then injected
        // under the reserved key so that tools always see the real context driving the current call.
        val mergedMetadata = featureMetadata + metadata +
            ToolCallMetadata.of(AgentContextAwareTool.AgentContextKey to context)

        val toolResult = environment.executeTool(toolCall, mergedMetadata)
        processToolResult(eventId, callbackContext, toolResult)

        if (isManagedExecution) {
            logger.trace {
                "Managed tool call completed (" +
                    "tool call id: ${toolCall.id}, " +
                    "tool: ${toolCall.tool}, " +
                    "data: $MANAGED_EXECUTION_DATA_REDACTED)"
            }
        } else {
            logger.trace {
                "Tool call completed (" +
                    "event id: ${toolResult.id}, " +
                    "execution info: ${context.executionInfo.path()}, " +
                    "run id: ${context.runId}, " +
                    "tool call id: ${toolCall.id}, " +
                    "tool: ${toolCall.tool}, " +
                    "tool description: ${toolResult.toolDescription}, " +
                    "args: $toolArgs) " +
                    "with result: $toolResult"
            }
        }

        return toolResult
    }

    override suspend fun reportProblem(exception: Throwable) {
        environment.reportProblem(exception)
    }

    //region Private Methods

    private suspend fun processToolResult(
        eventId: String,
        callbackContext: AIAgentContext,
        toolResult: ReceivedToolResult
    ) {
        val isManagedExecution =
            context.llm.toolRegistry.getToolOrNull(toolResult.tool) is ManagedExecutionTool<*, *>
        val pipelineDescription =
            if (isManagedExecution) MANAGED_EXECUTION_DATA_REDACTED else toolResult.toolDescription
        val pipelineArgs = if (isManagedExecution) managedExecutionPipelineArgs else toolResult.toolArgs

        when (val toolResultKind = toolResult.resultKind) {
            is ToolResultKind.Success -> {
                context.pipeline.onToolCallCompleted(
                    eventId = eventId,
                    executionInfo = callbackContext.executionInfo,
                    context = callbackContext,
                    runId = callbackContext.runId,
                    toolCallId = toolResult.id,
                    toolName = toolResult.tool,
                    toolDescription = pipelineDescription,
                    toolArgs = pipelineArgs,
                    toolResult = if (isManagedExecution) managedExecutionPipelineResult else toolResult.result,
                )
            }

            is ToolResultKind.Failure -> {
                context.pipeline.onToolCallFailed(
                    eventId = eventId,
                    executionInfo = callbackContext.executionInfo,
                    context = callbackContext,
                    runId = callbackContext.runId,
                    toolCallId = toolResult.id,
                    toolName = toolResult.tool,
                    toolDescription = pipelineDescription,
                    toolArgs = pipelineArgs,
                    message = if (isManagedExecution) MANAGED_EXECUTION_DATA_REDACTED else toolResult.output,
                    error = if (isManagedExecution) managedExecutionPipelineError() else toolResultKind.error,
                )
            }

            is ToolResultKind.ValidationError -> {
                context.pipeline.onToolValidationFailed(
                    eventId = eventId,
                    executionInfo = callbackContext.executionInfo,
                    context = callbackContext,
                    runId = callbackContext.runId,
                    toolCallId = toolResult.id,
                    toolName = toolResult.tool,
                    toolDescription = pipelineDescription,
                    toolArgs = pipelineArgs,
                    message = if (isManagedExecution) MANAGED_EXECUTION_DATA_REDACTED else toolResult.output,
                    error = if (isManagedExecution) managedExecutionPipelineError() else toolResultKind.error,
                )
            }
        }
    }

    @OptIn(DetachedPromptExecutorAPI::class)
    private suspend fun createManagedCallbackContext(): AIAgentContext {
        val managedToolNames = context.llm.toolRegistry.tools
            .filterIsInstance<ManagedExecutionTool<*, *>>()
            .mapTo(mutableSetOf()) { it.name }
        val safePrompt = context.llm.prompt.detachedManagedCallbackPrompt(managedToolNames)
        val safeModel = LLModel(
            provider = LLMProvider(MANAGED_CALLBACK_REDACTION, MANAGED_CALLBACK_REDACTION),
            id = MANAGED_CALLBACK_REDACTION,
            capabilities = null,
            contextLength = null,
            maxOutputTokens = null,
        )
        val safeClock = KoogClock { Instant.DISTANT_PAST }
        val safeConfig = AIAgentConfig(
            prompt = safePrompt,
            model = safeModel,
            maxAgentIterations = 1,
            missingToolsConversionStrategy = MissingToolsConversionStrategy.Missing(ToolCallDescriber.JSON),
            responseProcessor = null,
            serializer = KotlinxSerializer(),
        )
        val safeEnvironment = ManagedCallbackEnvironment()
        val safeLlm = AIAgentLLMContext(
            tools = emptyList(),
            toolRegistry = ToolRegistry { },
            prompt = safePrompt,
            model = safeModel,
            responseProcessor = null,
            promptExecutor = ManagedCallbackPromptExecutor(),
            environment = safeEnvironment,
            config = safeConfig,
            clock = safeClock,
        )

        return ManagedCallbackContext(
            environment = safeEnvironment,
            agentId = MANAGED_CALLBACK_REDACTION,
            pipeline = AIAgentGraphPipeline(safeConfig, safeClock),
            runId = MANAGED_CALLBACK_REDACTION,
            config = safeConfig,
            llm = safeLlm,
            stateManager = AIAgentStateManager(),
            storage = AIAgentStorage(safeConfig.serializer),
            strategyName = MANAGED_CALLBACK_REDACTION,
            executionInfo = context.executionInfo.detachedManagedCallbackExecutionInfo(),
        )
    }

    //endregion Private Methods
}

private fun Prompt.detachedManagedCallbackPrompt(managedToolNames: Set<String>): Prompt {
    val managedCalls = messages
        .asSequence()
        .flatMap { it.parts.asSequence() }
        .filterIsInstance<MessagePart.Tool.Call>()
        .filter { it.tool in managedToolNames }
        .mapNotNull { call -> call.id?.let { it to call.tool } }
        .toMap()

    return Prompt(
        id = MANAGED_CALLBACK_REDACTION,
        params = LLMParams(),
        messages = messages.mapTo(mutableListOf()) { message ->
            when (message) {
                is Message.System -> Message.System(
                    parts = message.parts.mapTo(mutableListOf()) {
                        detachedMarkerText()
                    },
                    metaInfo = detachedRequestMetaInfo(),
                    id = message.id?.let { MANAGED_CALLBACK_REDACTION },
                )

                is Message.User -> Message.User(
                    parts = message.parts.mapTo(mutableListOf()) { part ->
                        part.detachedManagedCallbackPart(managedToolNames, managedCalls)
                    },
                    metaInfo = detachedRequestMetaInfo(),
                    id = message.id?.let { MANAGED_CALLBACK_REDACTION },
                )

                is Message.Assistant -> Message.Assistant(
                    parts = message.parts.mapTo(mutableListOf()) { part ->
                        part.detachedManagedCallbackPart(managedToolNames)
                    },
                    metaInfo = detachedResponseMetaInfo(),
                    finishReason = null,
                    rawResponse = null,
                    id = message.id?.let { MANAGED_CALLBACK_REDACTION },
                )
            }
        },
    )
}

private fun MessagePart.RequestPart.detachedManagedCallbackPart(
    managedToolNames: Set<String>,
    managedCalls: Map<String, String>,
): MessagePart.RequestPart = when (this) {
    is MessagePart.Text -> detachedText()
    is MessagePart.Attachment -> detachedMarkerText()
    is MessagePart.Tool.Result -> (id?.let(managedCalls::get) ?: tool.takeIf { it in managedToolNames })?.let { managedToolName ->
        MessagePart.Tool.Result(
            id = id,
            tool = managedToolName,
            parts = parts.mapTo(mutableListOf()) { it.detachedContentPart() },
            isError = isError,
            cacheControl = null,
            providerItemId = null,
        )
    } ?: run {
        detachedMarkerText()
    }
}

private fun MessagePart.ResponsePart.detachedManagedCallbackPart(
    managedToolNames: Set<String>,
): MessagePart.ResponsePart = when (this) {
    is MessagePart.Text -> detachedText()
    is MessagePart.Attachment -> detachedMarkerText()
    is MessagePart.Reasoning -> MessagePart.Reasoning(
        content = mutableListOf(MANAGED_CALLBACK_REDACTION),
        summary = summary?.let { mutableListOf(MANAGED_CALLBACK_REDACTION) },
        encrypted = encrypted?.let { MANAGED_CALLBACK_REDACTION },
        id = id?.let { MANAGED_CALLBACK_REDACTION },
        cacheControl = null,
        providerItemId = providerItemId?.let { MANAGED_CALLBACK_REDACTION },
        replay = replay.mapTo(mutableListOf()) { replayPart ->
            when (replayPart) {
                is MessagePart.ReasoningReplay.Signed -> MessagePart.ReasoningReplay.Signed(
                    text = MANAGED_CALLBACK_REDACTION,
                    signature = MANAGED_CALLBACK_REDACTION,
                )

                is MessagePart.ReasoningReplay.OpaqueRedacted -> MessagePart.ReasoningReplay.OpaqueRedacted(
                    data = MANAGED_CALLBACK_REDACTION,
                )
            }
        },
    )

    is MessagePart.GeneratedFile -> detachedGeneratedFile()
    is MessagePart.HostedExecution.Request -> MessagePart.HostedExecution.Request(
        code = MANAGED_CALLBACK_REDACTION,
        language = MANAGED_CALLBACK_REDACTION,
        executionId = executionId?.let { MANAGED_CALLBACK_REDACTION },
        containerId = containerId?.let { MANAGED_CALLBACK_REDACTION },
        providerItemId = providerItemId?.let { MANAGED_CALLBACK_REDACTION },
        cacheControl = null,
    )

    is MessagePart.HostedExecution.Progress -> MessagePart.HostedExecution.Progress(
        message = message?.let { MANAGED_CALLBACK_REDACTION },
        sequence = null,
        executionId = executionId?.let { MANAGED_CALLBACK_REDACTION },
        containerId = containerId?.let { MANAGED_CALLBACK_REDACTION },
        providerItemId = providerItemId?.let { MANAGED_CALLBACK_REDACTION },
        cacheControl = null,
    )

    is MessagePart.HostedExecution.CumulativeOutput -> MessagePart.HostedExecution.CumulativeOutput(
        output = MANAGED_CALLBACK_REDACTION,
        sequence = null,
        executionId = executionId?.let { MANAGED_CALLBACK_REDACTION },
        containerId = containerId?.let { MANAGED_CALLBACK_REDACTION },
        providerItemId = providerItemId?.let { MANAGED_CALLBACK_REDACTION },
        cacheControl = null,
    )

    is MessagePart.HostedExecution.Result -> MessagePart.HostedExecution.Result(
        output = output?.let { MANAGED_CALLBACK_REDACTION },
        exitCode = null,
        generatedFiles = generatedFiles.mapTo(mutableListOf()) { it.detachedGeneratedFile() },
        executionId = executionId?.let { MANAGED_CALLBACK_REDACTION },
        containerId = containerId?.let { MANAGED_CALLBACK_REDACTION },
        providerItemId = providerItemId?.let { MANAGED_CALLBACK_REDACTION },
        cacheControl = null,
    )

    is MessagePart.HostedExecution.Error -> MessagePart.HostedExecution.Error(
        message = MANAGED_CALLBACK_REDACTION,
        code = code?.let { MANAGED_CALLBACK_REDACTION },
        executionId = executionId?.let { MANAGED_CALLBACK_REDACTION },
        containerId = containerId?.let { MANAGED_CALLBACK_REDACTION },
        providerItemId = providerItemId?.let { MANAGED_CALLBACK_REDACTION },
        cacheControl = null,
    )

    is MessagePart.CodeExecution -> MessagePart.CodeExecution(
        id = MANAGED_CALLBACK_REDACTION,
        code = MANAGED_CALLBACK_REDACTION,
        containerId = containerId?.let { MANAGED_CALLBACK_REDACTION },
        outputs = outputs.mapTo(mutableListOf()) { output ->
            when (output) {
                is MessagePart.CodeExecution.Output.Logs ->
                    MessagePart.CodeExecution.Output.Logs(MANAGED_CALLBACK_REDACTION)

                is MessagePart.CodeExecution.Output.Image ->
                    MessagePart.CodeExecution.Output.Image(MANAGED_CALLBACK_REDACTION)
            }
        },
        failure = failure,
        cacheControl = null,
        providerItemId = providerItemId?.let { MANAGED_CALLBACK_REDACTION },
    )

    is MessagePart.Tool.Call -> if (tool in managedToolNames) {
        MessagePart.Tool.Call(
            id = id,
            tool = tool,
            args = """{"data":"$MANAGED_CALLBACK_REDACTION"}""",
            cacheControl = null,
            providerItemId = null,
        )
    } else {
        detachedMarkerText()
    }
}

private fun MessagePart.ContentPart.detachedContentPart(): MessagePart.ContentPart = when (this) {
    is MessagePart.Text -> detachedText()
    is MessagePart.Attachment -> detachedMarkerText()
}

private fun detachedMarkerText(): MessagePart.Text =
    MessagePart.Text(
        text = MANAGED_CALLBACK_REDACTION,
        generatedFileCitations = mutableListOf(),
    )

private fun MessagePart.Text.detachedText(): MessagePart.Text = MessagePart.Text(
    text = MANAGED_CALLBACK_REDACTION,
    cacheControl = null,
    providerItemId = null,
    generatedFileCitations = generatedFileCitations.mapTo(mutableListOf()) { citation ->
        MessagePart.GeneratedFileCitation(
            providerFileId = MANAGED_CALLBACK_REDACTION,
            containerId = citation.containerId?.let { MANAGED_CALLBACK_REDACTION },
            filename = citation.filename?.let { MANAGED_CALLBACK_REDACTION },
            mediaType = citation.mediaType?.let { MANAGED_CALLBACK_REDACTION },
            sizeBytes = null,
            producingExecutionId = citation.producingExecutionId?.let { MANAGED_CALLBACK_REDACTION },
            providerItemId = citation.providerItemId?.let { MANAGED_CALLBACK_REDACTION },
            startIndex = null,
            endIndex = null,
        )
    },
)

private fun MessagePart.GeneratedFile.detachedGeneratedFile(): MessagePart.GeneratedFile =
    MessagePart.GeneratedFile(
        providerFileId = MANAGED_CALLBACK_REDACTION,
        containerId = containerId?.let { MANAGED_CALLBACK_REDACTION },
        filename = filename?.let { MANAGED_CALLBACK_REDACTION },
        mediaType = mediaType?.let { MANAGED_CALLBACK_REDACTION },
        sizeBytes = null,
        producingExecutionId = producingExecutionId?.let { MANAGED_CALLBACK_REDACTION },
        providerItemId = providerItemId?.let { MANAGED_CALLBACK_REDACTION },
        cacheControl = null,
    )

private fun detachedRequestMetaInfo(): RequestMetaInfo =
    RequestMetaInfo(timestamp = Instant.DISTANT_PAST, metadata = null)

private fun detachedResponseMetaInfo(): ResponseMetaInfo =
    ResponseMetaInfo(
        timestamp = Instant.DISTANT_PAST,
        totalTokensCount = null,
        inputTokensCount = null,
        outputTokensCount = null,
        modelId = null,
        metadata = null,
    )

private fun AgentExecutionInfo.detachedManagedCallbackExecutionInfo(): AgentExecutionInfo =
    AgentExecutionInfo(
        parent = parent?.detachedManagedCallbackExecutionInfo(),
        partName = MANAGED_CALLBACK_REDACTION,
    )

private const val MANAGED_CALLBACK_REDACTION = "<managed-execution-data-redacted>"

private class ManagedCallbackEnvironment : AIAgentEnvironment {
    override suspend fun executeTool(toolCall: MessagePart.Tool.Call): ReceivedToolResult =
        throw UnsupportedOperationException("Managed callback context cannot execute tools")

    override suspend fun reportProblem(exception: Throwable) {
        throw UnsupportedOperationException("Managed callback context cannot report agent problems")
    }
}

private class ManagedCallbackPromptExecutor : PromptExecutor() {
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant =
        throw UnsupportedOperationException("Managed callback context cannot execute prompts")

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow {
        throw UnsupportedOperationException("Managed callback context cannot stream prompts")
    }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): LLMChoice =
        throw UnsupportedOperationException("Managed callback context cannot execute prompts")

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        throw UnsupportedOperationException("Managed callback context cannot moderate prompts")

    override fun close() = Unit
}

@OptIn(InternalAgentsApi::class)
private class ManagedCallbackContext(
    override val environment: AIAgentEnvironment,
    override val agentId: String,
    override val pipeline: AIAgentPipeline,
    override val runId: String,
    override val config: ai.koog.agents.core.agent.config.AIAgentConfig,
    override val llm: AIAgentLLMContext,
    override val stateManager: AIAgentStateManager,
    override val storage: AIAgentStorage,
    override val strategyName: String,
    override var executionInfo: AgentExecutionInfo,
) : AIAgentContext {
    override val agentInput: Any? = MANAGED_CALLBACK_REDACTION
    override val parentContext: AIAgentContext? = null
}
