package ai.koog.prompt.executor.clients.openai

import ai.koog.prompt.dsl.PromptBuilder
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.Item
import ai.koog.prompt.executor.clients.openai.models.OpenAIOutputFormat
import ai.koog.prompt.executor.clients.openai.models.OpenAITextConfig
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.utils.time.KoogClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

internal const val CONFIGURATION_UPDATE = "koog.openai.configuration_update"

/**
 * Appends a settings change to the Responses conversation. Place it before the next user message
 * and keep the original request-level effort and format when replaying history.
 * Omitted settings retain their previous values. Set [plainText] to end structured output.
 */
public fun PromptBuilder.openAIConfigurationUpdate(
    effort: ReasoningEffort? = null,
    schema: LLMParams.Schema.JSON? = null,
    plainText: Boolean = false,
): PromptBuilder {
    val update = OpenAIConfigurationUpdate(effort, schema, plainText)
    val metadata = JsonObject(mapOf(CONFIGURATION_UPDATE to Json.encodeToJsonElement(update)))
    return message(Message.System(emptyList(), RequestMetaInfo.create(KoogClock.System).copy(metadata = metadata)))
}

@Serializable
internal class OpenAIConfigurationUpdate(
    val effort: ReasoningEffort? = null,
    val schema: LLMParams.Schema.JSON? = null,
    val plainText: Boolean = false,
) {
    init {
        require(effort != null || schema != null || plainText) { "Supply a reasoning effort or output format" }
        require(schema == null || !plainText) { "Choose either a schema or plain text" }
    }

    fun toItem(model: LLModel): Item.ConfigurationUpdate = Item.ConfigurationUpdate(
        reasoning = effort?.let { ReasoningConfig(effort = it) },
        text = if (plainText) OpenAITextConfig(OpenAIOutputFormat.Text()) else schema?.toResponsesTextConfig(model),
    )
}

internal fun Message.System.configurationUpdate(): OpenAIConfigurationUpdate? =
    metaInfo.metadata?.get(CONFIGURATION_UPDATE)?.let {
        require(parts.isEmpty()) { "A configuration update must be a separate input item" }
        Json.decodeFromJsonElement<OpenAIConfigurationUpdate>(it)
    }

internal fun LLMParams.Schema.toResponsesTextConfig(model: LLModel): OpenAITextConfig {
    require(model.supports(capability)) { "Model ${model.id} does not support structured output schema $name" }
    return when (this) {
        is LLMParams.Schema.JSON -> OpenAITextConfig(OpenAIOutputFormat.JsonSchema(name = name, schema = schema, strict = true))
    }
}
