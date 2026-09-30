package ai.koog.prompt.models

import ai.koog.prompt.provider.HostedExecutionCapability
import ai.koog.prompt.provider.ProviderApi
import ai.koog.prompt.provider.ProviderCapabilityMatrix

/** Publisher of a semantic model, independent of its serving provider. */
public enum class ModelPublisher {
    OPENAI,
    ANTHROPIC,
    GOOGLE,
    DEEPSEEK,
    AMAZON,
}

/** Semantic model workload. */
public enum class ModelKind {
    TEXT,
    EMBEDDING,
    REALTIME,
}

/** Provider-neutral form of a model's reasoning control. */
public enum class ReasoningMode {
    CATEGORICAL,
    TOKEN_BUDGET,
}

/** Authoritative reasoning support and exact effort values. */
public sealed interface ReasoningSupport {
    public data object Unsupported : ReasoningSupport

    public data class Supported(
        val mode: ReasoningMode,
        val efforts: Map<String, Double>,
        val minBudgetTokens: Int? = null,
        val maxBudgetTokens: Int? = null,
    ) : ReasoningSupport
}

/**
 * Temperature bounds and provider-specific request restrictions.
 *
 * A null [allowedReasoningEfforts] means that every declared effort accepts temperature.
 * [omittedProviderApis] records APIs where temperature must never be sent.
 */
public data class TemperatureSupport(
    val minimum: Double,
    val maximum: Double,
    val allowedReasoningEfforts: Set<String>? = null,
    val omittedProviderApis: Set<ProviderApi> = emptySet(),
)

/** Why a known provider route is unavailable for a semantic model. */
public enum class ModelProviderApiUnsupportedReason {
    MODEL_SUPPORT_NOT_ESTABLISHED,
    /** Provider documentation explicitly excludes this model from the API. */
    MODEL_API_NOT_SUPPORTED,
}

/** Compatibility of one provider API with a semantic model. */
public sealed interface ModelProviderApiCompatibility {
    public data object Supported : ModelProviderApiCompatibility

    public data class Unsupported(
        val reason: ModelProviderApiUnsupportedReason,
    ) : ModelProviderApiCompatibility

    public data object Undeclared : ModelProviderApiCompatibility
}

/**
 * One authoritative semantic model entry.
 *
 * Provider deployment names, credentials, presentation labels and descriptions do not belong here.
 * [maxInputTokens] is the provider's input ceiling; shared context windows still include generated output.
 * [maxOutputTokens] is zero for embeddings and for text models without a verified independent output ceiling.
 * Use [outputTokenLimit] when applying a generation limit; unknown text limits must not become a zero-token budget.
 */
public data class ModelCatalogueEntry(
    val id: String,
    val aliases: Set<String> = emptySet(),
    val publisher: ModelPublisher,
    val kind: ModelKind,
    val providerApis: Set<ProviderApi>,
    val unsupportedProviderApis: Map<ProviderApi, ModelProviderApiUnsupportedReason> = emptyMap(),
    val reasoning: ReasoningSupport,
    val maxInputTokens: Long,
    val maxOutputTokens: Long,
    val temperature: TemperatureSupport,
    val supportedMimeTypes: Set<String>,
    val structuredOutput: Boolean,
    val hostedExecution: Boolean,
) {
    /** Verified output ceiling, or null when a text model's independent output limit is unknown. */
    public val outputTokenLimit: Long?
        get() = maxOutputTokens.takeUnless { kind == ModelKind.TEXT && it == 0L }

    public val supportsImages: Boolean
        get() = supportedMimeTypes.any { it.startsWith("image/") }

    /** Return deterministic compatibility for a provider API before inference starts. */
    public fun compatibility(api: ProviderApi): ModelProviderApiCompatibility = when {
        api in providerApis -> ModelProviderApiCompatibility.Supported

        api in unsupportedProviderApis -> ModelProviderApiCompatibility.Unsupported(
            unsupportedProviderApis.getValue(api)
        )

        else -> ModelProviderApiCompatibility.Undeclared
    }
}

/** Catalogue validation failure. */
public enum class ModelCatalogueIssueKind {
    DUPLICATE_MODEL_ID,
    DUPLICATE_ALIAS,
    MODEL_ID_ALIAS_COLLISION,
    INVALID_TOKEN_LIMIT,
    INVALID_REASONING,
    INVALID_REASONING_EFFORT,
    INVALID_TEMPERATURE,
    TEMPERATURE_PROVIDER_CONTRADICTION,
    PROVIDER_API_CONTRADICTION,
    PROVIDER_API_SUPPORT_CONTRADICTION,
    EXECUTION_REQUIREMENT_CONTRADICTION,
}

/** A precise validation issue for a semantic model entry. */
public data class ModelCatalogueIssue(
    val kind: ModelCatalogueIssueKind,
    val modelId: String,
    val detail: String,
)

/**
 * Kroog's authoritative semantic model catalogue.
 *
 * The source constants identify the frozen KreLLM baseline used for the original entries. Entries added after that
 * baseline cite their provider documentation in source comments and do not claim KreLLM provenance.
 */
