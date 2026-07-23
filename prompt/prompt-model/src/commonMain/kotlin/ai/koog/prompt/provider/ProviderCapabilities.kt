package ai.koog.prompt.provider

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
    init {
        require((mode == HostedExecutionMode.INLINE_PROVIDER_TOOL) == (providerTool != null)) {
            "Inline provider execution must name exactly one provider tool"
        }
        require((mode == HostedExecutionMode.PROVIDER_MANAGED_SANDBOX) == (managedService != null)) {
            "Managed provider execution must name exactly one managed service"
        }
    }
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

/**
 * Authoritative execution capability matrix.
 *
 * The `when` deliberately has no `else`: adding a provider API requires a compile-time decision here.
 * Metadata marked [ProviderClientIntegration.REQUIRES_CLIENT_INTEGRATION] declares provider semantics only.
 */
public object ProviderCapabilityMatrix {
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
            service = "Vertex Agent Engine Code Execution"
        )

        ProviderApi.BEDROCK_ANTHROPIC_MESSAGES,
        ProviderApi.BEDROCK_CONVERSE,
        -> managedClaudeExecution(service = "Bedrock AgentCore Code Interpreter")

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

    private fun managedClaudeExecution(service: String): HostedExecutionCapability.Supported =
        HostedExecutionCapability.Supported(
            HostedExecutionConfiguration(
                mode = HostedExecutionMode.PROVIDER_MANAGED_SANDBOX,
                managedService = service,
                files = HostedExecutionFeatureSupport.UNSUPPORTED,
                callerAddressableContainer = false,
                streaming = HostedExecutionFeatureSupport.UNSUPPORTED,
                replay = HostedExecutionFeatureSupport.SUPPORTED,
                combinesWithCustomTools = HostedExecutionFeatureSupport.SUPPORTED,
                clientIntegration = ProviderClientIntegration.REQUIRES_CLIENT_INTEGRATION,
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
