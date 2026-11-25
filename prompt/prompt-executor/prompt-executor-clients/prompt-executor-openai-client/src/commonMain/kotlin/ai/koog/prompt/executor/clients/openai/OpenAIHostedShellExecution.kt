package ai.koog.prompt.executor.clients.openai

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.models.Item
import ai.koog.prompt.executor.clients.openai.models.ItemPolymorphicSerializer
import ai.koog.prompt.executor.clients.openai.models.OpenAIInputStatus
import ai.koog.prompt.executor.clients.openai.models.OpenAIShellCallEnvironment
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.provider.ProviderApi
import kotlinx.serialization.json.Json

/** Projects a single OpenAI response's paired shell items to the hosted execution stream. */
internal class OpenAIHostedShellExecution(
    private val json: Json,
    private val config: OpenAIHostedShellConfig?,
) {
    private val containerIds = mutableMapOf<String, String?>()

    fun started(call: Item.ShellCall, sequence: Int): MessagePart.HostedExecution.Progress =
        MessagePart.HostedExecution.Progress(
            message = "running",
            sequence = sequence,
            executionId = call.callId.executionId(),
            containerId = call.hostedContainerId(),
        )

    fun call(call: Item.ShellCall): MessagePart.HostedExecution {
        require(call.callId.isNotBlank()) { "OpenAI shell call requires an ID" }
        val containerId = call.hostedContainerId()
        containerIds[call.callId] = containerId
        if (call.isUnfinished) {
            return MessagePart.HostedExecution.Error(
                message = "Shell call: incomplete",
                code = "incomplete",
                executionId = call.callId.executionId(),
                containerId = containerId,
                providerItemId = call.id,
                replay = call.asHostedReplay(),
            )
        }
        require(call.action.commands.isNotEmpty()) { "OpenAI shell call requires commands" }
        return MessagePart.HostedExecution.Request(
            code = call.action.commands.joinToString("\n"),
            language = "bash",
            executionId = call.callId.executionId(),
            containerId = containerId,
            providerItemId = call.id,
            replay = call.asHostedReplay(),
        )
    }

    fun result(item: Item.ShellCallOutput): MessagePart.HostedExecution {
        require(item.callId in containerIds) { "OpenAI shell output has no matching call" }
        val containerId = containerIds[item.callId]
        val output = item.output.flatMap { entry -> listOf(entry.stdout, entry.stderr) }
            .filter(String::isNotEmpty).joinToString("\n").takeIf(String::isNotEmpty)
        val failure = when {
            item.status == OpenAIInputStatus.IN_PROGRESS -> "incomplete"
            item.output.any { it.outcome == Item.ShellCallOutput.Outcome.Timeout } -> "timeout"
            item.status == OpenAIInputStatus.INCOMPLETE -> "incomplete"
            else -> null
        }
        if (failure != null) {
            return MessagePart.HostedExecution.Error(
                message = listOfNotNull("Shell execution: $failure", output).joinToString("\n"),
                code = failure,
                executionId = item.callId.executionId(),
                containerId = containerId,
                providerItemId = item.id,
                replay = item.asHostedReplay(),
            )
        }
        val exitCodes = item.output.mapNotNull { (it.outcome as? Item.ShellCallOutput.Outcome.Exit)?.exitCode }
        return MessagePart.HostedExecution.Result(
            output = output,
            exitCode = exitCodes.firstOrNull { it != 0 } ?: exitCodes.lastOrNull(),
            executionId = item.callId.executionId(),
            containerId = containerId,
            providerItemId = item.id,
            replay = item.asHostedReplay(),
        )
    }

    private fun Item.ShellCall.hostedContainerId(): String? {
        require(config != null && environment != OpenAIShellCallEnvironment.Local) {
            "OpenAI returned a shell call outside a configured hosted shell request"
        }
        val observedId = (environment as? OpenAIShellCallEnvironment.Reference)?.containerId
        require(config.containerId == null || observedId == null || config.containerId == observedId) {
            "OpenAI returned a shell call for a different container"
        }
        return observedId ?: config.containerId
    }

    private fun Item.asHostedReplay(): MessagePart.HostedExecutionReplay =
        MessagePart.HostedExecutionReplay(
            provider = ProviderApi.OPENAI_RESPONSES,
            payload = json.encodeToString(ItemPolymorphicSerializer, this),
        )
}