public object ModelCatalogue {
    public const val sourceRevision: String = "ee0eb29e3f0befa21f8637b713fe5d00ce1113df"
    public const val sourceModelsSha256: String =
        "5e554a8a653a8def7a7eeeb127ea6d2d268e47abfac9d954cbb2692869875f06"
    public const val sourceTypesSha256: String =
        "d7c31c8b16ee554f41639e124190447a12c20185bb7f686e775b72100ff5658f"
    public const val sourceConfigSha256: String =
        "9328b0526dbedbe9ad94f57c3444f767d561809301be14d75f0067a385990bbe"

    public val entries: List<ModelCatalogueEntry> = listOf(
        openAiText("gpt-4.1", input = 1_014_808, output = 32_768),
        openAiText("gpt-4.1-mini", input = 1_014_808, output = 32_768),
        openAiText("gpt-4.1-nano", input = 1_014_808, output = 32_768),
        openAiReasoning(
            id = "gpt-5",
            efforts = standardReasoning,
            temperature = fixedOmittedTemperature,
        ),
        openAiReasoning(
            id = "gpt-5-mini",
            efforts = standardReasoning,
            temperature = fixedOmittedTemperature,
        ),
        openAiReasoning(
            id = "gpt-5-nano",
            efforts = standardReasoning,
            temperature = fixedOmittedTemperature,
        ),
        openAiReasoning(
            id = "gpt-5.1",
            efforts = mapOf("none" to 0.0, "low" to 0.25, "medium" to 0.5, "high" to 0.75),
            temperature = fixedAzureOmittedTemperature,
            providerApis = setOf(ProviderApi.AZURE_RESPONSES),
        ),
        openAiReasoning("gpt-5.2", extendedReasoning, conditionalTemperature),
        openAiReasoning(
            id = "gpt-5.4",
            efforts = extendedReasoning,
            temperature = conditionalTemperatureWithCodex,
            input = 922_000,
            providerApis = openAiAzureCodex,
        ),
        openAiReasoning("gpt-5.4-mini", extendedReasoning, conditionalTemperature),
        openAiReasoning("gpt-5.4-nano", extendedReasoning, conditionalTemperature),
        openAiReasoning(
            id = "gpt-5.5",
            efforts = extendedReasoning,
            temperature = conditionalTemperatureWithCodex,
            input = 922_000,
            providerApis = openAiAzureCodex,
        ),
        openAiReasoning(
            id = "gpt-5.6-sol",
            efforts = frontierReasoning,
            temperature = conditionalTemperatureWithCodex,
            input = 922_000,
            providerApis = openAiAzureCodex,
        ),
        openAiReasoning(
            id = "gpt-5.6-terra",
            efforts = frontierReasoning,
            temperature = conditionalTemperatureWithCodex,
            input = 922_000,
            providerApis = openAiAzureCodex,
        ),
        openAiReasoning(
            id = "gpt-5.6-luna",
            efforts = frontierReasoning,
            temperature = conditionalTemperatureWithCodex,
            input = 922_000,
            providerApis = openAiAzureCodex,
        ),
        // Added after the frozen KreLLM baseline. OpenAI documentation:
        // https://developers.openai.com/api/docs/models/gpt-6-astra
        // https://developers.openai.com/api/docs/guides/latest-model?model=gpt-6-astra
        openAiReasoning(
            id = "gpt-6-astra",
            efforts = frontierReasoning - "none",
            temperature = TemperatureSupport(
                1.0,
                1.0,
                omittedProviderApis = astraApis,
            ),
            input = 922_000,
            providerApis = astraApis,
        ),
        // Provider-verified additions: https://developers.openai.com/api/docs/models/gpt-6-sol
        // https://developers.openai.com/api/docs/models/gpt-6-luna
        openAiReasoning(
            id = "gpt-6-sol",
            efforts = frontierReasoning,
            temperature = conditionalTemperature,
            input = 922_000,
            providerApis = setOf(ProviderApi.OPENAI_RESPONSES),
        ),
        openAiReasoning(
            id = "gpt-6-luna",
            efforts = frontierReasoning,
            temperature = conditionalTemperature,
            input = 922_000,
            providerApis = setOf(ProviderApi.OPENAI_RESPONSES),
        ),
        // https://developers.openai.com/api/docs/models/gpt-6.1-sol
        // The input budget reserves 128,000 output tokens from the shared 1,050,000-token context.
        openAiReasoning(
            id = "gpt-6.1-sol",
            efforts = frontierReasoning - "none",
            temperature = TemperatureSupport(
                1.0, 1.0, omittedProviderApis = setOf(ProviderApi.OPENAI_RESPONSES),
            ),
            input = 922_000,
            providerApis = setOf(ProviderApi.OPENAI_RESPONSES),
        ),
        realtime(
            id = "gpt-realtime",
            input = 27_904,
            output = 4_096,
            mimeTypes = openAiVisualMimeTypes,
        ),
        realtime(
            id = "gpt-realtime-whisper",
            input = 16_000,
            output = 2_000,
            mimeTypes = setOf("text/plain", "audio/pcm"),
        ),
        embedding(),
        claude(
            id = "claude-4.5-haiku",
            input = 136_000,
            output = 64_000,
            reasoning = tokenBudget(
                minimum = 1_024,
                maximum = 63_999,
                efforts = mapOf("off" to 0.0, "low" to 0.25, "mid" to 0.5, "high" to 1.0),
            ),
        ),
        claude(
            id = "claude-4.6-sonnet",
            input = 936_000,
            output = 64_000,
            reasoning = categorical(
                mapOf("off" to 0.0, "low" to 0.25, "medium" to 0.5, "high" to 0.75)
            ),
        ),
        claude(
            id = "claude-4.6-opus",
            input = 872_000,
            output = 128_000,
            reasoning = categorical(adaptiveReasoning),
        ),
        claude(
            id = "claude-4.7-opus",
            input = 1_000_000,
            output = 128_000,
            reasoning = categorical(adaptiveReasoning),
            omitTemperature = true,
        ),
        claude(
            id = "claude-4.8-opus",
            input = 1_000_000,
            output = 128_000,
            reasoning = categorical(adaptiveReasoning),
            omitTemperature = true,
        ),
        claude(
            id = "claude-fable-5",
            input = 1_000_000,
            output = 128_000,
            reasoning = categorical(adaptiveReasoning),
            omitTemperature = true,
        ),
        // Provider-verified post-baseline addition:
        // https://platform.claude.com/docs/en/about-claude/models/overview
        claude(
            id = "claude-fable-5-1",
            input = 1_000_000,
            output = 128_000,
            reasoning = categorical(
                mapOf(
                    "low" to 0.0,
                    "medium" to 0.25,
                    "high" to 0.5,
                    "xhigh" to 0.75,
                    "max" to 1.0,
                )
            ),
            omitTemperature = true,
        ),
        claude(
            id = "claude-sonnet-5",
            input = 1_000_000,
            output = 128_000,
            reasoning = categorical(frontierReasoningWithOff),
            omitTemperature = true,
        ),
        // Provider-verified post-baseline addition:
        // https://platform.claude.com/docs/en/about-claude/models/whats-new-opus-5
        claude(
            id = "claude-opus-5",
            input = 1_000_000,
            output = 128_000,
            reasoning = categorical(frontierReasoningWithOff),
            omitTemperature = true,
        ),
        // https://platform.claude.com/docs/en/models/opus-5-5/overview
        // https://platform.claude.com/docs/en/models/sonnet-5-5/overview
        // https://platform.claude.com/docs/en/build-with-claude/effort
        // Both models reject disabled thinking; Sonnet's between_tools mode is not an off effort.
        claude(
            id = "claude-opus-5-5",
            input = 1_000_000,
            output = 128_000,
            reasoning = categorical(frontierReasoning - "none"),
            omitTemperature = true,
        ),
        claude(
            id = "claude-sonnet-5-5",
            input = 1_000_000,
            output = 128_000,
            reasoning = categorical(frontierReasoning - "none"),
            omitTemperature = true,
        ),
        deepSeek(),
        // https://api-docs.deepseek.com/quick_start/pricing/
        // https://api-docs.deepseek.com/guides/thinking_mode/
        deepSeekV4(
            id = "deepseek-flash",
            aliases = setOf("deepseek-v4-flash", "deepseek-v4-flash-vision-exp"),
            mimeTypes = openAiVisualMimeTypes,
        ),
        deepSeekV4(id = "deepseek-v4-pro"),
        // Mantle-only routes; these semantic IDs deliberately omit AWS's publisher prefix.
        // https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-google-gemma-4-31b.html
        // https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-google-gemma-4-26b-a4b.html
        // https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-google-gemma-4-e2b.html
        gemma4("gemma-4-31b", input = 256_000),
        gemma4("gemma-4-26b-a4b", input = 256_000),
        gemma4("gemma-4-e2b", input = 128_000),
        novaEmbeddings(),
        gemini(
            id = "gemini-2.5-pro",
            input = 983_041,
            output = 65_535,
            reasoning = tokenBudget(
                minimum = 128,
                maximum = 32_768,
                efforts = mapOf("low" to 0.25, "mid" to 0.5, "max" to 1.0),
            ),
        ),
        gemini(
            id = "gemini-2.5-flash",
            input = 983_040,
            output = 65_536,
            reasoning = tokenBudget(
                minimum = 0,
                maximum = 24_576,
                efforts = mapOf("off" to 0.0, "low" to 0.25, "mid" to 0.5, "max" to 1.0),
            ),
        ),
        gemini(
            id = "gemini-3.1-flash-lite",
            input = 1_048_576,
            output = 65_535,
            reasoning = categorical(standardReasoning),
        ),
        gemini(
            id = "gemini-3.5-flash",
            input = 1_048_576,
            output = 65_535,
            reasoning = categorical(standardReasoning),
        ),
        // Provider-verified post-baseline addition:
        // https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite
        gemini(
            id = "gemini-3.5-flash-lite",
            input = 1_048_576,
            output = 65_536,
            reasoning = categorical(standardReasoning),
            omitTemperature = true,
            mimeTypes = googleMultimodalMimeTypes,
        ),
        // Provider-verified post-baseline addition:
        // https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/gemini/3-6-flash
        gemini(
            id = "gemini-3.6-flash",
            input = 1_048_576,
            output = 65_536,
            reasoning = categorical(standardReasoning),
            omitTemperature = true,
            mimeTypes = googleMultimodalMimeTypes,
        ),
        // Provider-verified post-baseline addition:
        // https://ai.google.dev/gemini-api/docs/models/gemini-3.7-flash
        gemini(
            id = "gemini-3.7-flash",
            input = 1_048_576,
            output = 65_536,
            reasoning = categorical(mapOf("low" to 0.0, "medium" to 0.5, "high" to 1.0)),
            omitTemperature = true,
            mimeTypes = googleMultimodalMimeTypes,
        ),
        // https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash
        gemini(
            id = "gemini-3.8-flash",
            input = 1_048_576,
            output = 65_536,
            reasoning = categorical(mapOf("low" to 0.0, "medium" to 0.5, "high" to 1.0)),
            omitTemperature = true,
            mimeTypes = googleMultimodalMimeTypes,
        ),
        gemini(
            id = "gemini-3.1-pro",
            input = 983_040,
            output = 65_536,
            reasoning = categorical(mapOf("low" to 0.0, "mid" to 0.5, "high" to 1.0)),
        ),
        gemini(
            id = "gemini-3-flash",
            input = 983_040,
            output = 65_536,
            reasoning = categorical(standardReasoning),
        ),
    )

