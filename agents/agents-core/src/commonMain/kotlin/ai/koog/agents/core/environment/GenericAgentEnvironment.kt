package ai.koog.agents.core.environment

import ai.koog.agents.core.agent.tools.ManagedExecutionProtocolException
import ai.koog.agents.core.agent.tools.ManagedExecutionTool
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import io.github.oshai.kotlinlogging.KLogger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Represents base agent environment with generic abstractions.
 */
@OptIn(ExperimentalUuidApi::class)
public class GenericAgentEnvironment(
    private val agentId: String,
    private val logger: KLogger,
    private val toolRegistry: ToolRegistry,
    private val serializer: JSONSerializer,
) : AIAgentEnvironment {

    private val diagnosticAgentId = Uuid.random().toString()

    override suspend fun executeTool(toolCall: MessagePart.Tool.Call): ReceivedToolResult =
        executeTool(toolCall, ToolCallMetadata.EMPTY)

    override suspend fun executeTool(
        toolCall: MessagePart.Tool.Call,
        metadata: ToolCallMetadata,
    ): ReceivedToolResult {
        val managed = toolRegistry.getToolOrNull(toolCall.tool) is ManagedExecutionTool<*, *>
        val invocation = "execution_id=" + Uuid.random() +
            if (managed) " kind=managed data=<managed-execution-data-redacted>" else " kind=ordinary"
        logger.info { formatLog("execution_started", invocation) }
        val environmentToolResult = processToolCall(toolCall, metadata, invocation)
        logger.debug { formatLog("result_received", invocation) }

        return environmentToolResult
    }

    override suspend fun reportProblem(exception: Throwable) {
        logger.error { formatLog("agent_problem", "") }
        throw exception
    }

    @OptIn(InternalAgentToolsApi::class)
    private suspend fun processToolCall(
        toolCall: MessagePart.Tool.Call,
        metadata: ToolCallMetadata,
        invocation: String,
    ): ReceivedToolResult {
        logger.debug { formatLog("tool_call_received", invocation) }

        // Tool
        val id = toolCall.id
        val toolName = toolCall.tool
        val toolArgsJson = try {
            toolCall.argsJson.toKoogJSONObject()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ReceivedToolResult(
                id = id,
                tool = toolName,
                toolArgs = JSONObject(emptyMap()),
                toolDescription = null,
                output = "Tool with name '$toolName' failed to parse arguments due to the error: ${e.message}",
                resultKind = ToolResultKind.Failure(e),
                result = null,
                resultObject = null
            )
        }

        val tool = toolRegistry.getToolOrNull(toolName)
            ?: run {
                logger.error { formatLog("tool_not_found", invocation) }
                return ReceivedToolResult(
                    id = id,
                    tool = toolName,
                    toolArgs = toolArgsJson,
                    toolDescription = null,
                    output = "Tool with name '$toolName' not found in the tool registry. Use one of the available tools.",
                    resultKind = ToolResultKind.Failure(null),
                    result = null,
                    resultObject = null
                )
            }

        val toolDescription = tool.descriptor.description

        // Tool Args
        val toolArgs = try {
            tool.decodeArgs(toolArgsJson, serializer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error { formatLog("argument_decode_failed", invocation) }

            return ReceivedToolResult(
                id = id,
                tool = toolName,
                toolArgs = toolArgsJson,
                toolDescription = toolDescription,
                output = "Tool with name '$toolName' failed to parse arguments due to the error: ${e.message}",
                resultKind = ToolResultKind.Failure(e),
                result = null,
                resultObject = null
            )
        }

        val toolResult = try {
            @Suppress("UNCHECKED_CAST")
            if (tool is ManagedExecutionTool<*, *>) {
                (tool as ManagedExecutionTool<Any?, Any?>).collectExecution(toolArgs, metadata) { eventIndex, event ->
                    metadata.managedExecutionEventObserver?.onEvent(
                        ManagedExecutionObservation(
                            toolCallId = id,
                            toolName = toolName,
                            eventIndex = eventIndex,
                            event = event,
                        )
                    )
                }
            } else {
                (tool as ToolBase<Any?, Any?>).execute(toolArgs, metadata)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            return ReceivedToolResult(
                id = id,
                tool = toolName,
                toolArgs = toolArgsJson,
                toolDescription = toolDescription,
                output = e.message,
                resultKind = ToolResultKind.ValidationError(e),
                result = null,
                resultObject = null
            )
        } catch (e: ManagedExecutionProtocolException) {
            logger.error { formatLog("execution_protocol_failed", invocation) }
            return ReceivedToolResult(
                id = id,
                tool = toolName,
                toolArgs = toolArgsJson,
                toolDescription = toolDescription,
                output = "Tool with name '$toolName' failed to execute due to the error: ${e.message}!",
                resultKind = ToolResultKind.Failure(e),
                result = null,
                resultObject = null
            )
        } catch (e: Exception) {
            logger.error { formatLog("execution_failed", invocation) }

            return ReceivedToolResult(
                id = id,
                tool = toolName,
                toolArgs = toolArgsJson,
                toolDescription = toolDescription,
                output = "Tool with name '$toolName' failed to execute due to the error: ${e.message}!",
                resultKind = ToolResultKind.Failure(e),
                result = null,
                resultObject = null
            )
        }

        logger.trace { formatLog("execution_completed", invocation) }

        val (content, result, parts) = try {
            val content = tool.encodeResultToStringUnsafe(toolResult, serializer)
            val result = tool.encodeResult(toolResult, serializer)
            val parts = tool.encodeResultToPartsUnsafe(toolResult, serializer)
            Triple(content, result, parts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error { formatLog("result_encode_failed", invocation) }

            return ReceivedToolResult(
                id = id,
                tool = toolName,
                toolArgs = toolArgsJson,
                toolDescription = toolDescription,
                output = "Tool with name '$toolName' failed to serialize result due to the error: ${e.message}!",
                resultKind = ToolResultKind.Failure(e),
                result = null,
                resultObject = null
            )
        }

        return ReceivedToolResult(
            id = id,
            tool = toolName,
            toolArgs = toolArgsJson,
            toolDescription = toolDescription,
            output = content,
            resultKind = ToolResultKind.Success,
            result = result,
            resultObject = toolResult,
            parts = parts,
        )
    }

    // Correlation is local: supplied identifiers and payloads never enter diagnostics.
    private fun formatLog(category: String, invocation: String): String =
        "agent_id=$diagnosticAgentId $invocation category=$category"
}
