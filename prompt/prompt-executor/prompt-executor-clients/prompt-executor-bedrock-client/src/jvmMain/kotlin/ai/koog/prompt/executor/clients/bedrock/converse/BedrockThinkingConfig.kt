package ai.koog.prompt.executor.clients.bedrock.converse

/**
 * Controls model reasoning for Bedrock Converse requests.
 */
public sealed interface BedrockThinkingConfig {
    /**
     * Enables adaptive reasoning with the requested [effort] and response [display].
     */
    public data class Adaptive(
        public val effort: BedrockReasoningEffort,
        public val display: BedrockThinkingDisplay,
    ) : BedrockThinkingConfig
}

/**
 * Amount of reasoning effort requested from a Bedrock model.
 */
public enum class BedrockReasoningEffort {
    LOW,
    MEDIUM,
    HIGH,
    MAX,
}

/**
 * Controls how Bedrock includes reasoning in the response.
 */
public enum class BedrockThinkingDisplay {
    OMITTED,
    SUMMARIZED,
}