    private val byIdentifier: Map<String, ModelCatalogueEntry> = buildMap {
        ModelCatalogue.entries.forEach { entry ->
            put(entry.id, entry)
            entry.aliases.forEach { alias -> put(alias, entry) }
        }
    }

    init {
        val issues = validate(entries)
        require(issues.isEmpty()) {
            issues.joinToString(prefix = "Invalid model catalogue: ") { "${it.modelId}: ${it.detail}" }
        }
    }

    /** Resolve a canonical semantic ID or an explicit alias. */
    public fun find(idOrAlias: String): ModelCatalogueEntry? = byIdentifier[idOrAlias]

    /**
     * Resolve a semantic profile for a supported provider API, including route-specific restrictions.
     *
     * Returns null for unknown models and unsupported or undeclared routes. The one-argument lookup retains
     * semantic capabilities; applications selecting a provider route should use this overload.
     */
    public fun find(idOrAlias: String, api: ProviderApi): ModelCatalogueEntry? {
        val model = find(idOrAlias) ?: return null
        if (model.compatibility(api) !is ModelProviderApiCompatibility.Supported) return null
        return when {
            model.id == "gpt-6-astra" && api == ProviderApi.BEDROCK_CONVERSE ->
                model.copy(structuredOutput = false, hostedExecution = false)

            api == ProviderApi.OPENAI_COMPATIBLE_RESPONSES ||
                api == ProviderApi.OPENAI_COMPATIBLE_CHAT_COMPLETIONS ->
                model.copy(hostedExecution = false)

            else -> model
        }
    }

