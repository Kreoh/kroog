package ai.koog.prompt.executor.clients.google

import ai.koog.prompt.executor.clients.google.models.GoogleThinkingConfig
import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.json.JsonElement

internal fun LLMParams.toGoogleParams(): GoogleParams {
    if (this is GoogleParams) return this
    return GoogleParams(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
    )
}

/**
 * Describes whether the configured Gemini API can combine hosted execution with custom function declarations.
 */
public enum class GoogleHostedExecutionToolCombination {
    /** The API accepts hosted execution and custom functions in one request. */
    SUPPORTED,

    /** Custom functions take precedence and hosted execution must be omitted. */
    CUSTOM_TOOLS_TAKE_PRECEDENCE,
}

/** Configuration that enables Gemini provider-hosted code execution. */
public data class GoogleHostedExecutionConfig(
    public val toolCombination: GoogleHostedExecutionToolCombination =
        GoogleHostedExecutionToolCombination.SUPPORTED,
)

/** Typed result of resolving hosted execution against the supplied custom tools. */
public sealed interface GoogleHostedExecutionDecision {
    /** Hosted execution was not requested. */
    public data object NotRequested : GoogleHostedExecutionDecision

    /** Hosted execution is included, optionally alongside custom tools. */
    public data class Included(public val combinedWithCustomTools: Boolean) : GoogleHostedExecutionDecision

    /** The configured API cannot combine both kinds of tool, so custom tools were retained. */
    public data object CustomToolsTakePrecedence : GoogleHostedExecutionDecision
}

/**
 * Google Generate API parameters layered on top of [LLMParams].
 *
 * @property temperature Sampling temperature in [0.0, 2.0]. Higher ⇒ more random;
 *   lower ⇒ more deterministic. Use closer to 0.0 for analytical tasks, closer to 1.0
 *   for creative tasks.
 * @property maxTokens Maximum number of tokens the model may generate for this response.
 * @property numberOfChoices Number of completions to generate for the prompt (cost scales with N).
 * @property speculation Provider-specific control for speculative decoding / draft acceleration.
 * @property schema JSON Schema to constrain model output (validated when supported).
 * @property toolChoice Controls if/which tool must be called (`none`/`auto`/`required`/specific).
 * @property user not used for Google
 * @property additionalProperties Additional properties that can be used to store custom parameters.
 * @property topP The maximum cumulative probability of tokens to consider when sampling.
 * @property topK The maximum number of tokens to consider when sampling.
 * @property thinkingConfig Controls whether the model should expose its chain-of-thought
 * and how many tokens it may spend on it (see [GoogleThinkingConfig]).
 * @property hostedExecution Enables Gemini provider-hosted Python execution and declares tool-combination support.
 */
