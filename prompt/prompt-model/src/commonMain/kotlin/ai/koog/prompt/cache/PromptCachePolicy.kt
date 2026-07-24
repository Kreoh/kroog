package ai.koog.prompt.cache

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.CacheControl
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.PromptCacheControl
import ai.koog.prompt.message.PromptCacheTtl

/** Pure request-time placement and validation for provider prompt-cache breakpoints. */
public object PromptCachePolicy {
    /** Maximum number of prompt-cache breakpoints accepted by Anthropic-compatible transports. */
    public const val MAX_BREAKPOINTS: Int = 4

    /**
     * Builds an immutable request view, validates explicit breakpoints, and adds a rolling five-minute breakpoint to
     * the latest eligible user or assistant part when capacity remains.
     *
     * [leadingBreakpoints] represents provider-emittable markers which precede messages on the wire, such as cached
     * tool definitions. [trailingBreakpoints] represents request-level markers emitted after message content.
     * [metadata] lets a provider describe its legacy cache-control implementations while the default keeps existing
     * implementations compatible by treating an unknown control as a five-minute marker.
     */
    public fun requestView(
        prompt: Prompt,
        leadingBreakpoints: List<PromptCacheTtl> = emptyList(),
        automaticBreakpoint: Boolean = true,
        metadata: (CacheControl) -> PromptCacheControl? = ::defaultMetadata,
    ): Prompt = requestView(
        prompt = prompt,
        leadingBreakpoints = leadingBreakpoints,
        trailingBreakpoints = emptyList(),
        automaticBreakpoint = automaticBreakpoint,
        metadata = metadata,
    )

    /**
     * Builds a request view which also accounts for provider markers emitted after message content.
     */
    public fun requestView(
        prompt: Prompt,
        leadingBreakpoints: List<PromptCacheTtl>,
        trailingBreakpoints: List<PromptCacheTtl>,
        automaticBreakpoint: Boolean = true,
        metadata: (CacheControl) -> PromptCacheControl? = ::defaultMetadata,
    ): Prompt {
        val wireOrderedMessages = prompt.messages.filterIsInstance<Message.System>() +
            prompt.messages.filterNot { it is Message.System }
        val messageBreakpoints = wireOrderedMessages.flatMap { message ->
            message.parts.flatMap { part ->
                part.providerWireParts().mapNotNull { wirePart ->
                    if (wirePart.isProviderEmittableBreakpoint()) {
                        wirePart.cacheControl?.let(metadata)?.takeIf(PromptCacheControl::cacheable)?.ttl
                    } else {
                        null
                    }
                }
            }
        }
        val breakpoints = leadingBreakpoints + messageBreakpoints + trailingBreakpoints
        validate(breakpoints)

        if (!automaticBreakpoint || breakpoints.size >= MAX_BREAKPOINTS) return prompt

        val location = prompt.messages.indices.reversed().firstNotNullOfOrNull { messageIndex ->
            val message = prompt.messages[messageIndex]
            if (message !is Message.User && message !is Message.Assistant) return@firstNotNullOfOrNull null
            val partIndex = message.parts.indices.reversed().firstOrNull { message.parts[it].isAutomaticCandidate() }
                ?: return@firstNotNullOfOrNull null
            messageIndex to partIndex
        } ?: return prompt

        val (messageIndex, partIndex) = location
        val target = prompt.messages[messageIndex].parts[partIndex]
        val existing = target.cacheControl?.let(metadata)
        if (existing?.cacheable == true) return prompt

        if (PromptCacheTtl.OneHour in trailingBreakpoints) return prompt
        validate(breakpoints.dropLast(trailingBreakpoints.size) + PromptCacheTtl.FiveMinutes + trailingBreakpoints)

        val messages = prompt.messages.toMutableList()
        messages[messageIndex] = messages[messageIndex].withPartCacheControl(
            partIndex,
            PromptCacheControl(cacheable = true, ttl = PromptCacheTtl.FiveMinutes),
        )
        return prompt.copy(messages = messages)
    }

    /** Validates the provider wire-order constraints without constructing a request. */
    public fun validate(breakpoints: List<PromptCacheTtl>) {
        require(breakpoints.size <= MAX_BREAKPOINTS) {
            "Prompt caching allows at most $MAX_BREAKPOINTS provider-emittable breakpoints"
        }
        var fiveMinuteSeen = false
        breakpoints.forEach { ttl ->
            when (ttl) {
                PromptCacheTtl.FiveMinutes -> fiveMinuteSeen = true
                PromptCacheTtl.OneHour -> require(!fiveMinuteSeen) {
                    "Prompt cache TTLs must place one-hour breakpoints before five-minute breakpoints"
                }
            }
        }
    }

    private fun defaultMetadata(control: CacheControl): PromptCacheControl =
        control as? PromptCacheControl ?: PromptCacheControl(cacheable = true)

    /**
     * Anthropic emits tool-result content blocks before the enclosing tool-result breakpoint. Keep this traversal in
     * the shared policy so validation sees every marker in the same order as the provider request.
     */
    private fun MessagePart.providerWireParts(): List<MessagePart> = when (this) {
        is MessagePart.Tool.Result -> parts + this
        else -> listOf(this)
    }

    private fun MessagePart.isProviderEmittableBreakpoint(): Boolean = when (this) {
        is MessagePart.Text -> text.isNotBlank()
        is MessagePart.Attachment -> true
        is MessagePart.Tool.Call -> true
        is MessagePart.Tool.Result -> parts.any { it.isProviderEmittableBreakpoint() }
        is MessagePart.Reasoning,
        is MessagePart.CodeExecution,
        is MessagePart.HostedExecution,
        is MessagePart.GeneratedFile -> false
    }

    private fun MessagePart.isAutomaticCandidate(): Boolean = isProviderEmittableBreakpoint()

    @Suppress("UNCHECKED_CAST")
    private fun Message.withPartCacheControl(index: Int, cacheControl: CacheControl): Message {
        val updatedParts = parts.toMutableList()
        updatedParts[index] = updatedParts[index].withCacheControl(cacheControl)
        return when (this) {
            is Message.System -> copy(parts = updatedParts as List<MessagePart.Text>)
            is Message.User -> copy(parts = updatedParts as List<MessagePart.RequestPart>)
            is Message.Assistant -> copy(parts = updatedParts as List<MessagePart.ResponsePart>)
        }
    }

    private fun MessagePart.withCacheControl(cacheControl: CacheControl): MessagePart = when (this) {
        is MessagePart.Text -> copy(cacheControl = cacheControl)
        is MessagePart.Attachment -> copy(cacheControl = cacheControl)
        is MessagePart.Reasoning -> copy(cacheControl = cacheControl)
        is MessagePart.CodeExecution -> copy(cacheControl = cacheControl)
        is MessagePart.Tool.Call -> copy(cacheControl = cacheControl)
        is MessagePart.Tool.Result -> copy(cacheControl = cacheControl)
        is MessagePart.HostedExecution.Request -> copy(cacheControl = cacheControl)
        is MessagePart.HostedExecution.Progress -> copy(cacheControl = cacheControl)
        is MessagePart.HostedExecution.CumulativeOutput -> copy(cacheControl = cacheControl)
        is MessagePart.HostedExecution.Result -> copy(cacheControl = cacheControl)
        is MessagePart.HostedExecution.Error -> copy(cacheControl = cacheControl)
        is MessagePart.GeneratedFile -> copy(cacheControl = cacheControl)
    }
}