    /** Validate catalogue invariants without performing provider I/O. */
    public fun validate(
        entries: List<ModelCatalogueEntry>,
    ): List<ModelCatalogueIssue> {
        val issues = mutableListOf<ModelCatalogueIssue>()
        val ids = mutableSetOf<String>()
        val aliases = mutableMapOf<String, String>()

        entries.forEach { entry ->
            if (!ids.add(entry.id)) {
                issues += entry.issue(ModelCatalogueIssueKind.DUPLICATE_MODEL_ID, "duplicate model id")
            }
            if (entry.maxInputTokens <= 0 || entry.maxOutputTokens < 0) {
                issues += entry.issue(ModelCatalogueIssueKind.INVALID_TOKEN_LIMIT, "invalid token limits")
            }
            if (entry.temperature.minimum > entry.temperature.maximum) {
                issues += entry.issue(
                    ModelCatalogueIssueKind.INVALID_TEMPERATURE,
                    "minimum temperature exceeds maximum"
                )
            }
            if (!entry.providerApis.containsAll(entry.temperature.omittedProviderApis)) {
                issues += entry.issue(
                    ModelCatalogueIssueKind.TEMPERATURE_PROVIDER_CONTRADICTION,
                    "temperature omission names an undeclared provider API"
                )
            }
            validateReasoning(entry, issues)
            validateProviderApis(entry, issues)

            entry.aliases.forEach { alias ->
                val previous = aliases.putIfAbsent(alias, entry.id)
                if (previous != null) {
                    issues += entry.issue(
                        ModelCatalogueIssueKind.DUPLICATE_ALIAS,
                        "alias '$alias' is already owned by '$previous'"
                    )
                }
            }
        }

        entries.forEach { entry ->
            entry.aliases.filter { it in ids }.forEach { alias ->
                issues += entry.issue(
                    ModelCatalogueIssueKind.MODEL_ID_ALIAS_COLLISION,
                    "alias '$alias' is also a canonical model id"
                )
            }
        }
        return issues
    }

