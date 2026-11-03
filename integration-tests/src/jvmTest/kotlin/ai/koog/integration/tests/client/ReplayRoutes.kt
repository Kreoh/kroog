package ai.koog.integration.tests.client

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.AnthropicVertexClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicVertexLLMClient
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicEffort
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinkingDisplay
import ai.koog.prompt.executor.clients.bedrock.BedrockAPIMethod
import ai.koog.prompt.executor.clients.bedrock.BedrockClientSettings
import ai.koog.prompt.executor.clients.bedrock.BedrockLLMClient
import ai.koog.prompt.executor.clients.bedrock.BedrockModelFamilies
import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import ai.koog.prompt.executor.clients.bedrock.converse.BedrockConverseParams
import ai.koog.prompt.executor.clients.bedrock.converse.BedrockReasoningEffort
import ai.koog.prompt.executor.clients.bedrock.converse.BedrockThinkingConfig
import ai.koog.prompt.executor.clients.bedrock.converse.BedrockThinkingDisplay
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingConfig
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingLevel
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.executor.clients.openai.models.ReasoningSummary
import ai.koog.prompt.executor.clients.retry.RetryConfig
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.toMessageResponse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ReplayRoute(
    val name: String,
    val model: LLModel,
    val params: LLMParams,
    private val client: Lazy<LLMClient>,
    private val permits: Semaphore = Semaphore(if (name.startsWith("tensorx-")) 1 else 2),
    private val reasoning: (Boolean) -> LLMParams? = { null },
) : AutoCloseable {
    fun supportsReasoning(enabled: Boolean): Boolean = reasoning(enabled) != null

    fun withReasoning(enabled: Boolean): ReplayRoute =
        ReplayRoute(name, model, requireNotNull(reasoning(enabled)), client, permits, reasoning)

    suspend fun execute(prompt: Prompt, tools: List<ToolDescriptor> = emptyList(), params: LLMParams = this.params): Message.Assistant =
        request { client.value.execute(prompt.withParams(params), model, tools) }

    suspend fun executeStreaming(prompt: Prompt, tools: List<ToolDescriptor> = emptyList()): Message.Assistant =
        request { client.value.executeStreaming(prompt.withParams(params), model, tools).toList().toMessageResponse() }

    private suspend fun request(action: suspend () -> Message.Assistant): Message.Assistant = permits.withPermit {
        // Bound time spent on the provider, including any retry, after acquiring this route's permit.
        val timeoutMs = if (name.startsWith("tensorx-")) 75_000L else 180_000L
        withTimeoutOrNull(timeoutMs) { action() } ?: throw TimeoutException("$name exceeded ${timeoutMs / 1000}s")
    }

    override fun close() {
        if (client.isInitialized()) client.value.close()
    }

    override fun toString(): String = name
}

private fun setting(name: String): String = System.getenv(name)?.takeIf(String::isNotBlank)
    ?: error("Set $name to run the selected replay route")

private fun rootUrl(value: String) = value.trimEnd('/').removeSuffix("/v1")

private fun vertexBase(location: String): String {
    require(location.matches(Regex("[a-z][a-z0-9-]{0,61}[a-z0-9]"))) { "Invalid Vertex location" }
    return when (location) {
        "global" -> "https://aiplatform.googleapis.com"
        "eu", "us" -> "https://aiplatform.$location.rep.googleapis.com"
        else -> "https://$location-aiplatform.googleapis.com"
    }
}

private fun vertexTransport(location: String, naming: JsonNamingStrategy? = null) =
    HttpClientFactoryResolver.resolve().create(
        clientName = "VertexReplayIntegrationTest",
        baseUrl = vertexBase(location),
        headers = mapOf("Authorization" to "Bearer ${setting("VERTEX_ACCESS_TOKEN")}"),
        json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            namingStrategy = naming
        },
    )

