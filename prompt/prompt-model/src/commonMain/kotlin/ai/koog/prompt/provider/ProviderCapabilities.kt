package ai.koog.prompt.provider

import ai.koog.prompt.models.ModelCatalogue
import ai.koog.prompt.models.ModelProviderApiCompatibility
import kotlinx.serialization.Serializable

/**
 * Closed set of provider wire APIs understood by the Kroog model catalogue.
 *
 * A member denotes a wire contract, not a credential, deployment, or client instance.
 */
public enum class ProviderApi {
    OPENAI_RESPONSES,
    AZURE_RESPONSES,
    OPENAI_COMPATIBLE_RESPONSES,
    OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
    CODEX_RESPONSES,
    VERTEX_GEMINI_GENERATE_CONTENT,
    VERTEX_ANTHROPIC_MESSAGES,
    BEDROCK_ANTHROPIC_MESSAGES,
    BEDROCK_CONVERSE,
    OPENAI_EMBEDDINGS,
    AZURE_EMBEDDINGS,
    OPENAI_REALTIME,
    AZURE_REALTIME,
}

/** How provider-side code is executed. */
public enum class HostedExecutionMode {
    INLINE_PROVIDER_TOOL,
    PROVIDER_MANAGED_SANDBOX,
}

/** Provider service used when hosted execution is presented by the client as an ordinary custom tool. */
@Serializable
public enum class ManagedExecutionServiceKind {
    VERTEX_AGENT_ENGINE,
    BEDROCK_AGENT_CORE,
}

/** Why a provider API cannot execute hosted code through this contract. */
public enum class HostedExecutionUnsupportedReason {
    API_DOES_NOT_EXPOSE_HOSTED_EXECUTION,
    CAPABILITY_REQUIRES_EXPLICIT_COMPATIBLE_ENDPOINT_DECLARATION,
    CODEX_EXECUTION_IS_OUT_OF_SCOPE,
    MODEL_API_IS_NOT_AN_EXECUTION_API,
}

/** Whether the current Kroog clients implement the declared provider capability. */
public enum class ProviderClientIntegration {
    IMPLEMENTED,
    REQUIRES_CLIENT_INTEGRATION,
}

/** Whether a hosted-execution feature is available on a provider API. */
public enum class HostedExecutionFeatureSupport {
    SUPPORTED,
    UNSUPPORTED,
}

/**
 * Exact restrictions for a supported hosted-execution API.
 *
 * [managedService] is present only when execution occurs through a separate provider-managed service.
 * [callerAddressableContainer] is false when callers must not persist or supply a provider container reference.
 */
public data class HostedExecutionConfiguration(
    val mode: HostedExecutionMode,
    val providerTool: String? = null,
    val managedService: String? = null,
    val files: HostedExecutionFeatureSupport,
    val callerAddressableContainer: Boolean,
    val streaming: HostedExecutionFeatureSupport,
    val replay: HostedExecutionFeatureSupport,
    val combinesWithCustomTools: HostedExecutionFeatureSupport,
    val clientIntegration: ProviderClientIntegration,
) {
    /** Typed managed-service identity derived from the legacy serialised service name. */
    public val managedServiceKind: ManagedExecutionServiceKind?
        get() = managedExecutionServiceKind(managedService)

    init {
        require((mode == HostedExecutionMode.INLINE_PROVIDER_TOOL) == (providerTool != null)) {
            "Inline provider execution must name exactly one provider tool"
        }
        require((mode == HostedExecutionMode.PROVIDER_MANAGED_SANDBOX) == (managedService != null)) {
            "Managed provider execution must name exactly one managed service"
        }
    }
}

/** Stable legacy display used by the original string-based capability API. */
public val ManagedExecutionServiceKind.legacyName: String
    get() = when (this) {
        ManagedExecutionServiceKind.VERTEX_AGENT_ENGINE -> "Vertex Agent Engine Code Execution"
        ManagedExecutionServiceKind.BEDROCK_AGENT_CORE -> "Bedrock AgentCore Code Interpreter"
    }

private fun managedExecutionServiceKind(legacyName: String?): ManagedExecutionServiceKind? = when (legacyName) {
    ManagedExecutionServiceKind.VERTEX_AGENT_ENGINE.legacyName -> ManagedExecutionServiceKind.VERTEX_AGENT_ENGINE
    ManagedExecutionServiceKind.BEDROCK_AGENT_CORE.legacyName -> ManagedExecutionServiceKind.BEDROCK_AGENT_CORE
    else -> null
}

/** Exhaustive hosted-execution outcome for a [ProviderApi]. */
public sealed interface HostedExecutionCapability {
    public data class Supported(
        val configuration: HostedExecutionConfiguration,
    ) : HostedExecutionCapability