    /** Deterministic representation used by the checked-in baseline and provider-verified fixture. */
    public fun normalisedSnapshot(): String = buildString {
        appendLine("# source-revision=$sourceRevision")
        appendLine("# models.py-sha256=$sourceModelsSha256")
        appendLine("# types.py-sha256=$sourceTypesSha256")
        appendLine("# config.py-sha256=$sourceConfigSha256")
        entries.sortedBy { it.id }.forEach { entry ->
            append(entry.id)
            append('|')
            append(entry.aliases.sorted().joinToString(","))
            append('|')
            append(entry.publisher)
            append('|')
            append(entry.kind)
            append('|')
            append(entry.maxInputTokens)
            append('|')
            append(entry.maxOutputTokens)
            append('|')
            append(entry.normalisedReasoning())
            append('|')
            append(entry.temperature.minimum)
            append("..")
            append(entry.temperature.maximum)
            append(';')
            append(entry.temperature.allowedReasoningEfforts?.sorted()?.joinToString(",").orEmpty())
            append(';')
            append(entry.temperature.omittedProviderApis.sortedBy { it.name }.joinToString(","))
            append('|')
            append(entry.supportedMimeTypes.sorted().joinToString(","))
            append('|')
            append(entry.structuredOutput)
            append('|')
            append(entry.hostedExecution)
            append('|')
            append(entry.providerApis.sortedBy { it.name }.joinToString(","))
            append('|')
            appendLine(
                entry.unsupportedProviderApis.toSortedMap(compareBy { it.name })
                    .entries.joinToString(",") { "${it.key}=${it.value}" }
            )
        }
    }.trimEnd()

    private fun validateReasoning(
        entry: ModelCatalogueEntry,
        issues: MutableList<ModelCatalogueIssue>,
    ) {
        val reasoning = entry.reasoning
        if (reasoning !is ReasoningSupport.Supported) {
            if (entry.temperature.allowedReasoningEfforts != null) {
                issues += entry.issue(
                    ModelCatalogueIssueKind.INVALID_REASONING,
                    "temperature restricts undeclared reasoning"
                )
            }
            return
        }
        if (reasoning.efforts.isEmpty()) {
            issues += entry.issue(ModelCatalogueIssueKind.INVALID_REASONING, "reasoning efforts are empty")
        }
        reasoning.efforts.forEach { (name, value) ->
            if (name.isBlank() || value !in 0.0..1.0) {
                issues += entry.issue(
                    ModelCatalogueIssueKind.INVALID_REASONING_EFFORT,
                    "invalid reasoning effort '$name'=$value"
                )
            }
        }
        if (reasoning.mode == ReasoningMode.TOKEN_BUDGET) {
            val minimum = reasoning.minBudgetTokens
            val maximum = reasoning.maxBudgetTokens
            if (minimum == null || maximum == null || minimum < 0 || maximum < minimum) {
                issues += entry.issue(
                    ModelCatalogueIssueKind.INVALID_REASONING,
                    "invalid token-budget reasoning bounds"
                )
            }
        } else if (reasoning.minBudgetTokens != null || reasoning.maxBudgetTokens != null) {
            issues += entry.issue(
                ModelCatalogueIssueKind.INVALID_REASONING,
                "categorical reasoning has token-budget bounds"
            )
        }
        val allowedEfforts = entry.temperature.allowedReasoningEfforts
        if (allowedEfforts != null && !reasoning.efforts.keys.containsAll(allowedEfforts)) {
            issues += entry.issue(
                ModelCatalogueIssueKind.INVALID_TEMPERATURE,
                "temperature names an undeclared reasoning effort"
            )
        }
    }

    private fun validateProviderApis(
        entry: ModelCatalogueEntry,
        issues: MutableList<ModelCatalogueIssue>,
    ) {
        val allowedApis = when (entry.kind) {
            ModelKind.TEXT -> textModelApis
            ModelKind.EMBEDDING -> embeddingModelApis
            ModelKind.REALTIME -> realtimeModelApis
        }
        if (entry.providerApis.isEmpty() || !allowedApis.containsAll(entry.providerApis)) {
            issues += entry.issue(
                ModelCatalogueIssueKind.PROVIDER_API_CONTRADICTION,
                "provider API does not match model kind"
            )
        }
        if (entry.providerApis.any { it in entry.unsupportedProviderApis }) {
            issues += entry.issue(
                ModelCatalogueIssueKind.PROVIDER_API_SUPPORT_CONTRADICTION,
                "provider API is both supported and unsupported"
            )
        }
        if (!allowedApis.containsAll(entry.unsupportedProviderApis.keys)) {
            issues += entry.issue(
                ModelCatalogueIssueKind.PROVIDER_API_CONTRADICTION,
                "unsupported provider API does not match model kind"
            )
        }
        if (
            entry.hostedExecution &&
            entry.providerApis.none {
                ProviderCapabilityMatrix.hostedExecution(it) is HostedExecutionCapability.Supported
            }
        ) {
            issues += entry.issue(
                ModelCatalogueIssueKind.EXECUTION_REQUIREMENT_CONTRADICTION,
                "hosted execution has no supported provider API"
            )
        }
        if (entry.kind != ModelKind.TEXT && (entry.structuredOutput || entry.hostedExecution)) {
            issues += entry.issue(
                ModelCatalogueIssueKind.PROVIDER_API_CONTRADICTION,
                "non-text model declares text generation capabilities"
            )
        }
    }