internal fun replayRoutes(): List<ReplayRoute> {
    val maxTokens = 2048
    val openai = ReplayRoute(
        "openai-responses", OpenAIModels.Chat.GPT6Luna,
        OpenAIResponsesParams(maxTokens = maxTokens, stateless = true),
        lazy {
            val base = rootUrl(System.getenv("OPENAI_BASE_URL") ?: "https://api.openai.com")
            OpenAILLMClient(setting("OPENAI_API_KEY"), OpenAIClientSettings(baseUrl = base))
        },
    ) { enabled ->
        OpenAIResponsesParams(maxTokens = maxTokens, stateless = true, reasoning = ReasoningConfig(
            if (enabled) ReasoningEffort.LOW else ReasoningEffort.NONE,
            if (enabled) ReasoningSummary.AUTO else null,
        ))
    }
    val gemini = ReplayRoute(
        "vertex-gemini", GoogleModels.Gemini3_8Flash, GoogleParams(maxTokens = maxTokens),
        lazy {
            val location = System.getenv("VERTEX_GEMINI_LOCATION") ?: "eu"
            GoogleLLMClient(
                settings = GoogleClientSettings(
                    baseUrl = vertexBase(location),
                    defaultPath = "v1/projects/${setting("VERTEX_PROJECT_ID")}/locations/$location/publishers/google/models",
                ),
                httpClient = vertexTransport(location),
            )
        },
    ) { enabled ->
        if (enabled) GoogleParams(maxTokens = maxTokens, thinkingConfig = GoogleThinkingConfig(
            includeThoughts = true, thinkingLevel = GoogleThinkingLevel.LOW,
        )) else null
    }
    val tensorModels = mapOf(
        "glm" to "z-ai/glm-5.3-flash",
        "kimi" to "moonshotai/kimi-k3",
        "qwen" to "qwen/qwen3.8-2.4t-a95b",
        "deepseek" to "deepseek/deepseek-v4.1-flash-1017",
    )
    return listOf(openai, gemini) + claudeReplayRoutes() + tensorModels.map { (slug, id) ->
        val capabilities = listOf(
            LLMCapability.Tools, LLMCapability.Completion, LLMCapability.Thinking, LLMCapability.OpenAIEndpoint.Completions,
        )
        val model = LLModel(
            LLMProvider.OpenAI, id,
            if (slug == "deepseek") capabilities + LLMCapability.Vision.Image else capabilities,
        )
        fun tensorParams(enabled: Boolean, effort: ReasoningEffort? = null) =
            OpenAIChatParams(maxTokens = maxTokens, reasoningEffort = effort).withChatTemplateKwargs(
                buildJsonObject {
                    when (slug) {
                        "glm" -> put("clear_thinking", true)
                        "qwen" -> {
                            put("enable_thinking", enabled)
                            put("preserve_thinking", true)
                        }
                        "deepseek" -> put("thinking", enabled)
                    }
                },
            )
        ReplayRoute("tensorx-$slug", model, tensorParams(true), lazy {
            val settings = OpenAIClientSettings(baseUrl = rootUrl(setting("TENSORX_BASE_URL")))
            RetryingLLMClient(OpenAILLMClient(setting("TENSORX_API_KEY"), settings), RetryConfig(maxAttempts = 2))
                .withObserver { println("RETRY tensorx-$slug ${it.reason}") }
        }) { enabled ->
            when {
                slug == "glm" && enabled -> tensorParams(true, ReasoningEffort.HIGH)
                slug == "qwen" && enabled -> tensorParams(true)
                slug == "deepseek" -> tensorParams(enabled)
                else -> null
            }
        }
    }
}

internal fun claudeBedrockReplaySettings() = BedrockClientSettings(
    region = System.getenv("AWS_REGION") ?: "us-west-2",
    apiMethod = BedrockAPIMethod.Converse,
    maxRetries = 0,
    fallbackModelFamily = BedrockModelFamilies.AnthropicClaude,
)

internal fun claudeReplayRoutes(opus: Boolean = false): List<ReplayRoute> {
    val maxTokens = 2048
    val claude = if (opus) AnthropicModels.Opus_5_5 else AnthropicModels.Sonnet_5_5
    val slug = if (opus) "opus" else "sonnet"
    val betweenTools = mapOf("thinking" to buildJsonObject { put("type", "between_tools") })
    val vertex = ReplayRoute("vertex-$slug", claude, AnthropicParams(maxTokens = maxTokens), lazy {
        val location = System.getenv("VERTEX_ANTHROPIC_LOCATION") ?: "global"
        AnthropicVertexLLMClient(
            settings = AnthropicVertexClientSettings(
                projectId = setting("VERTEX_PROJECT_ID"), location = location, modelVersionsMap = mapOf(claude to claude.id),
            ),
            httpClient = vertexTransport(location, JsonNamingStrategy.SnakeCase),
        )
    }) { enabled ->
        when {
            enabled -> AnthropicParams(maxTokens = maxTokens, thinking =
                AnthropicThinking.Adaptive(AnthropicThinkingDisplay.SUMMARIZED, AnthropicEffort.HIGH))
            !opus -> AnthropicParams(maxTokens = maxTokens, additionalProperties = betweenTools)
            else -> null
        }
    }
    val bedrockParams = BedrockConverseParams(maxTokens = maxTokens)
    val profile = if (opus) BedrockModels.AnthropicClaude55Opus else BedrockModels.AnthropicClaude55Sonnet
    val configuredId = System.getenv(if (opus) "BEDROCK_OPUS_MODEL_ID" else "BEDROCK_SONNET_MODEL_ID")
    val bedrock = ReplayRoute("bedrock-$slug", profile.copy(id = configuredId ?: profile.id), bedrockParams, lazy {
        BedrockLLMClient(
            identityProvider = StaticCredentialsProvider {
                accessKeyId = setting("AWS_ACCESS_KEY_ID")
                secretAccessKey = setting("AWS_SECRET_ACCESS_KEY")
                sessionToken = System.getenv("AWS_SESSION_TOKEN")
            },
            settings = claudeBedrockReplaySettings(),
        )
    }) { enabled ->
        when {
            enabled -> bedrockParams.withThinking(BedrockThinkingConfig.Adaptive(
                BedrockReasoningEffort.HIGH, BedrockThinkingDisplay.SUMMARIZED,
            ))
            !opus -> BedrockConverseParams(maxTokens = maxTokens, additionalProperties = betweenTools)
            else -> null
        }
    }
    return listOf(vertex, bedrock)
}