    public data class Unsupported(
        val reason: HostedExecutionUnsupportedReason,
    ) : HostedExecutionCapability
}

/** Why a provider-and-model request was rejected before provider work began. */
@Serializable
public enum class HostedExecutionAcceptanceUnsupportedReason {
    UNKNOWN_MODEL,
    MODEL_PROVIDER_MISMATCH,
    MODEL_DOES_NOT_SUPPORT_HOSTED_EXECUTION,
    PROVIDER_API_UNSUPPORTED,
}

/**
 * Closed execution selection for one exact provider API and semantic model.
 *
 * Native execution is replayed on the provider wire. Client-managed execution remains an ordinary custom-tool
 * transcript and uses [serviceKind] only to select an injected service implementation.
 */
@Serializable
public sealed interface HostedExecutionAcceptance {
    @Serializable
    public data class NativeInline(
        val providerTool: String,
    ) : HostedExecutionAcceptance

    @Serializable
    public data class ClientManaged(
        val serviceKind: ManagedExecutionServiceKind,
    ) : HostedExecutionAcceptance

    @Serializable
    public data class Unsupported(
        val reason: HostedExecutionAcceptanceUnsupportedReason,
        val capabilityReason: HostedExecutionUnsupportedReason? = null,
    ) : HostedExecutionAcceptance
}

/**
 * Authoritative execution capability matrix.
 *
 * The `when` deliberately has no `else`: adding a provider API requires a compile-time decision here.
 * Metadata marked [ProviderClientIntegration.REQUIRES_CLIENT_INTEGRATION] declares provider semantics only.
 */
public object ProviderCapabilityMatrix {
    /**
     * Select execution only after validating that [modelId] belongs on [api].
     *
     * This method is side-effect free and is intended to run before clients create requests or perform network work.
     */
    public fun acceptHostedExecution(
        api: ProviderApi,
        modelId: String,
    ): HostedExecutionAcceptance {
        val model = ModelCatalogue.find(modelId)
            ?: return HostedExecutionAcceptance.Unsupported(
                HostedExecutionAcceptanceUnsupportedReason.UNKNOWN_MODEL
            )
        if (model.compatibility(api) !is ModelProviderApiCompatibility.Supported) {
            return HostedExecutionAcceptance.Unsupported(
                HostedExecutionAcceptanceUnsupportedReason.MODEL_PROVIDER_MISMATCH
            )
        }
        if (!model.hostedExecution) {
            return HostedExecutionAcceptance.Unsupported(
                HostedExecutionAcceptanceUnsupportedReason.MODEL_DOES_NOT_SUPPORT_HOSTED_EXECUTION
            )
        }

        return when (val capability = hostedExecution(api)) {
            is HostedExecutionCapability.Unsupported -> HostedExecutionAcceptance.Unsupported(
                reason = HostedExecutionAcceptanceUnsupportedReason.PROVIDER_API_UNSUPPORTED,
                capabilityReason = capability.reason,
            )

            is HostedExecutionCapability.Supported -> when (capability.configuration.mode) {
                HostedExecutionMode.INLINE_PROVIDER_TOOL -> HostedExecutionAcceptance.NativeInline(
                    providerTool = requireNotNull(capability.configuration.providerTool)
                )

                HostedExecutionMode.PROVIDER_MANAGED_SANDBOX -> HostedExecutionAcceptance.ClientManaged(
                    serviceKind = requireNotNull(capability.configuration.managedServiceKind)
                )
            }
        }
    }