@Suppress("LongParameterList")
public class GoogleParams private constructor(
    temperature: Double? = null,
    maxTokens: Int? = null,
    numberOfChoices: Int? = null,
    speculation: String? = null,
    schema: Schema? = null,
    toolChoice: ToolChoice? = null,
    user: String? = null,
    additionalProperties: Map<String, JsonElement>? = null,
    public val topP: Double? = null,
    public val topK: Int? = null,
    public val thinkingConfig: GoogleThinkingConfig? = null,
    public val hostedExecution: GoogleHostedExecutionConfig? = null,
    @Suppress("UNUSED_PARAMETER") compatibilityMarker: Boolean,
) : LLMParams(
    temperature,
    maxTokens,
    numberOfChoices,
    speculation,
    schema,
    toolChoice,
    user,
    additionalProperties,
) {
    /** Preserves the JVM constructor layout published before hosted execution was added. */
    public constructor(
        temperature: Double? = null,
        maxTokens: Int? = null,
        numberOfChoices: Int? = null,
        speculation: String? = null,
        schema: Schema? = null,
        toolChoice: ToolChoice? = null,
        user: String? = null,
        additionalProperties: Map<String, JsonElement>? = null,
        topP: Double? = null,
        topK: Int? = null,
        thinkingConfig: GoogleThinkingConfig? = null,
    ) : this(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
        topP = topP,
        topK = topK,
        thinkingConfig = thinkingConfig,
        hostedExecution = null,
        compatibilityMarker = false,
    )

    /** Creates Google parameters with Gemini provider-hosted code execution enabled. */
    public constructor(
        hostedExecution: GoogleHostedExecutionConfig,
        temperature: Double? = null,
        maxTokens: Int? = null,
        numberOfChoices: Int? = null,
        speculation: String? = null,
        schema: Schema? = null,
        toolChoice: ToolChoice? = null,
        user: String? = null,
        additionalProperties: Map<String, JsonElement>? = null,
        topP: Double? = null,
        topK: Int? = null,
        thinkingConfig: GoogleThinkingConfig? = null,
    ) : this(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
        topP = topP,
        topK = topK,
        thinkingConfig = thinkingConfig,
        hostedExecution = hostedExecution,
        compatibilityMarker = true,
    )

    /** Returns a copy with Gemini provider-hosted execution enabled, disabled, or reconfigured. */
    public fun withHostedExecution(hostedExecution: GoogleHostedExecutionConfig?): GoogleParams = GoogleParams(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
        topP = topP,
        topK = topK,
        thinkingConfig = thinkingConfig,
        hostedExecution = hostedExecution,
        compatibilityMarker = true,
    )

    init {
        require(temperature == null || temperature in 0.0..2.0) {
            "temperature must be in [0.0, 2.0], but was $temperature"
        }
        require(topP == null || topP in 0.0..1.0) {
            "topP must be in [0.0, 1.0], but was $topP"
        }
        require(topK == null || topK >= 0) {
            "topK must be >= 0, but was $topK"
        }
    }

    override fun copy(
        temperature: Double?,
        maxTokens: Int?,
        numberOfChoices: Int?,
        speculation: String?,
        schema: Schema?,
        toolChoice: ToolChoice?,
        user: String?,
        additionalProperties: Map<String, JsonElement>?,
    ): GoogleParams = copy(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
        topP = topP,
        topK = topK,
        thinkingConfig = thinkingConfig,
    )

    /**
     * Creates a copy of this instance with the ability to modify any of its properties.
     */
    public fun copy(
        temperature: Double? = this.temperature,
        maxTokens: Int? = this.maxTokens,
        numberOfChoices: Int? = this.numberOfChoices,
        speculation: String? = this.speculation,
        schema: Schema? = this.schema,
        toolChoice: ToolChoice? = this.toolChoice,
        user: String? = this.user,
        additionalProperties: Map<String, JsonElement>? = this.additionalProperties,
        topP: Double? = this.topP,
        topK: Int? = this.topK,
        thinkingConfig: GoogleThinkingConfig? = this.thinkingConfig,
    ): GoogleParams = GoogleParams(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
        topP = topP,
        topK = topK,
        thinkingConfig = thinkingConfig,
        hostedExecution = this.hostedExecution,
        compatibilityMarker = true,
    )

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other !is GoogleParams -> false
        else ->
            temperature == other.temperature &&
                maxTokens == other.maxTokens &&
                numberOfChoices == other.numberOfChoices &&
                speculation == other.speculation &&
                schema == other.schema &&
                toolChoice == other.toolChoice &&
                user == other.user &&
                additionalProperties == other.additionalProperties &&
                topP == other.topP &&
                topK == other.topK &&
                thinkingConfig == other.thinkingConfig &&
                hostedExecution == other.hostedExecution
    }

    override fun hashCode(): Int = listOf(
        temperature, maxTokens, numberOfChoices,
        speculation, schema, toolChoice, user,
        additionalProperties, topP, topK, thinkingConfig, hostedExecution
    ).fold(0) { acc, element ->
        31 * acc + (element?.hashCode() ?: 0)
    }

    override fun toString(): String = buildString {
        append("GoogleParams(")
        append("temperature=$temperature")
        append(", maxTokens=$maxTokens")
        append(", numberOfChoices=$numberOfChoices")
        append(", speculation=$speculation")
        append(", schema=$schema")
        append(", toolChoice=$toolChoice")
        append(", user=$user")
        append(", additionalProperties=$additionalProperties")
        append(", topP=$topP")
        append(", topK=$topK")
        append(", thinkingConfig=$thinkingConfig")
        append(", hostedExecution=$hostedExecution")
        append(")")
    }
}
