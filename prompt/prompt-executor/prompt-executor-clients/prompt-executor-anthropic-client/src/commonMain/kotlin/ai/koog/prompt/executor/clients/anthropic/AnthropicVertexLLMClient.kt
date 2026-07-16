package ai.koog.prompt.executor.clients.anthropic

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.llm.LLModel
import ai.koog.utils.time.KoogClock
import kotlin.jvm.JvmOverloads

private val vertexPathSegmentPattern = Regex("[A-Za-z0-9][A-Za-z0-9._@-]*")

/**
 * Settings for Anthropic Messages served through Vertex AI raw prediction endpoints.
 *
 * [modelVersionsMap] is explicit because the Vertex path requires a publisher model version rather than the
 * ordinary Anthropic API model name.
 */
public class AnthropicVertexClientSettings(
    public val projectId: String,
    public val location: String,
    public val modelVersionsMap: Map<LLModel, String>,
    public val anthropicVersion: String = "vertex-2023-10-16",
) {
    init {
        requireSafeVertexPathSegment("projectId", projectId)
        requireSafeVertexPathSegment("location", location)
        require(modelVersionsMap.isNotEmpty()) { "modelVersionsMap must not be empty" }
        modelVersionsMap.values.forEach { modelVersion ->
            requireSafeVertexPathSegment("model version", modelVersion)
        }
        require(anthropicVersion.isNotBlank()) { "anthropicVersion must not be blank" }
    }
}

/**
 * Anthropic Messages client for Vertex AI.
 *
 * [httpClient] must already provide Vertex authentication. This client adds no OAuth, API-key, or Anthropic
 * authentication headers.
 */
public class AnthropicVertexLLMClient @JvmOverloads constructor(
    settings: AnthropicVertexClientSettings,
    httpClient: KoogHttpClient,
    clock: KoogClock = KoogClock.System,
) : AnthropicLLMClient(
    httpClient = httpClient,
    clock = clock,
    requestDialect = AnthropicRequestDialect.Vertex(settings),
)

private fun requireSafeVertexPathSegment(name: String, value: String) {
    require(vertexPathSegmentPattern.matches(value)) {
        "Vertex Anthropic $name must be a safe path segment"
    }
}
