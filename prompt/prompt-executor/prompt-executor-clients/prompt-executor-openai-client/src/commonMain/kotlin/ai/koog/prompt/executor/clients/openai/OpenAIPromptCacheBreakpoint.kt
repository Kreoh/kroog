package ai.koog.prompt.executor.clients.openai

import ai.koog.prompt.executor.clients.openai.models.OpenAIPromptCacheBreakpoint
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.PromptCacheControl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Maps a cacheable [PromptCacheControl] on an input part to an explicit Responses breakpoint.
 * OpenAI applies its own retention, so the requested TTL is not sent. Compatible endpoints never receive the field.
 */
internal fun MessagePart.openAIBreakpoint(
    model: LLModel,
    dialect: OpenAIResponsesDialect,
): OpenAIPromptCacheBreakpoint? = if (
    (cacheControl as? PromptCacheControl)?.cacheable == true &&
    model.supports(LLMCapability.OpenAIExplicitPromptCaching) &&
    dialect != OpenAIResponsesDialect.Compatible
) OpenAIPromptCacheBreakpoint("explicit") else null

/** Controls whether OpenAI also writes an implicit breakpoint after eligible input. */
public enum class OpenAIPromptCacheMode(internal val value: String) {
    Implicit("implicit"),
    Explicit("explicit"),
}

/** Minimum cache lifetime for OpenAI Responses. */
public enum class OpenAIPromptCacheTtl(internal val value: String) {
    ThirtyMinutes("30m"),
    TwelveHours("12h"),
}

/** Selects the request's cache mode and optional lifetime. Other cache options, such as diagnostics, are retained. */
public fun OpenAIResponsesParams.withPromptCacheOptions(
    mode: OpenAIPromptCacheMode,
    ttl: OpenAIPromptCacheTtl? = null,
): OpenAIResponsesParams {
    val properties = additionalProperties.orEmpty()
    val options = buildJsonObject {
        (properties["prompt_cache_options"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
        put("mode", mode.value)
        ttl?.let { put("ttl", it.value) }
    }
    return copy(additionalProperties = properties + ("prompt_cache_options" to options))
}
