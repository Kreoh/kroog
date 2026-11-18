package ai.koog.prompt.executor.clients.bedrock

import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.Serializable

/**
 * Represents a sealed class for AWS Bedrock model families.
 *
 * This class serves as a hierarchy for different sub-providers under the AWS Bedrock ecosystem.
 * Each sub-provider is represented as a specific sealed `data object` with an associated
 * unique identifier and display name.
 *
 * @property id The unique identifier of the Bedrock model family.
 * @property display The human-readable display name of the Bedrock model family.
 */
@Serializable
public sealed class BedrockModelFamilies(
    public val id: String,
    public val display: String
) {

    /**
     * Represents the Anthropic sub-provider under AWS Bedrock.
     */
    @Serializable
    public data object AnthropicClaude : BedrockModelFamilies("bedrock.anthropic", "AWS Bedrock (Anthropic Claude)")

    /**
     * Represents the Amazon sub-provider under AWS Bedrock.
     */
    @Serializable
    public data object AmazonNova : BedrockModelFamilies("bedrock.amazon", "AWS Bedrock (Amazon Nova)")

    /**
     * Represents the Meta sub-provider under AWS Bedrock.
     */
    @Serializable
    public data object Meta : BedrockModelFamilies("bedrock.meta", "AWS Bedrock (Meta Llama)")

    /**
     * Represents the Amazon Titan sub-provider under AWS Bedrock.
     */
    @Serializable
    public data object TitanEmbedding : BedrockModelFamilies("bedrock.titan", "AWS Bedrock (Amazon Titan Embedding)")

    /**
     * Represents the Cohere sub-provider under AWS Bedrock.
     */
    @Serializable
    public data object Cohere : BedrockModelFamilies("bedrock.cohere", "AWS Bedrock (Cohere Embeddings)")

    /**
     * Represents the Moonshot AI (Kimi) sub-provider under AWS Bedrock.
     */
    @Serializable
    public data object MoonshotKimi : BedrockModelFamilies("bedrock.moonshot", "AWS Bedrock (Moonshot Kimi)")

    /**
     * Represents the Google sub-provider under AWS Bedrock.
     */
    @Serializable
    public data object GoogleGemma : BedrockModelFamilies("bedrock.google", "AWS Bedrock (Google Gemma)")

    /**
     * Represents the MiniMax sub-provider under AWS Bedrock.
     */
    @Serializable
    public data object MiniMax : BedrockModelFamilies("bedrock.minimax", "AWS Bedrock (MiniMax)")

    /**
     * Represents the OpenAI sub-provider under AWS Bedrock.
     */
    @Serializable
    public data object OpenAI : BedrockModelFamilies("bedrock.openai", "AWS Bedrock (OpenAI)")
}

internal fun String.isBedrockFable52(): Boolean =
    this == "us.anthropic.claude-fable-5-2" || this == "global.anthropic.claude-fable-5-2" ||
        this == "anthropic.claude-fable-5-2"

internal fun validateFable52Turn(toolChoice: LLMParams.ToolChoice?, assistantPrefill: Boolean) {
    require(toolChoice == null || toolChoice == LLMParams.ToolChoice.Auto || toolChoice == LLMParams.ToolChoice.None) {
        "Claude Fable 5.2 rejects forced tool choice."
    }
    require(!assistantPrefill) { "Claude Fable 5.2 rejects assistant prefill." }
}

internal fun String.knownBedrockFamily(): BedrockModelFamilies? {
    return when {
        contains("anthropic.claude") || startsWith("claude-") -> BedrockModelFamilies.AnthropicClaude

        contains("amazon.nova") -> BedrockModelFamilies.AmazonNova

        contains("meta.llama") -> BedrockModelFamilies.Meta

        contains("amazon.titan") -> BedrockModelFamilies.TitanEmbedding

        contains("cohere.embed") -> BedrockModelFamilies.Cohere

        contains("moonshot.kimi") || contains("moonshotai.kimi") -> BedrockModelFamilies.MoonshotKimi

        contains("google.gemma") -> BedrockModelFamilies.GoogleGemma

        contains("minimax.") -> BedrockModelFamilies.MiniMax

        contains("openai.gpt") -> BedrockModelFamilies.OpenAI

        else -> null
    }
}