internal fun MessagePart.HostedExecution.nativeReplay(): MessagePart.HostedExecutionReplay? = when (this) {
    is MessagePart.HostedExecution.Request -> replay
    is MessagePart.HostedExecution.Result -> replay
    is MessagePart.HostedExecution.Error -> replay
    is MessagePart.HostedExecution.Progress, is MessagePart.HostedExecution.CumulativeOutput -> null
}

internal fun Prompt.requireNoNativeShellReplay() {
    val hasNativeReplay = messages.any { message ->
        message.parts.any { part -> part is MessagePart.HostedExecution && part.nativeReplay() != null }
    }
    require(!hasNativeReplay) { "Hosted shell replay requires the direct OpenAI Responses API" }
}

/** Validates native shell history and projects it onto the Responses input schema. */
internal class OpenAIHostedShellReplay(private val json: Json) {
    private val pendingCalls = mutableMapOf<String, MessagePart.HostedExecution.Request>()
    private val seenCalls = mutableSetOf<String>()

    fun item(part: MessagePart.HostedExecution, replay: MessagePart.HostedExecutionReplay): Item {
        require(replay.provider == ProviderApi.OPENAI_RESPONSES) { "Hosted shell replay belongs to a different provider" }
        val item = json.decodeFromString(ItemPolymorphicSerializer, replay.payload)
        return when (item) {
            is Item.ShellCall -> {
                require(!item.isUnfinished) { "OpenAI shell replay requires finished calls" }
                require(part is MessagePart.HostedExecution.Request && part.matches(item.callId, item.id)) {
                    "OpenAI shell replay call ID does not match the execution request"
                }
                require(item.environment != OpenAIShellCallEnvironment.Local && item.action.commands.isNotEmpty()) {
                    "OpenAI hosted shell replay requires a hosted environment and commands"
                }
                val containerId = (item.environment as? OpenAIShellCallEnvironment.Reference)?.containerId
                require(containerId == null || containerId == part.containerId) {
                    "OpenAI shell replay container ID does not match the execution request"
                }
                require(seenCalls.add(item.callId)) { "Duplicate OpenAI shell call in replay" }
                pendingCalls[item.callId] = part
                Item.ShellCall(
                    action = item.action,
                    callId = item.callId,
                    id = item.id,
                    environment = item.environment,
                    status = item.status,
                    caller = item.caller,
                )
            }
            is Item.ShellCallOutput -> {
                require(item.status != OpenAIInputStatus.IN_PROGRESS) {
                    "OpenAI shell replay requires finished output items"
                }
                require(
                    (part is MessagePart.HostedExecution.Result || part is MessagePart.HostedExecution.Error) &&
                        part.matches(item.callId, item.id)
                ) { "OpenAI shell replay output ID does not match the execution result" }
                val request = pendingCalls.remove(item.callId)
                require(request != null && request.containerId == part.containerId) {
                    "OpenAI shell replay output is missing its matching request"
                }
                Item.ShellCallOutput(
                    callId = item.callId,
                    output = item.output.map { entry ->
                        Item.ShellCallOutput.Output(
                            stdout = entry.stdout,
                            stderr = entry.stderr,
                            outcome = entry.outcome,
                        )
                    },
                    id = item.id,
                    maxOutputLength = item.maxOutputLength,
                    status = item.status,
                    caller = item.caller,
                )
            }
            else -> throw IllegalArgumentException("Unexpected item in OpenAI hosted shell replay")
        }
    }

    fun requireResolved() {
        require(pendingCalls.isEmpty()) { "OpenAI shell replay requires each call to have its output" }
    }

    private fun MessagePart.HostedExecution.matches(callId: String, itemId: String?): Boolean =
        callId.isNotBlank() && executionId == callId.executionId() && providerItemId == itemId
}

private val Item.ShellCall.isUnfinished: Boolean
    get() = status == OpenAIInputStatus.IN_PROGRESS || status == OpenAIInputStatus.INCOMPLETE

private fun String.executionId(): String = "execution:$this"
