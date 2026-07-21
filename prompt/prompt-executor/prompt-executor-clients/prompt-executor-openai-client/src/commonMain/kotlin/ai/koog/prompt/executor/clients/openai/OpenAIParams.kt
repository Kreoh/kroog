package ai.koog.prompt.executor.clients.openai

import ai.koog.prompt.executor.clients.openai.base.models.OpenAIAudioConfig
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIWebSearchOptions
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.base.models.ServiceTier
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.executor.clients.openai.models.Truncation
import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.json.JsonElement
import org.jetbrains.annotations.ApiStatus.Experimental
import kotlin.jvm.JvmOverloads

internal sealed interface OpenAIParams

internal fun LLMParams.toOpenAIChatParams(): OpenAIChatParams {
    if (this is OpenAIChatParams) return this
    return OpenAIChatParams(
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

internal fun LLMParams.toOpenAIResponsesParams(): OpenAIResponsesParams {
    if (this is OpenAIResponsesParams) return this
    return OpenAIResponsesParams(
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
 * OpenAI chat/completions parameters layered on top of [LLMParams].
 *
 * These options mirror the fields commonly used with OpenAI’s Chat Completions /
 * Responses APIs and add OpenAI-specific controls (audio, logprobs, reasoning effort,
 * response formatting, service tiers, etc.). All parameters are optional; when unset,
 * provider/model defaults apply.
 *
 * @property temperature Sampling temperature in [0.0, 2.0]. Higher ⇒ more random;
 *   lower ⇒ more deterministic. Adjust this **or** [topP], not both.
 * @property maxTokens Maximum number of tokens the model may generate for this response.
 * @property numberOfChoices Number of completions to generate for the prompt (cost scales with N).
 * @property speculation Provider-specific control for speculative decoding / draft acceleration.
 * @property schema JSON Schema to constrain model output (validated when supported).
 * @property toolChoice Controls if/which tool must be called (`none`/`auto`/`required`/specific).
 * @property user (**Deprecated**) legacy stable end-user identifier; prefer [safetyIdentifier]
 *   and [promptCacheKey] to preserve caching and safety benefits.
 * @property additionalProperties Additional properties that can be used to store custom parameters.
 * @property frequencyPenalty Number in [-2.0, 2.0]—penalizes frequent tokens to reduce repetition.
 * @property presencePenalty Number in [-2.0, 2.0]—encourages an introduction of new tokens/topics.
 * @property parallelToolCalls Allow multiple tool calls in parallel.
 * @property promptCacheKey Stable cache key for prompt caching (non-blank when provided).
 * @property safetyIdentifier Stable app-scoped user ID for policy enforcement (non-blank when provided).
 * @property serviceTier Processing tier selection for cost/latency trade-offs.
 * @property store Whether the provider may store outputs for improvement/evals.
 * @property audio Audio output configuration when using audio-capable models.
 * @property logprobs Whether to include log-probabilities for output tokens.
 * @property reasoningEffort Constrains reasoning effort (e.g., MINIMAL/LOW/MEDIUM/HIGH).
 * @property stop Stop sequences (0–4 items); generation halts before any of these.
 * @property topLogprobs Number of top alternatives per position (0–20). Requires [logprobs] = true.
 * @property topP Nucleus sampling in (0.0, 1.0]; use **instead of** [temperature].
 * @property webSearchOptions Configure web search tool usage (if supported).
 */
@Suppress("LongParameterList")
public class OpenAIChatParams(
    temperature: Double? = null,
    maxTokens: Int? = null,
    numberOfChoices: Int? = null,
    speculation: String? = null,
    schema: Schema? = null,
    toolChoice: ToolChoice? = null,
    user: String? = null,
    additionalProperties: Map<String, JsonElement>? = null,
    public val frequencyPenalty: Double? = null,
    public val presencePenalty: Double? = null,
    public val parallelToolCalls: Boolean? = null,
    public val promptCacheKey: String? = null,
    public val safetyIdentifier: String? = null,
    public val serviceTier: ServiceTier? = null,
    public val store: Boolean? = null,
    public val audio: OpenAIAudioConfig? = null,
    public val logprobs: Boolean? = null,
    public val reasoningEffort: ReasoningEffort? = null,
    public val stop: List<String>? = null,
    public val topLogprobs: Int? = null,
    public val topP: Double? = null,
    public val webSearchOptions: OpenAIWebSearchOptions? = null
) : LLMParams(
    temperature,
    maxTokens,
    numberOfChoices,
    speculation,
    schema,
    toolChoice,
    user,
    additionalProperties
),
    OpenAIParams {
    init {
        // Mutual exclusivity: temperature and topP
        require(!(temperature != null && topP != null)) {
            "temperature and topP are mutually exclusive"
        }

        // topP bounds
        if (topP != null) {
            require(topP >= 0.0) { "TopP must be positive" }
            require(topP <= 1.0) { "TopP must be <= 1" }
        }

        // topLogprobs requires logprobs=true, and bounds
        if (topLogprobs != null) {
            require(logprobs != false) {
                "topLogprobs should not be provided when logprobs=false"
            }
            require(topLogprobs in 0..20) { "`topLogprobs` must be in [0, 20], but was $topLogprobs" }
        }

        require(promptCacheKey == null || promptCacheKey.isNotBlank()) {
            "promptCacheKey must be non-blank"
        }

        require(safetyIdentifier == null || safetyIdentifier.isNotBlank()) {
            "safetyIdentifier must be non-blank"
        }

        // --- Stop sequences ---
        if (stop != null) {
            require(stop.isNotEmpty()) { "stop must not be empty when provided." }
            require(stop.size <= 4) { "stop supports at most 4 sequences, but was ${stop.size}" }
            require(stop.all { it.isNotBlank() }) { "stop sequences must not be blank." }
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
    ): OpenAIChatParams = copy(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
        frequencyPenalty = frequencyPenalty,
        presencePenalty = presencePenalty,
        parallelToolCalls = parallelToolCalls,
        promptCacheKey = promptCacheKey,
        safetyIdentifier = safetyIdentifier,
        serviceTier = serviceTier,
        store = store,
        audio = audio,
        logprobs = logprobs,
        reasoningEffort = reasoningEffort,
        stop = stop,
        topLogprobs = topLogprobs,
        topP = topP,
        webSearchOptions = webSearchOptions,
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
        frequencyPenalty: Double? = this.frequencyPenalty,
        presencePenalty: Double? = this.presencePenalty,
        parallelToolCalls: Boolean? = this.parallelToolCalls,
        promptCacheKey: String? = this.promptCacheKey,
        safetyIdentifier: String? = this.safetyIdentifier,
        serviceTier: ServiceTier? = this.serviceTier,
        store: Boolean? = this.store,
        audio: OpenAIAudioConfig? = this.audio,
        logprobs: Boolean? = this.logprobs,
        reasoningEffort: ReasoningEffort? = this.reasoningEffort,
        stop: List<String>? = this.stop,
        topLogprobs: Int? = this.topLogprobs,
        topP: Double? = this.topP,
        webSearchOptions: OpenAIWebSearchOptions? = this.webSearchOptions,
    ): OpenAIChatParams = OpenAIChatParams(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
        frequencyPenalty = frequencyPenalty,
        presencePenalty = presencePenalty,
        parallelToolCalls = parallelToolCalls,
        promptCacheKey = promptCacheKey,
        safetyIdentifier = safetyIdentifier,
        serviceTier = serviceTier,
        store = store,
        audio = audio,
        logprobs = logprobs,
        reasoningEffort = reasoningEffort,
        stop = stop,
        topLogprobs = topLogprobs,
        topP = topP,
        webSearchOptions = webSearchOptions,
    )

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other !is OpenAIChatParams -> false
        else ->
            temperature == other.temperature &&
                maxTokens == other.maxTokens &&
                numberOfChoices == other.numberOfChoices &&
                speculation == other.speculation &&
                schema == other.schema &&
                toolChoice == other.toolChoice &&
                user == other.user &&
                additionalProperties == other.additionalProperties &&
                frequencyPenalty == other.frequencyPenalty &&
                presencePenalty == other.presencePenalty &&
                parallelToolCalls == other.parallelToolCalls &&
                promptCacheKey == other.promptCacheKey &&
                safetyIdentifier == other.safetyIdentifier &&
                serviceTier == other.serviceTier &&
                store == other.store &&
                audio == other.audio &&
                logprobs == other.logprobs &&
                reasoningEffort == other.reasoningEffort &&
                stop == other.stop &&
                topLogprobs == other.topLogprobs &&
                topP == other.topP &&
                webSearchOptions == other.webSearchOptions
    }

    override fun hashCode(): Int = listOf(
        temperature, maxTokens, numberOfChoices,
        speculation, schema, toolChoice, user,
        additionalProperties, frequencyPenalty, presencePenalty,
        parallelToolCalls, promptCacheKey,
        safetyIdentifier, serviceTier,
        store, audio, logprobs,
        reasoningEffort, stop, topLogprobs,
        topP, webSearchOptions
    ).fold(0) { acc, element ->
        31 * acc + (element?.hashCode() ?: 0)
    }

    override fun toString(): String = buildString {
        append("OpenAIChatParams(")
        append("temperature=$temperature")
        append(", maxTokens=$maxTokens")
        append(", numberOfChoices=$numberOfChoices")
        append(", speculation=$speculation")
        append(", schema=$schema")
        append(", toolChoice=$toolChoice")
        append(", user=$user")
        append(", additionalProperties=$additionalProperties")
        append(", frequencyPenalty=$frequencyPenalty")
        append(", presencePenalty=$presencePenalty")
        append(", parallelToolCalls=$parallelToolCalls")
        append(", promptCacheKey=$promptCacheKey")
        append(", safetyIdentifier=$safetyIdentifier")
        append(", serviceTier=$serviceTier")
        append(", store=$store")
        append(", audio=$audio")
        append(", logprobs=$logprobs")
        append(", reasoningEffort=$reasoningEffort")
        append(", stop=$stop")
        append(", topLogprobs=$topLogprobs")
        append(", topP=$topP")
        append(", webSearchOptions=$webSearchOptions")
        append(")")
    }
}

/**
 * Configures the OpenAI Responses Code Interpreter hosted tool.
 *
 * When [containerId] is null, OpenAI creates an automatic container and makes [fileIds]
 * available to it. When [containerId] is present, that existing container is reused. In
 * that mode [fileIds] are retained solely for a bounded stale-container reconstruction.
 *
 * @property fileIds Validated provider file identifiers for a new automatic container.
 * @property containerId A validated existing provider container identifier to reuse.
 */
public data class OpenAICodeInterpreterConfig @JvmOverloads constructor(
    public val fileIds: List<String> = emptyList(),
    public val containerId: String? = null,
) {
    init {
        require(fileIds.all { it.isNotBlank() && it == it.trim() }) {
            "Code Interpreter fileIds must contain only non-blank provider file IDs"
        }
        require(fileIds.distinct().size == fileIds.size) {
            "Code Interpreter fileIds must not contain duplicates"
        }
        require(containerId == null || (containerId.isNotBlank() && containerId == containerId.trim())) {
            "Code Interpreter containerId must be a non-blank provider container ID"
        }
    }
}

/**
 * OpenAI **Responses API** parameters layered on top of [LLMParams].
 *
 * Use these options to generate text or JSON, call built-in tools (e.g., web/file search)
 * or your own functions, enable background processing, include auxiliary outputs, and tune
 * sampling, reasoning, and truncation behavior. All parameters are optional; when unset,
 * provider/model defaults apply.
 *
 * @property temperature Sampling temperature in [0.0, 2.0]. Higher ⇒ more random; lower ⇒ more deterministic.
 *   Adjust this **or** [topP], not both.
 * @property maxTokens Maximum number of tokens the model may generate for this response.
 * @property numberOfChoices Number of completions to generate for the prompt (cost scales with N).
 * @property speculation Provider-specific control for speculative decoding / draft acceleration.
 * @property schema JSON Schema to constrain model output (validated when supported).
 * @property toolChoice Controls if/which tool(s) may be called (`none` / `auto` / `required` / specific).
 * @property user (**Deprecated**) legacy stable end-user identifier; prefer [safetyIdentifier] and
 *   [promptCacheKey] to preserve caching and safety benefits.
 * @property additionalProperties Additional properties that can be used to store custom parameters.
 * @property background Run the response in the background (non-blocking).
 * @property include Additional output sections to include (see the list above).
 * @property maxToolCalls Maximum total number of built-in tool calls allowed in this response (≥ 0).
 * @property parallelToolCalls Whether tool calls may run in parallel.
 * @property reasoning Reasoning configuration for reasoning-capable models.
 * @property truncation Truncation strategy when nearing the context window.
 * @property promptCacheKey Stable cache key for prompt caching (non-blank when provided).
 * @property safetyIdentifier Stable app-scoped user ID for policy enforcement (non-blank when provided).
 * @property serviceTier Processing tier selection for cost/latency trade-offs.
 * @property store Whether the provider may store outputs for later retrieval/evals.
 * @property logprobs Whether to include log-probabilities for output tokens.
 * @property topLogprobs Number of top alternatives per position (0–20). Requires [logprobs] = true.
 * @property topP Nucleus sampling in (0.0, 1.0]; use **instead of** [temperature].
 * @property codeInterpreter Optional typed configuration for the hosted Code Interpreter tool.
 * @property stateless Whether this is the ASK-compatible stateless path. Stateless requests always use
 *   `store=false`, include encrypted reasoning, and rebuild complete typed history without a previous response ID.
 */
@Experimental
public class OpenAIResponsesParams(
    temperature: Double? = null,
    maxTokens: Int? = null,
    numberOfChoices: Int? = null,
    speculation: String? = null,
    schema: Schema? = null,
    toolChoice: ToolChoice? = null,
    user: String? = null,
    additionalProperties: Map<String, JsonElement>? = null,
    public val background: Boolean? = null,
    public val include: List<OpenAIInclude>? = null,
    public val maxToolCalls: Int? = null,
    public val parallelToolCalls: Boolean? = null,
    public val reasoning: ReasoningConfig? = null,
    public val truncation: Truncation? = null,
    public val promptCacheKey: String? = null,
    public val safetyIdentifier: String? = null,
    public val serviceTier: ServiceTier? = null,
    public val store: Boolean? = null,
    public val logprobs: Boolean? = null,
    public val topLogprobs: Int? = null,
    public val topP: Double? = null,
    public val codeInterpreter: OpenAICodeInterpreterConfig? = null,
    public val stateless: Boolean = false,
) : LLMParams(
    temperature,
    maxTokens,
    numberOfChoices,
    speculation,
    schema,
    toolChoice,
    user,
    additionalProperties
),
    OpenAIParams {
    /**
     * Preserves the JVM constructor layout published before [codeInterpreter] was added.
     */
    public constructor(
        temperature: Double?,
        maxTokens: Int? = null,
        numberOfChoices: Int? = null,
        speculation: String? = null,
        schema: Schema? = null,
        toolChoice: ToolChoice? = null,
        user: String? = null,
        additionalProperties: Map<String, JsonElement>? = null,
        background: Boolean? = null,
        include: List<OpenAIInclude>? = null,
        maxToolCalls: Int? = null,
        parallelToolCalls: Boolean? = null,
        reasoning: ReasoningConfig? = null,
        truncation: Truncation? = null,
        promptCacheKey: String? = null,
        safetyIdentifier: String? = null,
        serviceTier: ServiceTier? = null,
        store: Boolean? = null,
        logprobs: Boolean? = null,
        topLogprobs: Int? = null,
        topP: Double? = null,
    ) : this(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
        background = background,
        include = include,
        maxToolCalls = maxToolCalls,
        parallelToolCalls = parallelToolCalls,
        reasoning = reasoning,
        truncation = truncation,
        promptCacheKey = promptCacheKey,
        safetyIdentifier = safetyIdentifier,
        serviceTier = serviceTier,
        store = store,
        logprobs = logprobs,
        topLogprobs = topLogprobs,
        topP = topP,
        codeInterpreter = null,
        stateless = false,
    )

    /**
     * Preserves the JVM constructor layout and synthetic default constructor published with [codeInterpreter].
     */
    public constructor(
        temperature: Double?,
        maxTokens: Int? = null,
        numberOfChoices: Int? = null,
        speculation: String? = null,
        schema: Schema? = null,
        toolChoice: ToolChoice? = null,
        user: String? = null,
        additionalProperties: Map<String, JsonElement>? = null,
        background: Boolean? = null,
        include: List<OpenAIInclude>? = null,
        maxToolCalls: Int? = null,
        parallelToolCalls: Boolean? = null,
        reasoning: ReasoningConfig? = null,
        truncation: Truncation? = null,
        promptCacheKey: String? = null,
        safetyIdentifier: String? = null,
        serviceTier: ServiceTier? = null,
        store: Boolean? = null,
        logprobs: Boolean? = null,
        topLogprobs: Int? = null,
        topP: Double? = null,
        codeInterpreter: OpenAICodeInterpreterConfig? = null,
    ) : this(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
        background = background,
        include = include,
        maxToolCalls = maxToolCalls,
        parallelToolCalls = parallelToolCalls,
        reasoning = reasoning,
        truncation = truncation,
        promptCacheKey = promptCacheKey,
        safetyIdentifier = safetyIdentifier,
        serviceTier = serviceTier,
        store = store,
        logprobs = logprobs,
        topLogprobs = topLogprobs,
        topP = topP,
        codeInterpreter = codeInterpreter,
        stateless = false,
    )

    init {
        // Mutual exclusivity: temperature and topP
        require(!(temperature != null && topP != null)) {
            "temperature and topP are mutually exclusive"
        }

        require(topP == null || topP in 0.0..1.0) {
            "topP must be in (0.0, 1.0], but was $topP"
        }
        if (topLogprobs != null) {
            require(logprobs == true) {
                "`topLogprobs` requires `logprobs=true`."
            }
            require(topLogprobs in 0..20) {
                "`topLogprobs` must be in [0, 20], but was $topLogprobs"
            }
        }
        require(promptCacheKey == null || promptCacheKey.isNotBlank()) {
            "promptCacheKey must be non-blank"
        }

        require(safetyIdentifier == null || safetyIdentifier.isNotBlank()) {
            "safetyIdentifier must be non-blank"
        }

        // include validations
        if (include != null) {
            require(include.isNotEmpty()) { "include must not be empty when provided." }
        }

        // maxToolCalls bounds
        if (maxToolCalls != null) {
            require(maxToolCalls >= 0) { "maxToolCalls must be >= 0" }
        }
        require(!stateless || store != true) {
            "Stateless Responses requests require store=false"
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
    ): OpenAIResponsesParams = copy(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
        background = background,
        include = include,
        maxToolCalls = maxToolCalls,
        parallelToolCalls = parallelToolCalls,
        reasoning = reasoning,
        truncation = truncation,
        promptCacheKey = promptCacheKey,
        safetyIdentifier = safetyIdentifier,
        serviceTier = serviceTier,
        store = store,
        logprobs = logprobs,
        topLogprobs = topLogprobs,
        topP = topP,
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
        background: Boolean? = this.background,
        include: List<OpenAIInclude>? = this.include,
        maxToolCalls: Int? = this.maxToolCalls,
        parallelToolCalls: Boolean? = this.parallelToolCalls,
        reasoning: ReasoningConfig? = this.reasoning,
        truncation: Truncation? = this.truncation,
        promptCacheKey: String? = this.promptCacheKey,
        safetyIdentifier: String? = this.safetyIdentifier,
        serviceTier: ServiceTier? = this.serviceTier,
        store: Boolean? = this.store,
        logprobs: Boolean? = this.logprobs,
        topLogprobs: Int? = this.topLogprobs,
        topP: Double? = this.topP,
    ): OpenAIResponsesParams = OpenAIResponsesParams(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
        background = background,
        include = include,
        maxToolCalls = maxToolCalls,
        parallelToolCalls = parallelToolCalls,
        reasoning = reasoning,
        truncation = truncation,
        promptCacheKey = promptCacheKey,
        safetyIdentifier = safetyIdentifier,
        serviceTier = serviceTier,
        store = store,
        logprobs = logprobs,
        topLogprobs = topLogprobs,
        topP = topP,
        codeInterpreter = this.codeInterpreter,
        stateless = this.stateless,
    )

    /**
     * Returns a copy using [codeInterpreter] as its hosted Code Interpreter configuration.
     */
    public fun withCodeInterpreter(codeInterpreter: OpenAICodeInterpreterConfig?): OpenAIResponsesParams =
        OpenAIResponsesParams(
            temperature = temperature,
            maxTokens = maxTokens,
            numberOfChoices = numberOfChoices,
            speculation = speculation,
            schema = schema,
            toolChoice = toolChoice,
            user = user,
            additionalProperties = additionalProperties,
            background = background,
            include = include,
            maxToolCalls = maxToolCalls,
            parallelToolCalls = parallelToolCalls,
            reasoning = reasoning,
            truncation = truncation,
            promptCacheKey = promptCacheKey,
            safetyIdentifier = safetyIdentifier,
            serviceTier = serviceTier,
            store = store,
            logprobs = logprobs,
            topLogprobs = topLogprobs,
            topP = topP,
            codeInterpreter = codeInterpreter,
            stateless = stateless,
        )

    /** Returns an ASK-compatible stateless copy that never permits provider-side response storage. */
    public fun asStateless(): OpenAIResponsesParams = OpenAIResponsesParams(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
        background = background,
        include = include,
        maxToolCalls = maxToolCalls,
        parallelToolCalls = parallelToolCalls,
        reasoning = reasoning,
        truncation = truncation,
        promptCacheKey = promptCacheKey,
        safetyIdentifier = safetyIdentifier,
        serviceTier = serviceTier,
        store = false,
        logprobs = logprobs,
        topLogprobs = topLogprobs,
        topP = topP,
        codeInterpreter = codeInterpreter,
        stateless = true,
    )

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other !is OpenAIResponsesParams -> false
        else ->
            temperature == other.temperature &&
                maxTokens == other.maxTokens &&
                numberOfChoices == other.numberOfChoices &&
                speculation == other.speculation &&
                schema == other.schema &&
                toolChoice == other.toolChoice &&
                user == other.user &&
                additionalProperties == other.additionalProperties &&
                background == other.background &&
                include == other.include &&
                maxToolCalls == other.maxToolCalls &&
                parallelToolCalls == other.parallelToolCalls &&
                reasoning == other.reasoning &&
                truncation == other.truncation &&
                promptCacheKey == other.promptCacheKey &&
                safetyIdentifier == other.safetyIdentifier &&
                serviceTier == other.serviceTier &&
                store == other.store &&
                logprobs == other.logprobs &&
                topLogprobs == other.topLogprobs &&
                topP == other.topP &&
                codeInterpreter == other.codeInterpreter &&
                stateless == other.stateless
    }

    override fun hashCode(): Int = listOf(
        temperature, maxTokens, numberOfChoices,
        speculation, schema, toolChoice, user,
        additionalProperties, background, include, maxToolCalls,
        parallelToolCalls, reasoning,
        truncation, promptCacheKey, safetyIdentifier,
        serviceTier, store, logprobs, topLogprobs, topP,
        codeInterpreter,
        stateless,
    ).fold(0) { acc, element ->
        31 * acc + (element?.hashCode() ?: 0)
    }

    override fun toString(): String = buildString {
        append("OpenAIResponsesParams(")
        append("temperature=$temperature")
        append(", maxTokens=$maxTokens")
        append(", numberOfChoices=$numberOfChoices")
        append(", speculation=$speculation")
        append(", schema=$schema")
        append(", toolChoice=$toolChoice")
        append(", user=$user")
        append(", additionalProperties=$additionalProperties")
        append(", background=$background")
        append(", include=$include")
        append(", maxToolCalls=$maxToolCalls")
        append(", parallelToolCalls=$parallelToolCalls")
        append(", reasoning=$reasoning")
        append(", truncation=$truncation")
        append(", promptCacheKey=$promptCacheKey")
        append(", safetyIdentifier=$safetyIdentifier")
        append(", serviceTier=$serviceTier")
        append(", store=$store")
        append(", logprobs=$logprobs")
        append(", topLogprobs=$topLogprobs")
        append(", topP=$topP")
        append(", codeInterpreter=$codeInterpreter")
        append(", stateless=$stateless")
        append(")")
    }
}
