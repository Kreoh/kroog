package ai.koog.prompt.executor.clients.deepseek

import ai.koog.prompt.executor.clients.LLModelDefinitions
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Flash
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4FlashVisionExp
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels.DeepSeekV4Pro
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlin.collections.plus
import kotlin.jvm.JvmField

/**
 * Object containing a collection of predefined DeepSeek model configurations.
 *
 * DeepSeek provides powerful language models with competitive pricing and advanced reasoning capabilities.
 * All models support JSON output, function calling, and chat prefix completion features.
 *
 * | Name                        | Speed  | Price                | Input              | Output      |
 * |-----------------------------|--------|----------------------|--------------------|-------------|
 * | [DeepSeekV4Flash]           | Fast   | $0.44 / $1.32 per 1M | Text, Image, Tools | Text, Tools |
 * | [DeepSeekV4FlashVisionExp]  | Fast   | $0.44 / $1.32 per 1M | Text, Image, Tools | Text, Tools |
 * | [DeepSeekV4Pro]             | Medium | $1.74 / $3.48 per 1M | Text, Tools        | Text, Tools |
 *
 * @see <a href="https://platform.deepseek.com/api-docs/pricing">DeepSeek Pricing Documentation</a>
 */
public object DeepSeekModels : LLModelDefinitions {

    /**
     * Legacy Flash alias, now routed by DeepSeek to V4.1 Flash with image input.
     * Supports both thinking and non-thinking modes in the DeepSeek API.
     *
     * @see <a href="https://api-docs.deepseek.com/api/create-chat-completion/">Chat Completion API</a>
     */
    @JvmField
    public val DeepSeekV4Flash: LLModel = LLModel(
        provider = LLMProvider.DeepSeek,
        id = "deepseek-v4-flash",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Temperature,
            LLMCapability.Tools,
            LLMCapability.ToolChoice,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.MultipleChoices,
            LLMCapability.Thinking,
            LLMCapability.Vision.Image,
        ),
        contextLength = 1_000_000,
        maxOutputTokens = 384_000
    )

    /**
     * DeepSeek V4 Pro model optimised for advanced reasoning and agentic tasks.
     * Supports both thinking and non-thinking modes in the DeepSeek API.
     *
     * @see <a href="https://api-docs.deepseek.com/api/create-chat-completion/">Chat Completion API</a>
     */
    @JvmField
    public val DeepSeekV4Pro: LLModel = LLModel(
        provider = LLMProvider.DeepSeek,
        id = "deepseek-v4-pro",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Temperature,
            LLMCapability.Tools,
            LLMCapability.ToolChoice,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.MultipleChoices,
            LLMCapability.Thinking,
        ),
        contextLength = 1_000_000,
        maxOutputTokens = 384_000
    )

    /**
     * Legacy experimental vision alias, now routed by DeepSeek to V4.1 Flash.
     * Supports both thinking and non-thinking modes in the DeepSeek API.
     *
     * @see <a href="https://api-docs.deepseek.com/api/create-chat-completion/">Chat Completion API</a>
     */
    @JvmField
    public val DeepSeekV4FlashVisionExp: LLModel = LLModel(
        provider = LLMProvider.DeepSeek,
        id = "deepseek-v4-flash-vision-exp",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Temperature,
            LLMCapability.Tools,
            LLMCapability.ToolChoice,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.MultipleChoices,
            LLMCapability.Thinking,
            LLMCapability.Vision.Image,
        ),
        contextLength = 1_000_000,
        maxOutputTokens = 384_000
    )

    /**
     * DeepSeek V4.1 Flash supports image input, tools, and thinking or non-thinking generation.
     * Uses the current stable Flash ID with a one-million-token context window.
     *
     * @see <a href="https://api-docs.deepseek.com/guides/vision/">DeepSeek vision guide</a>
     */
    @JvmField
    public val DeepSeekV4_1Flash: LLModel = DeepSeekV4Flash.copy(id = "deepseek-flash")

    /**
     * List of the supported models by the DeepSeek provider.
     */
    private val supportedModels: List<LLModel> = listOf(
        DeepSeekV4_1Flash,
        DeepSeekV4Flash,
        DeepSeekV4FlashVisionExp,
        DeepSeekV4Pro,
    )

    /**
     * List of custom models added to the DeepSeek provider.
     */
    private val customModels: MutableList<LLModel> = mutableListOf()

    override val models: List<LLModel>
        get() = supportedModels + customModels

    override fun addCustomModel(model: LLModel) {
        require(model.provider == LLMProvider.DeepSeek) { "Model provider must be DeepSeek" }
        customModels.add(model)
    }
}
