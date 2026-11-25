package ai.koog.prompt.executor.clients.openai

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

    fun request(call: Item.ShellCall): MessagePart.HostedExecution.Request {
        require(call.callId.isNotBlank() && call.action.commands.isNotEmpty()) {
            "OpenAI shell call requires an ID and commands"
        }
        val containerId = call.hostedContainerId()
        containerIds[call.callId] = containerId
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

/** Validates the native shell item types, identities and pairing before writing them into a request. */
internal class OpenAIHostedShellReplay(private val json: Json) {
    private val pendingCalls = mutableMapOf<String, MessagePart.HostedExecution.Request>()
    private val seenCalls = mutableSetOf<String>()

    fun item(part: MessagePart.HostedExecution, replay: MessagePart.HostedExecutionReplay): Item {
        require(replay.provider == ProviderApi.OPENAI_RESPONSES) { "Hosted shell replay belongs to a different provider" }
        val item = json.decodeFromString(ItemPolymorphicSerializer, replay.payload)
        when (item) {
            is Item.ShellCall -> {
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
            }
            is Item.ShellCallOutput -> {
                require(
                    (part is MessagePart.HostedExecution.Result || part is MessagePart.HostedExecution.Error) &&
                        part.matches(item.callId, item.id)
                ) { "OpenAI shell replay output ID does not match the execution result" }
                val request = pendingCalls.remove(item.callId)
                require(request != null && request.containerId == part.containerId) {
                    "OpenAI shell replay output is missing its matching request"
                }
            }
            else -> throw IllegalArgumentException("Unexpected item in OpenAI hosted shell replay")
        }
        return item
    }

    fun requireResolved() {
        require(pendingCalls.isEmpty()) { "OpenAI shell replay requires each call to have its output" }
    }

    private fun MessagePart.HostedExecution.matches(callId: String, itemId: String?): Boolean =
        callId.isNotBlank() && executionId == callId.executionId() && providerItemId == itemId
}

private fun String.executionId(): String = "execution:$this"
