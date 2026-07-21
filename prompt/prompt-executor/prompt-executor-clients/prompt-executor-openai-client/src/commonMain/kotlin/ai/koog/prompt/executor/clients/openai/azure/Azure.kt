package ai.koog.prompt.executor.clients.openai.azure

import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.openai.OpenAICredentialMechanism
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesCapability
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesDialect

/**
 * Creates an instance of [OpenAIClientSettings] for Azure OpenAI client configuration.
 *
 * @param resourceName The name of the Azure OpenAI resource.
 * @param deploymentName The name of the deployment within the Azure OpenAI resource.
 * @param version The version of the Azure OpenAI Service to use.
 * @param timeoutConfig Configuration for connection timeouts, including request, connect, and socket timeouts.
 */
@Suppress("FunctionName")
public fun AzureOpenAIClientSettings(
    resourceName: String,
    deploymentName: String,
    version: AzureOpenAIServiceVersion,
    timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig(),
): OpenAIClientSettings = AzureOpenAIClientSettings(
    endpoint = "https://$resourceName.openai.azure.com",
    deploymentName = deploymentName,
    apiVersion = version.value,
    timeoutConfig = timeoutConfig,
)

/**
 * Creates explicit Azure Responses settings without inferring behaviour from the endpoint hostname.
 *
 * @param endpoint Azure resource endpoint, such as `https://example.openai.azure.com`.
 * @param deploymentName Azure deployment selected for inference.
 * @param apiVersion Azure OpenAI API version sent as a query parameter.
 * @param credentialMechanism Authentication mechanism used by the factory-backed client.
 * @param responsesCapability Explicit Responses capability declaration.
 */
@Suppress("FunctionName", "LongParameterList")
public fun AzureOpenAIClientSettings(
    endpoint: String,
    deploymentName: String,
    apiVersion: String,
    timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig(),
    credentialMechanism: OpenAICredentialMechanism = OpenAICredentialMechanism.ApiKey,
    responsesCapability: OpenAIResponsesCapability = OpenAIResponsesCapability.Supported,
): OpenAIClientSettings {
    require(endpoint.isNotBlank()) { "Azure OpenAI endpoint must be non-blank" }
    require(deploymentName.isNotBlank()) { "Azure OpenAI deployment must be non-blank" }
    require(apiVersion.isNotBlank()) { "Azure OpenAI API version must be non-blank" }
    val resourceBaseUrl = "${endpoint.trimEnd('/')}/"
    return OpenAIClientSettings(
        baseUrl = resourceBaseUrl,
        timeoutConfig = timeoutConfig,
        chatCompletionsPath = "openai/deployments/$deploymentName/chat/completions",
        responsesAPIPath = azureResponsesPath(apiVersion),
        embeddingsPath = "openai/deployments/$deploymentName/embeddings",
        responsesDialect = OpenAIResponsesDialect.Azure,
        declaredResponsesCapability = responsesCapability,
        credentialMechanism = credentialMechanism,
        queryParameters = mapOf("api-version" to apiVersion),
        deployment = deploymentName,
        apiVersion = apiVersion,
    )
}

/**
 * Creates an instance of [OpenAIClientSettings] for Azure OpenAI client configuration.
 *
 * This function is a convenience method that allows you to specify the base URL directly,
 * along with the Azure OpenAI service version and connection timeout configuration.
 *
 * @param baseUrl The base URL for the Azure OpenAI service.
 * @param version The version of the Azure OpenAI Service to use.
 * @param timeoutConfig Configuration for connection timeouts, including request, connect, and socket timeouts.
 */
@Suppress("FunctionName")
public fun AzureOpenAIClientSettings(
    baseUrl: String,
    version: AzureOpenAIServiceVersion,
    timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig(),
): OpenAIClientSettings = OpenAIClientSettings(
    baseUrl = baseUrl,
    timeoutConfig = timeoutConfig,
    chatCompletionsPath = "chat/completions",
    responsesAPIPath = azureResponsesPath(version.value),
    embeddingsPath = "embeddings",
    responsesDialect = OpenAIResponsesDialect.Azure,
    declaredResponsesCapability = OpenAIResponsesCapability.Unsupported(
        "Azure Responses requires an explicit resource endpoint and deployment"
    ),
    credentialMechanism = OpenAICredentialMechanism.ApiKey,
    queryParameters = mapOf("api-version" to version.value),
    apiVersion = version.value,
)

private fun azureResponsesPath(apiVersion: String): String =
    if (apiVersion.matches(Regex("""\d{4}-\d{2}-\d{2}-preview""", RegexOption.IGNORE_CASE))) {
        "openai/responses"
    } else {
        "openai/v1/responses"
    }