    private fun ModelCatalogueEntry.issue(
        kind: ModelCatalogueIssueKind,
        detail: String,
    ): ModelCatalogueIssue = ModelCatalogueIssue(kind, id, detail)

    private fun ModelCatalogueEntry.normalisedReasoning(): String = when (val support = reasoning) {
        ReasoningSupport.Unsupported -> "unsupported"

        is ReasoningSupport.Supported -> buildString {
            append(support.mode)
            append(':')
            append(support.efforts.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" })
            append(':')
            append(support.minBudgetTokens ?: "")
            append(':')
            append(support.maxBudgetTokens ?: "")
        }
    }
}

private val textModelApis: Set<ProviderApi> = setOf(
    ProviderApi.OPENAI_RESPONSES,
    ProviderApi.AZURE_RESPONSES,
    ProviderApi.OPENAI_COMPATIBLE_RESPONSES,
    ProviderApi.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
    ProviderApi.CODEX_RESPONSES,
    ProviderApi.VERTEX_GEMINI_GENERATE_CONTENT,
    ProviderApi.VERTEX_ANTHROPIC_MESSAGES,
    ProviderApi.BEDROCK_ANTHROPIC_MESSAGES,
    ProviderApi.BEDROCK_CONVERSE,
)
private val embeddingModelApis: Set<ProviderApi> =
    setOf(ProviderApi.OPENAI_EMBEDDINGS, ProviderApi.AZURE_EMBEDDINGS, ProviderApi.BEDROCK_EMBEDDINGS)
private val realtimeModelApis: Set<ProviderApi> =
    setOf(ProviderApi.OPENAI_REALTIME, ProviderApi.AZURE_REALTIME)
private val compatibleApis: Set<ProviderApi> =
    setOf(ProviderApi.OPENAI_COMPATIBLE_RESPONSES, ProviderApi.OPENAI_COMPATIBLE_CHAT_COMPLETIONS)
private val astraApis: Set<ProviderApi> =
    setOf(ProviderApi.OPENAI_RESPONSES, ProviderApi.BEDROCK_CONVERSE) + compatibleApis
private val openAiAzure: Set<ProviderApi> =
    setOf(ProviderApi.OPENAI_RESPONSES, ProviderApi.AZURE_RESPONSES)
private val openAiAzureCodex: Set<ProviderApi> = openAiAzure + ProviderApi.CODEX_RESPONSES
private val claudeApis: Set<ProviderApi> =
    setOf(
        ProviderApi.VERTEX_ANTHROPIC_MESSAGES,
        ProviderApi.BEDROCK_ANTHROPIC_MESSAGES,
        ProviderApi.BEDROCK_CONVERSE,
    )
private val openAiVisualMimeTypes: Set<String> =
    setOf("text/plain", "image/jpeg", "image/png", "image/gif", "image/webp")
private val multimodalMimeTypes: Set<String> = openAiVisualMimeTypes + "application/pdf"
private val googleImageMimeTypes: Set<String> = setOf(
    "image/png",
    "image/jpeg",
    "image/webp",
    "image/heic",
    "image/heif",
)
private val googleDocumentMimeTypes: Set<String> = setOf(
    "application/pdf",
    "text/plain",
)
private val googleAudioMimeTypes: Set<String> = setOf(
    "audio/x-aac",
    "audio/flac",
    "audio/m4a",
    "audio/mp3",
    "audio/mp4",
    "audio/mpga",
    "audio/mpeg",
    "audio/ogg",
    "audio/pcm",
    "audio/wav",
    "audio/webm",
)
private val googleVideoMimeTypes: Set<String> = setOf(
    "video/x-flv",
    "video/quicktime",
    "video/mpeg",
    "video/mpegs",
    "video/mpg",
    "video/mp4",
    "video/webm",
    "video/wmv",
    "video/3gpp",
)
private val googleMultimodalMimeTypes: Set<String> =
    googleImageMimeTypes + googleDocumentMimeTypes + googleAudioMimeTypes + googleVideoMimeTypes
private val standardReasoning: Map<String, Double> =
    mapOf("minimal" to 0.0, "low" to 0.25, "medium" to 0.5, "high" to 1.0)
private val extendedReasoning: Map<String, Double> =
    mapOf("none" to 0.0, "low" to 0.25, "medium" to 0.5, "high" to 0.9, "xhigh" to 1.0)
private val frontierReasoning: Map<String, Double> =
    mapOf("none" to 0.0, "low" to 0.2, "medium" to 0.4, "high" to 0.6, "xhigh" to 0.8, "max" to 1.0)
private val frontierReasoningWithOff: Map<String, Double> =
    mapOf("off" to 0.0, "low" to 0.2, "medium" to 0.4, "high" to 0.6, "xhigh" to 0.8, "max" to 1.0)
private val adaptiveReasoning: Map<String, Double> =
    mapOf("off" to 0.0, "low" to 0.25, "medium" to 0.5, "high" to 0.75, "max" to 1.0)
private val fixedOmittedTemperature: TemperatureSupport =
    TemperatureSupport(1.0, 1.0, omittedProviderApis = openAiAzure)
private val fixedAzureOmittedTemperature: TemperatureSupport =
    TemperatureSupport(1.0, 1.0, omittedProviderApis = setOf(ProviderApi.AZURE_RESPONSES))
private val conditionalTemperature: TemperatureSupport =
    TemperatureSupport(0.0, 2.0, allowedReasoningEfforts = setOf("none"))
private val conditionalTemperatureWithCodex: TemperatureSupport =
    TemperatureSupport(
        0.0,
        2.0,
        allowedReasoningEfforts = setOf("none"),
        omittedProviderApis = setOf(ProviderApi.CODEX_RESPONSES),
    )

private fun categorical(efforts: Map<String, Double>): ReasoningSupport.Supported =
    ReasoningSupport.Supported(ReasoningMode.CATEGORICAL, efforts)

private fun tokenBudget(
    minimum: Int,
    maximum: Int,
    efforts: Map<String, Double>,
): ReasoningSupport.Supported = ReasoningSupport.Supported(
    mode = ReasoningMode.TOKEN_BUDGET,
    efforts = efforts,
    minBudgetTokens = minimum,
    maxBudgetTokens = maximum,
)

private fun openAiText(
    id: String,
    input: Long,
    output: Long,
): ModelCatalogueEntry = ModelCatalogueEntry(
    id = id,
    publisher = ModelPublisher.OPENAI,
    kind = ModelKind.TEXT,
    providerApis = openAiAzure,
    reasoning = ReasoningSupport.Unsupported,
    maxInputTokens = input,
    maxOutputTokens = output,
    temperature = TemperatureSupport(0.0, 2.0),
    supportedMimeTypes = openAiVisualMimeTypes,
    structuredOutput = true,
    hostedExecution = true,
)

private fun openAiReasoning(
    id: String,
    efforts: Map<String, Double>,
    temperature: TemperatureSupport,
    input: Long = 272_000,
    providerApis: Set<ProviderApi> = openAiAzure,
): ModelCatalogueEntry = ModelCatalogueEntry(
    id = id,
    publisher = ModelPublisher.OPENAI,
    kind = ModelKind.TEXT,
    providerApis = providerApis,
    reasoning = categorical(efforts),
    maxInputTokens = input,
    maxOutputTokens = 128_000,
    temperature = temperature,
    supportedMimeTypes = openAiVisualMimeTypes,
    structuredOutput = true,
    hostedExecution = true,
)

private fun realtime(
    id: String,
    input: Long,
    output: Long,
    mimeTypes: Set<String>,
): ModelCatalogueEntry = ModelCatalogueEntry(
    id = id,
    publisher = ModelPublisher.OPENAI,
    kind = ModelKind.REALTIME,
    providerApis = setOf(ProviderApi.OPENAI_REALTIME, ProviderApi.AZURE_REALTIME),
    reasoning = ReasoningSupport.Unsupported,
    maxInputTokens = input,
    maxOutputTokens = output,
    temperature = TemperatureSupport(1.0, 1.0),
    supportedMimeTypes = mimeTypes,
    structuredOutput = false,
    hostedExecution = false,
)

private fun embedding(): ModelCatalogueEntry = ModelCatalogueEntry(
    id = "text-embedding-3-large",
    publisher = ModelPublisher.OPENAI,
    kind = ModelKind.EMBEDDING,
    providerApis = setOf(ProviderApi.OPENAI_EMBEDDINGS, ProviderApi.AZURE_EMBEDDINGS),
    reasoning = ReasoningSupport.Unsupported,
    maxInputTokens = 300_000,
    maxOutputTokens = 0,
    temperature = TemperatureSupport(0.0, 2.0),
    supportedMimeTypes = emptySet(),
    structuredOutput = false,
    hostedExecution = false,
)

private fun claude(
    id: String,
    input: Long,
    output: Long,
    reasoning: ReasoningSupport.Supported,
    omitTemperature: Boolean = false,
): ModelCatalogueEntry = ModelCatalogueEntry(
    id = id,
    publisher = ModelPublisher.ANTHROPIC,
    kind = ModelKind.TEXT,
    providerApis = claudeApis,
    reasoning = reasoning,
    maxInputTokens = input,
    maxOutputTokens = output,
    temperature = TemperatureSupport(
        minimum = 0.0,
        maximum = 1.0,
        omittedProviderApis = if (omitTemperature) claudeApis else emptySet(),
    ),
    supportedMimeTypes = multimodalMimeTypes,
    structuredOutput = true,
    hostedExecution = true,
)

private fun deepSeek(): ModelCatalogueEntry = ModelCatalogueEntry(
    id = "deepseek-3.2",
    publisher = ModelPublisher.DEEPSEEK,
    kind = ModelKind.TEXT,
    providerApis = setOf(
        ProviderApi.BEDROCK_CONVERSE,
        ProviderApi.VERTEX_GEMINI_GENERATE_CONTENT,
    ),
    unsupportedProviderApis = mapOf(
        ProviderApi.AZURE_RESPONSES to
            ModelProviderApiUnsupportedReason.MODEL_SUPPORT_NOT_ESTABLISHED
    ),
    reasoning = ReasoningSupport.Unsupported,
    maxInputTokens = 99_840,
    maxOutputTokens = 64_000,
    temperature = TemperatureSupport(0.0, 1.0),
    supportedMimeTypes = setOf("text/plain"),
    structuredOutput = true,
    hostedExecution = false,
)

private fun deepSeekV4(
    id: String,
    aliases: Set<String> = emptySet(),
    mimeTypes: Set<String> = setOf("text/plain"),
): ModelCatalogueEntry = ModelCatalogueEntry(
    id = id,
    aliases = aliases,
    publisher = ModelPublisher.DEEPSEEK,
    kind = ModelKind.TEXT,
    providerApis = compatibleApis,
    reasoning = categorical(mapOf("none" to 0.0, "low" to 0.25, "high" to 0.5, "max" to 1.0)),
    maxInputTokens = 1_000_000,
    maxOutputTokens = 384_000,
    temperature = TemperatureSupport(0.0, 2.0, allowedReasoningEfforts = setOf("none")),
    supportedMimeTypes = mimeTypes,
    structuredOutput = true,
    hostedExecution = false,
)

private fun gemma4(id: String, input: Long): ModelCatalogueEntry = ModelCatalogueEntry(
    id = id,
    publisher = ModelPublisher.GOOGLE,
    kind = ModelKind.TEXT,
    providerApis = compatibleApis,
    unsupportedProviderApis = mapOf(
        ProviderApi.BEDROCK_CONVERSE to ModelProviderApiUnsupportedReason.MODEL_API_NOT_SUPPORTED,
    ),
    // Mantle documents high effort to enable thinking. Avoid borrowing GPT reasoning levels.
    reasoning = categorical(mapOf("none" to 0.0, "high" to 1.0)),
    maxInputTokens = input,
    // AWS publishes a shared context window, but no independent output ceiling.
    maxOutputTokens = 0,
    temperature = TemperatureSupport(0.0, 2.0),
    supportedMimeTypes = openAiVisualMimeTypes,
    structuredOutput = false,
    hostedExecution = false,
)

// https://docs.aws.amazon.com/nova/latest/nova2-userguide/embeddings.html
private fun novaEmbeddings(): ModelCatalogueEntry = ModelCatalogueEntry(
    id = "nova-2-multimodal-embeddings",
    publisher = ModelPublisher.AMAZON,
    kind = ModelKind.EMBEDDING,
    providerApis = setOf(ProviderApi.BEDROCK_EMBEDDINGS),
    reasoning = ReasoningSupport.Unsupported,
    maxInputTokens = 8_192,
    maxOutputTokens = 0,
    temperature = TemperatureSupport(1.0, 1.0),
    // Kroog's current embedding client accepts text only.
    supportedMimeTypes = setOf("text/plain"),
    structuredOutput = false,
    hostedExecution = false,
)

private fun gemini(
    id: String,
    input: Long,
    output: Long,
    reasoning: ReasoningSupport.Supported,
    omitTemperature: Boolean = false,
    mimeTypes: Set<String> = multimodalMimeTypes,
): ModelCatalogueEntry = ModelCatalogueEntry(
    id = id,
    publisher = ModelPublisher.GOOGLE,
    kind = ModelKind.TEXT,
    providerApis = setOf(ProviderApi.VERTEX_GEMINI_GENERATE_CONTENT),
    reasoning = reasoning,
    maxInputTokens = input,
    maxOutputTokens = output,
    temperature = TemperatureSupport(
        minimum = 0.0,
        maximum = 2.0,
        omittedProviderApis = if (omitTemperature) {
            setOf(ProviderApi.VERTEX_GEMINI_GENERATE_CONTENT)
        } else {
            emptySet()
        },
    ),
    supportedMimeTypes = mimeTypes,
    structuredOutput = true,
    hostedExecution = true,
)
