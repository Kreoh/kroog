package ai.koog.prompt.executor.clients.openai

import ai.koog.prompt.executor.clients.openai.models.OpenAIPromptCacheBreakpoint
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.PromptCacheControl

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