    public fun hostedExecution(api: ProviderApi): HostedExecutionCapability = when (api) {
        ProviderApi.OPENAI_RESPONSES -> inlineCodeInterpreter()

        ProviderApi.AZURE_RESPONSES -> inlineCodeInterpreter()

        ProviderApi.VERTEX_GEMINI_GENERATE_CONTENT -> HostedExecutionCapability.Supported(
            HostedExecutionConfiguration(
                mode = HostedExecutionMode.INLINE_PROVIDER_TOOL,
                providerTool = "code_execution",
                files = HostedExecutionFeatureSupport.UNSUPPORTED,
                callerAddressableContainer = false,
                streaming = HostedExecutionFeatureSupport.SUPPORTED,
                replay = HostedExecutionFeatureSupport.SUPPORTED,
                combinesWithCustomTools = HostedExecutionFeatureSupport.SUPPORTED,
                clientIntegration = ProviderClientIntegration.IMPLEMENTED,
            )
        )

        ProviderApi.VERTEX_ANTHROPIC_MESSAGES -> managedClaudeExecution(
            serviceKind = ManagedExecutionServiceKind.VERTEX_AGENT_ENGINE,
            providerStreaming = HostedExecutionFeatureSupport.UNSUPPORTED,
            clientIntegration = ProviderClientIntegration.IMPLEMENTED,
        )

        ProviderApi.BEDROCK_ANTHROPIC_MESSAGES,
        ProviderApi.BEDROCK_CONVERSE,
        -> managedClaudeExecution(
            serviceKind = ManagedExecutionServiceKind.BEDROCK_AGENT_CORE,
            providerStreaming = HostedExecutionFeatureSupport.SUPPORTED,
            clientIntegration = ProviderClientIntegration.IMPLEMENTED,
        )

        ProviderApi.OPENAI_COMPATIBLE_RESPONSES -> HostedExecutionCapability.Unsupported(
            HostedExecutionUnsupportedReason.CAPABILITY_REQUIRES_EXPLICIT_COMPATIBLE_ENDPOINT_DECLARATION
        )

        ProviderApi.OPENAI_COMPATIBLE_CHAT_COMPLETIONS -> HostedExecutionCapability.Unsupported(
            HostedExecutionUnsupportedReason.API_DOES_NOT_EXPOSE_HOSTED_EXECUTION
        )

        ProviderApi.CODEX_RESPONSES -> HostedExecutionCapability.Unsupported(
            HostedExecutionUnsupportedReason.CODEX_EXECUTION_IS_OUT_OF_SCOPE
        )

        ProviderApi.OPENAI_EMBEDDINGS,
        ProviderApi.AZURE_EMBEDDINGS,
        ProviderApi.OPENAI_REALTIME,
        ProviderApi.AZURE_REALTIME,
        -> HostedExecutionCapability.Unsupported(
            HostedExecutionUnsupportedReason.MODEL_API_IS_NOT_AN_EXECUTION_API
        )
    }

    private fun inlineCodeInterpreter(): HostedExecutionCapability.Supported =
        HostedExecutionCapability.Supported(
            HostedExecutionConfiguration(
                mode = HostedExecutionMode.INLINE_PROVIDER_TOOL,
                providerTool = "code_interpreter",
                files = HostedExecutionFeatureSupport.SUPPORTED,
                callerAddressableContainer = true,
                streaming = HostedExecutionFeatureSupport.SUPPORTED,
                replay = HostedExecutionFeatureSupport.SUPPORTED,
                combinesWithCustomTools = HostedExecutionFeatureSupport.SUPPORTED,
                clientIntegration = ProviderClientIntegration.IMPLEMENTED,
            )
        )

    private fun managedClaudeExecution(
        serviceKind: ManagedExecutionServiceKind,
        providerStreaming: HostedExecutionFeatureSupport,
        clientIntegration: ProviderClientIntegration = ProviderClientIntegration.REQUIRES_CLIENT_INTEGRATION,
    ): HostedExecutionCapability.Supported =
        HostedExecutionCapability.Supported(
            HostedExecutionConfiguration(
                mode = HostedExecutionMode.PROVIDER_MANAGED_SANDBOX,
                managedService = serviceKind.legacyName,
                files = HostedExecutionFeatureSupport.SUPPORTED,
                callerAddressableContainer = false,
                streaming = providerStreaming,
                replay = HostedExecutionFeatureSupport.SUPPORTED,
                combinesWithCustomTools = HostedExecutionFeatureSupport.SUPPORTED,
                clientIntegration = clientIntegration,
            )
        )
}

/** Typed rejection for an OpenAI-compatible endpoint with no declared transport. */
public enum class CompatibleTransportRejectionReason {
    NO_COMPATIBLE_TRANSPORT_DECLARED,
}

/** Deterministic transport selection made before inference begins. */
public sealed interface CompatibleTransportSelection {
    public data class Selected(val api: ProviderApi) : CompatibleTransportSelection

    public data class Rejected(
        val reason: CompatibleTransportRejectionReason,
    ) : CompatibleTransportSelection
}

/**
 * Declared transports for an OpenAI-compatible endpoint.
 *
 * Responses is preferred whenever declared. Chat Completions is selected only for a Chat-only declaration.
 * Selection is final for the request and does not authorise fallback after an inference failure.
 */
public data class CompatibleTransportSupport(
    val responses: Boolean,
    val chatCompletions: Boolean,
) {
    public fun select(): CompatibleTransportSelection = when {
        responses -> CompatibleTransportSelection.Selected(ProviderApi.OPENAI_COMPATIBLE_RESPONSES)

        chatCompletions -> CompatibleTransportSelection.Selected(
            ProviderApi.OPENAI_COMPATIBLE_CHAT_COMPLETIONS
        )

        else -> CompatibleTransportSelection.Rejected(
            CompatibleTransportRejectionReason.NO_COMPATIBLE_TRANSPORT_DECLARED
        )
    }
}
