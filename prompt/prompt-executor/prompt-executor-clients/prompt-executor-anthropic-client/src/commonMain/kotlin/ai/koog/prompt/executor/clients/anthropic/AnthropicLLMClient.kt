package ai.koog.prompt.executor.clients.anthropic

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.cache.PromptCachePolicy
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicContent
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicEffort
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicMessage
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicMessageRequest
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicMessageRequestSerializer
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicModelsResponse
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicOutputConfig
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicOutputFormat
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicResponse
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicStreamDeltaContentType
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicStreamEventType
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicStreamResponse
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicTool
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicToolChoice
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicToolSchema
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicUsage
import ai.koog.prompt.executor.clients.anthropic.models.CacheTtl
import ai.koog.prompt.executor.clients.anthropic.models.DocumentSource
import ai.koog.prompt.executor.clients.anthropic.models.ImageSource
import ai.koog.prompt.executor.clients.anthropic.models.SystemAnthropicMessage
import ai.koog.prompt.executor.clients.anthropic.structure.AnthropicBasicJsonSchemaGenerator
import ai.koog.prompt.executor.clients.anthropic.structure.AnthropicStandardJsonSchemaGenerator
import ai.koog.prompt.executor.clients.modelsById
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.CacheControl
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.PromptCacheControl
import ai.koog.prompt.message.PromptCacheTtl
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.message.isClientManagedExecutionPresentation
import ai.koog.prompt.message.require
import ai.koog.prompt.message.validateClientManagedExecutionPresentation
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.buildStreamFrameFlow
import ai.koog.prompt.streaming.requireEndFrame
import ai.koog.utils.time.KoogClock
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.jvm.JvmOverloads
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicCacheControl as AnthropicCacheControlBlock

/**
 * Represents the settings for configuring an Anthropic client, including model mapping, base URL, and API version.
 *
 * @property modelVersionsMap Maps specific `LLModel` instances to their corresponding model version strings.
 * This determines which Anthropic model versions are used for operations.
 * @property baseUrl The base URL for accessing the Anthropic API. Defaults to "https://api.anthropic.com".
 * @property apiVersion The version of the Anthropic API to be used. Defaults to "2023-06-01".
 */
public class AnthropicClientSettings(
    public val modelVersionsMap: Map<LLModel, String> = DEFAULT_ANTHROPIC_MODEL_VERSIONS_MAP,
    public val baseUrl: String = "https://api.anthropic.com",
    public val apiVersion: String = "2023-06-01",
    public val messagesPath: String = "v1/messages",
    public val modelsPath: String = "v1/models",
    public val timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig()
)

internal sealed interface AnthropicRequestDialect {
    val clientName: String
    val modelVersionsMap: Map<LLModel, String>

    fun requestPath(modelVersion: String, stream: Boolean): String

    fun transformRequestBody(body: JsonObject): JsonObject

    class Direct(private val settings: AnthropicClientSettings) : AnthropicRequestDialect {
        override val clientName: String = "AnthropicLLMClient"
        override val modelVersionsMap: Map<LLModel, String> = settings.modelVersionsMap

        override fun requestPath(modelVersion: String, stream: Boolean): String = settings.messagesPath

        override fun transformRequestBody(body: JsonObject): JsonObject = body
    }

    class Vertex(private val settings: AnthropicVertexClientSettings) : AnthropicRequestDialect {
        override val clientName: String = "AnthropicVertexLLMClient"
        override val modelVersionsMap: Map<LLModel, String> = settings.modelVersionsMap

        override fun requestPath(modelVersion: String, stream: Boolean): String =
            "v1/projects/${settings.projectId}/locations/${settings.location}/publishers/anthropic/models/" +
                "$modelVersion:${if (stream) "streamRawPredict" else "rawPredict"}"

        override fun transformRequestBody(body: JsonObject): JsonObject =
            buildJsonObject {
                body.entries
                    .filterNot { (key, _) -> key == "model" || key == "anthropic_version" }
                    .forEach { (key, value) -> put(key, value) }
                put("anthropic_version", settings.anthropicVersion)
            }
    }
}

/**
 * A client implementation for interacting with Anthropic's API in a suspendable and direct manner.
 *
 * This class supports functionalities for executing text prompts and streaming interactions with the Anthropic API.
 * It leverages Kotlin Coroutines to handle asynchronous operations and provides full support for configuring HTTP
 * requests, including timeout handling and JSON serialization.
 *
 * @param settings Configurable settings for the Anthropic client, which include the base URL and other options.
 * @param httpClient A preconfigured Koog HTTP client used for API calls. Must have authentication and other
 *   request defaults already embedded. To create a client with standard defaults, use the secondary
 *   constructor that accepts an API key and a [KoogHttpClient.Factory].
 * @param clock Clock instance used for tracking response metadata timestamps.
 */
public open class AnthropicLLMClient @JvmOverloads constructor(
    private val settings: AnthropicClientSettings = AnthropicClientSettings(),
    protected val httpClient: KoogHttpClient,
    private val clock: KoogClock = KoogClock.System
) : LLMClient() {

    private var requestDialect: AnthropicRequestDialect = AnthropicRequestDialect.Direct(settings)

    internal constructor(
        httpClient: KoogHttpClient,
        clock: KoogClock,
        requestDialect: AnthropicRequestDialect,
    ) : this(
        settings = AnthropicClientSettings(modelVersionsMap = requestDialect.modelVersionsMap),
        httpClient = httpClient,
        clock = clock,
    ) {
        this.requestDialect = requestDialect
    }

    private companion object {
        private const val ANTHROPIC_CLIENT_NAME = "AnthropicLLMClient"

        private val logger = KotlinLogging.logger { }
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true // Ensure default values are included in serialization
            explicitNulls = false
            namingStrategy = JsonNamingStrategy.SnakeCase
        }
    }

    /**
     * Secondary constructor for creating an Anthropic client from an HTTP client factory.
     */
    @JvmOverloads
    public constructor(
        apiKey: String,
        settings: AnthropicClientSettings = AnthropicClientSettings(),
        httpClientFactory: KoogHttpClient.Factory,
        clock: KoogClock = KoogClock.System
    ) : this(
        settings = settings,
        httpClient = httpClientFactory.create(
            clientName = ANTHROPIC_CLIENT_NAME,
            baseUrl = settings.baseUrl,
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to settings.apiVersion
            ),
            queryParameters = emptyMap(),
            requestTimeoutMillis = settings.timeoutConfig.requestTimeoutMillis,
            connectTimeoutMillis = settings.timeoutConfig.connectTimeoutMillis,
            socketTimeoutMillis = settings.timeoutConfig.socketTimeoutMillis,
            json = json,
        ),
        clock = clock
    )

    /**
     * Provides the specific Large Language Model (LLM) provider used by the client.
     *
     * This method returns the LLM provider that the client is configured to use,
     * allowing identification and configuration of provider-specific features.
     *
     * @return The LLM provider associated with this client, specifically `LLMProvider.Anthropic`.
     */
    override val clientName: String
        get() = requestDialect.clientName

    override fun llmProvider(): LLMProvider = LLMProvider.Anthropic

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        logger.debug { "Executing prompt: $prompt with tools: $tools and model: $model" }
        require(model.supports(LLMCapability.Completion)) {
            "Model ${model.id} does not support chat completions"
        }
        require(model.supports(LLMCapability.Tools)) {
            "Model ${model.id} does not support tools"
        }

        val request = createAnthropicRequest(prompt, tools, model, false)

        return try {
            httpClient.post(
                path = requestDialect.requestPath(modelVersion(model), stream = false),
                requestBody = request,
                requestBodyType = String::class,
                responseType = String::class,
            ).let(::decodeAnthropicResponse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LLMClientException(
                clientName = clientName,
                message = e.message,
                cause = e
            )
        }.let(::processAnthropicResponse)
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> {
        logger.debug { "Executing streaming prompt: $prompt with model: $model with tools: ${tools.map { it.name }}" }
        require(model.supports(LLMCapability.Completion)) {
            "Model ${model.id} does not support chat completions"
        }

        val request = createAnthropicRequest(prompt, tools, model, true)
        return buildStreamFrameFlow {
            var inputTokens: Int? = null
            var outputTokens: Int? = null
            val activeBlockTypes = mutableMapOf<Int, String>()
            val reasoningTextByIndex = mutableMapOf<Int, String>()
            val reasoningSignatureByIndex = mutableMapOf<Int, String>()

            fun updateUsage(usage: AnthropicUsage) {
                inputTokens = usage.inputTokens ?: inputTokens
                outputTokens = usage.outputTokens ?: outputTokens
            }

            fun getMetaInfo(): ResponseMetaInfo = ResponseMetaInfo.create(
                clock = clock,
                totalTokensCount = inputTokens?.plus(outputTokens ?: 0) ?: outputTokens,
                inputTokensCount = inputTokens,
                outputTokensCount = outputTokens,
            )

            try {
                httpClient.sse(
                    path = requestDialect.requestPath(modelVersion(model), stream = true),
                    requestBody = request,
                    requestBodyType = String::class,
                    decodeStreamingResponse = ::decodeAnthropicStreamResponse,
                    processStreamingChunk = { it }
                ).collect { response ->
                    when (response.type) {
                        AnthropicStreamEventType.MESSAGE_START.value -> {
                            response.message?.usage?.let(::updateUsage)
                        }

                        AnthropicStreamEventType.CONTENT_BLOCK_START.value -> {
                            val index = response.index
                                ?: throw LLMClientException(clientName, "Content block index is missing")
                            when (val contentBlock = response.contentBlock) {
                                is AnthropicContent.Text -> {
                                    requireUnsupportedCitationsAbsent(contentBlock)
                                    activeBlockTypes[index] = "text"
                                    emitTextDelta(
                                        text = contentBlock.text,
                                        index = index,
                                    )
                                }

                                is AnthropicContent.ToolUse -> {
                                    activeBlockTypes[index] = "tool_use"
                                    emitToolCallDelta(
                                        id = contentBlock.id,
                                        name = contentBlock.name,
                                        index = index,
                                    )
                                }

                                is AnthropicContent.Thinking -> {
                                    activeBlockTypes[index] = "thinking"
                                    reasoningTextByIndex[index] = contentBlock.thinking
                                    contentBlock.signature.takeIf { it.isNotEmpty() }?.let { signature ->
                                        reasoningSignatureByIndex[index] = signature
                                        attachReasoningEncrypted(signature, index = index)
                                    }
                                    if (contentBlock.thinking.isNotEmpty()) {
                                        emitReasoningDelta(
                                            text = contentBlock.thinking,
                                            index = index,
                                        )
                                    }
                                }

                                is AnthropicContent.RedactedThinking -> {
                                    activeBlockTypes[index] = "redacted_thinking"
                                    attachReasoningReplay(
                                        MessagePart.ReasoningReplay.OpaqueRedacted(contentBlock.data),
                                        index = index,
                                    )
                                }

                                null -> throw LLMClientException(clientName, "Anthropic stream content block is missing")
                                else -> throw LLMClientException(
                                    clientName,
                                    "Unsupported Anthropic stream content block type: ${contentBlock::class}",
                                )
                            }
                        }

                        AnthropicStreamEventType.CONTENT_BLOCK_DELTA.value -> {
                            val delta = response.delta
                                ?: throw LLMClientException(clientName, "Anthropic stream delta is missing")
                            val index = response.index
                                ?: throw LLMClientException(clientName, "Anthropic stream delta index is missing")
                            when (delta.type) {
                                AnthropicStreamDeltaContentType.TEXT_DELTA.value -> {
                                    emitTextDelta(
                                        delta.text ?: throw LLMClientException(clientName, "Text delta is missing"),
                                        index = index,
                                    )
                                }

                                AnthropicStreamDeltaContentType.INPUT_JSON_DELTA.value -> {
                                    emitToolCallDelta(
                                        args = delta.partialJson
                                            ?: throw LLMClientException(clientName, "Tool args are missing"),
                                        index = index,
                                    )
                                }

                                AnthropicStreamDeltaContentType.THINKING_DELTA.value -> {
                                    val thinking = delta.thinking
                                        ?: throw LLMClientException(clientName, "Reasoning delta is missing")
                                    reasoningTextByIndex[index] = reasoningTextByIndex[index].orEmpty() + thinking
                                    emitReasoningDelta(
                                        text = thinking,
                                        index = index,
                                    )
                                }

                                AnthropicStreamDeltaContentType.SIGNATURE_DELTA.value -> {
                                    val signature = delta.signature
                                        ?: throw LLMClientException(clientName, "Reasoning signature delta is missing")
                                    reasoningSignatureByIndex[index] = signature
                                    attachReasoningEncrypted(
                                        encrypted = signature,
                                        index = index,
                                    )
                                }

                                else -> throw LLMClientException(
                                    clientName,
                                    "Unsupported Anthropic stream delta type: ${delta.type}",
                                )
                            }
                        }

                        AnthropicStreamEventType.CONTENT_BLOCK_STOP.value -> {
                            val index = response.index
                                ?: throw LLMClientException(clientName, "Content block stop index is missing")
                            when (activeBlockTypes.remove(index)) {
                                "text" -> tryEmitPendingText()
                                "tool_use" -> tryEmitPendingToolCall()
                                "thinking" -> {
                                    val signature = reasoningSignatureByIndex.remove(index)
                                        ?: throw LLMClientException(
                                            clientName,
                                            "Malformed Anthropic thinking block: signature is missing",
                                        )
                                    attachReasoningReplay(
                                        MessagePart.ReasoningReplay.Signed(
                                            text = reasoningTextByIndex.remove(index).orEmpty(),
                                            signature = signature,
                                        ),
                                        index = index,
                                    )
                                    tryEmitPendingReasoning()
                                }
                                "redacted_thinking" -> tryEmitPendingReasoning()
                                null -> throw LLMClientException(
                                    clientName,
                                    "Content block stop has no matching start for index $index",
                                )
                                else -> throw LLMClientException(clientName, "Unsupported Anthropic content block state")
                            }
                        }

                        AnthropicStreamEventType.MESSAGE_DELTA.value -> {
                            response.usage?.let(::updateUsage)
                            emitEnd(
                                finishReason = response.delta?.stopReason,
                                metaInfo = getMetaInfo()
                            )
                        }

                        AnthropicStreamEventType.MESSAGE_STOP.value -> {
                            logger.debug { "Received stop message event from Anthropic" }
                        }

                        AnthropicStreamEventType.ERROR.value -> {
                            throw LLMClientException(clientName, "Anthropic error: ${response.error}")
                        }

                        AnthropicStreamEventType.PING.value -> {
                            logger.debug { "Received ping from Anthropic" }
                        }

                        else -> throw LLMClientException(
                            clientName,
                            "Unsupported Anthropic stream event type: ${response.type}",
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw LLMClientException(
                    clientName = clientName,
                    message = e.message,
                    cause = e
                )
            }
        }.requireEndFrame()
    }

    internal fun CacheControl.toAnthropicCacheControl(): AnthropicCacheControlBlock? {
        return when (this) {
            is PromptCacheControl -> when {
                !cacheable -> null
                ttl == PromptCacheTtl.FiveMinutes -> AnthropicCacheControlBlock.Ephemeral()
                else -> AnthropicCacheControlBlock.Ephemeral(CacheTtl.OneHour)
            }
            else -> when (require<AnthropicCacheControl>()) {
                AnthropicCacheControl.Default -> AnthropicCacheControlBlock.Ephemeral()
                AnthropicCacheControl.OneHour -> AnthropicCacheControlBlock.Ephemeral(CacheTtl.OneHour)
            }
        }
    }

    private fun CacheControl.toPromptCacheMetadata(): PromptCacheControl? = when (this) {
        is PromptCacheControl -> this
        AnthropicCacheControl.Default -> PromptCacheControl(cacheable = true)
        AnthropicCacheControl.OneHour -> PromptCacheControl(cacheable = true, ttl = PromptCacheTtl.OneHour)
        else -> null
    }

    internal fun createAnthropicRequest(
        prompt: Prompt,
        tools: List<ToolDescriptor>,
        model: LLModel,
        stream: Boolean
    ): String {
        prompt.validateClientManagedExecutionPresentation()
        val toolBreakpoints = tools.mapNotNull { tool ->
            tool.cacheControl?.toPromptCacheMetadata()?.takeIf(PromptCacheControl::cacheable)?.ttl
        }
        val requestPrompt = PromptCachePolicy.requestView(
            prompt = prompt,
            leadingBreakpoints = toolBreakpoints,
            metadata = { it.toPromptCacheMetadata() },
        )
        val systemMessage = mutableListOf<SystemAnthropicMessage>()
        val messages = mutableListOf<AnthropicMessage>()

        for (message in requestPrompt.messages) {
            when (message) {
                is Message.System -> {
                    message.parts.forEach { part ->
                        systemMessage.add(
                            SystemAnthropicMessage(
                                part.text,
                                cacheControl = part.cacheControl.takeIf { part.text.isNotBlank() }
                                    ?.toAnthropicCacheControl()
                            )
                        )
                    }
                }

                is Message.User -> {
                    messages.add(message.toAnthropicUserMessage(model))
                }

                is Message.Assistant -> {
                    messages.add(message.toAnthropicAssistantMessage(model))
                }
            }
        }

        val anthropicTools = tools.map { tool ->
            val properties = mutableMapOf<String, JsonElement>()

            (tool.requiredParameters + tool.optionalParameters).forEach { param ->
                val typeMap = getTypeMapForParameter(param.type)

                properties[param.name] = JsonObject(
                    mapOf("description" to JsonPrimitive(param.description)) + typeMap
                )
            }

            AnthropicTool(
                name = tool.name,
                description = tool.description,
                inputSchema = AnthropicToolSchema(
                    properties = JsonObject(properties),
                    required = tool.requiredParameters.map { it.name }
                ),
                cacheControl = tool.cacheControl?.toAnthropicCacheControl()
            )
        }

        return serializeAnthropicMessageRequest(
            messages,
            systemMessage,
            model,
            anthropicTools,
            prompt.params,
            stream
        )
    }

    private fun serializeAnthropicMessageRequest(
        messages: List<AnthropicMessage>,
        systemMessages: List<SystemAnthropicMessage>,
        model: LLModel,
        tools: List<AnthropicTool>,
        params: LLMParams,
        stream: Boolean
    ): String {
        val anthropicParams = params.toAnthropicParams()
        val adaptiveThinking = anthropicParams.thinking as? ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking.Adaptive

        val toolChoice = when (val toolChoice = anthropicParams.toolChoice) {
            LLMParams.ToolChoice.Auto -> AnthropicToolChoice.Auto
            LLMParams.ToolChoice.None -> AnthropicToolChoice.None
            LLMParams.ToolChoice.Required -> AnthropicToolChoice.Any
            is LLMParams.ToolChoice.Named -> AnthropicToolChoice.Tool(name = toolChoice.name)
            null -> null
        }

        val outputConfig = anthropicParams.schema?.let { schema ->
            require(schema is LLMParams.Schema.JSON) {
                "Anthropic only supports JSON schemas for structured output"
            }
            AnthropicOutputConfig(
                format = AnthropicOutputFormat.JsonSchema(schema = schema.schema)
            )
        }
        val additionalOutputConfig = anthropicParams.additionalProperties?.get("output_config")
        require(additionalOutputConfig == null || additionalOutputConfig is JsonObject) {
            "Anthropic additionalProperties output_config must be a JSON object"
        }
        val requestAdditionalProperties =
            anthropicParams.additionalProperties
                ?.filterKeys { it != "output_config" }
                ?.takeIf { it.isNotEmpty() }

        // Always include max_tokens as it's required by the API
        val request = AnthropicMessageRequest(
            model = modelVersion(model),
            messages = messages,
            maxTokens = anthropicParams.maxTokens ?: AnthropicMessageRequest.MAX_TOKENS_DEFAULT,
            cacheControl = anthropicParams.cacheControl?.toAnthropicCacheControl(),
            container = anthropicParams.container,
            mcpServers = anthropicParams.mcpServers,
            outputConfig = outputConfig,
            serviceTier = anthropicParams.serviceTier,
            stopSequence = anthropicParams.stopSequences,
            stream = stream,
            system = systemMessages,
            temperature = anthropicParams.temperature.takeUnless { adaptiveThinking != null },
            thinking = anthropicParams.thinking,
            toolChoice = toolChoice,
            tools = tools,
            topK = anthropicParams.topK,
            topP = anthropicParams.topP,
            additionalProperties = requestAdditionalProperties,
        )

        val encodedRequest = json.encodeToJsonElement(AnthropicMessageRequestSerializer, request).jsonObject
        val typedOutputConfig = encodedRequest["output_config"] as? JsonObject
        val mergedOutputConfig =
            buildJsonObject {
                additionalOutputConfig?.forEach { (key, value) -> put(key, value) }
                typedOutputConfig?.forEach { (key, value) -> put(key, value) }
                adaptiveThinking?.effort?.let { put("effort", effortValue(it)) }
            }.takeIf { it.isNotEmpty() }
        val mergedRequest =
            buildJsonObject {
                encodedRequest.entries
                    .filterNot { (key, _) -> key == "output_config" }
                    .forEach { (key, value) -> put(key, value) }
                mergedOutputConfig?.let { put("output_config", it) }
            }

        return json.encodeToString(JsonObject.serializer(), requestDialect.transformRequestBody(mergedRequest))
    }

    private fun modelVersion(model: LLModel): String =
        requestDialect.modelVersionsMap[model] ?: throw IllegalArgumentException("Unsupported model: $model")

    private fun effortValue(effort: AnthropicEffort): String =
        when (effort) {
            AnthropicEffort.LOW -> "low"
            AnthropicEffort.MEDIUM -> "medium"
            AnthropicEffort.HIGH -> "high"
            AnthropicEffort.XHIGH -> "xhigh"
            AnthropicEffort.MAX -> "max"
        }

    private fun Message.Assistant.toAnthropicAssistantMessage(model: LLModel): AnthropicMessage.Assistant {
        val listOfContent = buildList {
            parts.forEach { part ->
                when (part) {
                    is MessagePart.Reasoning -> {
                        require(model.supports(LLMCapability.Thinking)) {
                            "Model ${model.id} does not support thinking"
                        }
                        addAll(part.toAnthropicReasoningBlocks())
                    }

                    is MessagePart.Text -> {
                        add(
                            AnthropicContent.Text(
                                text = part.text,
                                cacheControl = part.cacheControl.takeIf { part.text.isNotBlank() }
                                    ?.toAnthropicCacheControl()
                            )
                        )
                    }

                    is MessagePart.Attachment -> {
                        throw IllegalArgumentException("Attachments in assistant messages are not supported in Anthropic")
                    }

                    is MessagePart.CodeExecution -> {
                        throw IllegalArgumentException(
                            "Anthropic cannot replay provider-hosted code execution items"
                        )
                    }

                    is MessagePart.HostedExecution -> {
                        if (!part.isClientManagedExecutionPresentation()) {
                            throw IllegalArgumentException(
                                "Anthropic cannot replay provider-hosted execution items"
                            )
                        }
                    }

                    is MessagePart.GeneratedFile -> {
                        if (!part.isClientManagedExecutionPresentation()) {
                            throw IllegalArgumentException(
                                "Anthropic cannot replay provider-hosted execution items"
                            )
                        }
                    }

                    is MessagePart.Tool.Call -> {
                        require(model.supports(LLMCapability.Tools)) {
                            "Model ${model.id} does not support tools"
                        }
                        add(
                            AnthropicContent.ToolUse(
                                id = requireNotNull(part.id) {
                                    "Anthropic tool-call replay requires a call ID"
                                },
                                name = part.tool,
                                input = part.argsJson,
                                cacheControl = part.cacheControl?.toAnthropicCacheControl()
                            )
                        )
                    }
                }
            }
        }
        return AnthropicMessage.Assistant(content = listOfContent)
    }

    private fun MessagePart.Reasoning.toAnthropicReasoningBlocks(): List<AnthropicContent> {
        if (replay.isNotEmpty()) {
            return replay.map { replayBlock ->
                when (replayBlock) {
                    is MessagePart.ReasoningReplay.Signed -> AnthropicContent.Thinking(
                        thinking = replayBlock.text,
                        signature = replayBlock.signature,
                    )

                    is MessagePart.ReasoningReplay.OpaqueRedacted ->
                        AnthropicContent.RedactedThinking(data = replayBlock.data)
                }
            }
        }

        val thinking = when (content.size) {
            0 -> ""
            1 -> content.single()
            else -> throw IllegalArgumentException(
                "At most one content value is supported for reasoning messages",
            )
        }
        return listOf(
            AnthropicContent.Thinking(
                thinking = thinking,
                signature = encrypted
                    ?: throw IllegalArgumentException("Encrypted signature is required for reasoning messages but was null"),
            )
        )
    }

    private fun Message.User.toAnthropicUserMessage(model: LLModel): AnthropicMessage.User {
        val listOfContent = buildList {
            parts.forEach { part ->
                when (part) {
                    is MessagePart.Text -> add(
                        AnthropicContent.Text(
                            part.text,
                            cacheControl = part.cacheControl.takeIf { part.text.isNotBlank() }
                                ?.toAnthropicCacheControl()
                        )
                    )

                    is MessagePart.Tool.Result -> {
                        require(model.supports(LLMCapability.Tools)) {
                            "Model ${model.id} does not support tools"
                        }
                        add(
                            AnthropicContent.ToolResult(
                                toolUseId = requireNotNull(part.id) {
                                    "Anthropic tool-result replay requires a call ID"
                                },
                                content = part.toAnthropicToolResultContent(model),
                                isError = part.isError,
                                cacheControl = part.cacheControl.takeIf { part.hasAnthropicCacheableContent() }
                                    ?.toAnthropicCacheControl()
                            )
                        )
                    }

                    is MessagePart.Attachment -> {
                        when (val source = part.source) {
                            is AttachmentSource.Image -> {
                                require(model.supports(LLMCapability.Vision.Image)) {
                                    "Model ${model.id} does not support images"
                                }

                                val imageSource: ImageSource = when (val content = source.content) {
                                    is AttachmentContent.URL -> ImageSource.Url(content.url)

                                    is AttachmentContent.Binary -> ImageSource.Base64(
                                        content.asBase64(),
                                        source.mimeType
                                    )

                                    else -> throw LLMClientException(
                                        clientName,
                                        "Unsupported image attachment content: ${content::class}"
                                    )
                                }

                                add(
                                    AnthropicContent.Image(
                                        imageSource,
                                        cacheControl = part.cacheControl?.toAnthropicCacheControl()
                                    )
                                )
                            }

                            is AttachmentSource.File -> {
                                require(model.supports(LLMCapability.Document)) {
                                    "Model ${model.id} does not support files"
                                }

                                val documentSource: DocumentSource = when (val content = source.content) {
                                    is AttachmentContent.URL -> DocumentSource.Url(content.url)

                                    is AttachmentContent.Binary -> DocumentSource.Base64(
                                        content.asBase64(),
                                        source.mimeType
                                    )

                                    is AttachmentContent.PlainText -> DocumentSource.PlainText(
                                        content.text,
                                        source.mimeType
                                    )
                                }

                                add(
                                    AnthropicContent.Document(
                                        documentSource,
                                        cacheControl = part.cacheControl?.toAnthropicCacheControl()
                                    )
                                )
                            }

                            else -> throw LLMClientException(
                                clientName,
                                "Unsupported attachment type: $part"
                            )
                        }
                    }
                }
            }
        }

        return AnthropicMessage.User(content = listOfContent)
    }

    private fun MessagePart.Tool.Result.toAnthropicToolResultContent(model: LLModel): List<AnthropicContent> =
        parts.map { part ->
            when (part) {
                is MessagePart.Text -> AnthropicContent.Text(
                    part.text,
                    cacheControl = part.cacheControl.takeIf { part.text.isNotBlank() }
                        ?.toAnthropicCacheControl()
                )

                is MessagePart.Attachment -> {
                    when (val source = part.source) {
                        is AttachmentSource.Image -> {
                            val imageSource: ImageSource = when (val content = source.content) {
                                is AttachmentContent.URL -> ImageSource.Url(content.url)
                                is AttachmentContent.Binary -> ImageSource.Base64(content.asBase64(), source.mimeType)
                                else -> throw LLMClientException(
                                    clientName,
                                    "Unsupported image attachment content in tool result: ${content::class}"
                                )
                            }
                            AnthropicContent.Image(
                                imageSource,
                                cacheControl = part.cacheControl?.toAnthropicCacheControl()
                            )
                        }

                        is AttachmentSource.File -> {
                            val documentSource: DocumentSource = when (val content = source.content) {
                                is AttachmentContent.URL -> DocumentSource.Url(content.url)
                                is AttachmentContent.Binary -> DocumentSource.Base64(content.asBase64(), source.mimeType)
                                is AttachmentContent.PlainText -> DocumentSource.PlainText(content.text, source.mimeType)
                            }
                            AnthropicContent.Document(
                                documentSource,
                                cacheControl = part.cacheControl?.toAnthropicCacheControl()
                            )
                        }

                        else -> throw LLMClientException(
                            clientName,
                            "Unsupported attachment type in tool result: ${source::class}"
                        )
                    }
                }
            }
        }

    private fun MessagePart.Tool.Result.hasAnthropicCacheableContent(): Boolean = parts.any { part ->
        part is MessagePart.Attachment || part is MessagePart.Text && part.text.isNotBlank()
    }

    private fun processAnthropicResponse(response: AnthropicResponse): Message.Assistant {
        // Extract token count from the response
        val inputTokensCount = response.usage?.inputTokens
        val outputTokensCount = response.usage?.outputTokens
        val totalTokensCount = response.usage?.let { it.inputTokens?.plus(it.outputTokens ?: 0) ?: it.outputTokens }
        val cacheCreationInputTokens = response.usage?.cacheCreationInputTokens
        val cacheReadInputTokens = response.usage?.cacheReadInputTokens

        val cacheMetadata = buildJsonObject {
            cacheCreationInputTokens?.let { put("cacheCreationInputTokens", it) }
            cacheReadInputTokens?.let { put("cacheReadInputTokens", it) }
        }.takeIf { it.isNotEmpty() }

        val metaInfo = ResponseMetaInfo.create(
            clock,
            totalTokensCount = totalTokensCount,
            inputTokensCount = inputTokensCount,
            outputTokensCount = outputTokensCount,
            metadata = cacheMetadata,
        )

        val parts = response.content.map { content ->
            when (content) {
                is AnthropicContent.Thinking -> {
                    MessagePart.Reasoning(
                        encrypted = content.signature,
                        content = content.thinking.takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty(),
                        replay = listOf(
                            MessagePart.ReasoningReplay.Signed(
                                text = content.thinking,
                                signature = content.signature,
                            )
                        ),
                    )
                }

                is AnthropicContent.RedactedThinking -> MessagePart.Reasoning(
                    content = emptyList(),
                    replay = listOf(MessagePart.ReasoningReplay.OpaqueRedacted(content.data)),
                )

                is AnthropicContent.Text -> {
                    requireUnsupportedCitationsAbsent(content)
                    MessagePart.Text(
                        text = content.text,
                    )
                }

                is AnthropicContent.ToolUse -> {
                    MessagePart.Tool.Call(
                        id = content.id,
                        tool = content.name,
                        args = content.input,
                    )
                }

                else -> throw LLMClientException(
                    clientName,
                    "Unhandled AnthropicContent type. Content: $content"
                )
            }
        }

        return Message.Assistant(
            parts = parts,
            finishReason = response.stopReason,
            metaInfo = metaInfo
        )
    }

    /**
     * Helper function to get the type map for a parameter type without using smart casting
     */
    @OptIn(InternalAgentToolsApi::class)
    private fun getTypeMapForParameter(type: ToolParameterType): JsonObject {
        return when (type) {
            ToolParameterType.Boolean -> JsonObject(mapOf("type" to JsonPrimitive("boolean")))

            ToolParameterType.Float -> JsonObject(mapOf("type" to JsonPrimitive("number")))

            ToolParameterType.Integer -> JsonObject(mapOf("type" to JsonPrimitive("integer")))

            ToolParameterType.String -> JsonObject(mapOf("type" to JsonPrimitive("string")))

            ToolParameterType.Null -> JsonObject(mapOf("type" to JsonPrimitive("null")))

            is ToolParameterType.Enum -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("string"),
                    "enum" to JsonArray(type.entries.map { JsonPrimitive(it) })
                )
            )

            is ToolParameterType.List -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("array"),
                    "items" to getTypeMapForParameter(type.itemsType)
                )
            )

            is ToolParameterType.Object -> {
                // Create properties map with proper type information
                val propertiesMap = mutableMapOf<String, JsonElement>()

                for (prop in type.properties) {
                    // Get type information for the property
                    val typeInfo = getTypeMapForParameter(prop.type)

                    // Create a map with all type properties and description
                    val propMap = mutableMapOf<String, JsonElement>()
                    for (entry in typeInfo.entries) {
                        propMap[entry.key] = entry.value
                    }
                    propMap["description"] = JsonPrimitive(prop.description)

                    // Add to properties map
                    propertiesMap[prop.name] = JsonObject(propMap)
                }

                // Create the final object schema
                val objectMap = mutableMapOf<String, JsonElement>()
                objectMap["type"] = JsonPrimitive("object")
                objectMap["properties"] = JsonObject(propertiesMap)

                // Add required field if requiredProperties is not empty
                if (type.requiredProperties.isNotEmpty()) {
                    objectMap["required"] = JsonArray(type.requiredProperties.map { JsonPrimitive(it) })
                }

                // Add additionalProperties for strict validation
                objectMap["additionalProperties"] = JsonPrimitive(type.additionalProperties ?: false)

                JsonObject(objectMap)
            }

            is ToolParameterType.AnyOf -> {
                // FIXME this is hack, represent union types properly in ToolDescriptor
                type.hackRepresentAnyOfWithNullAsTypeUnionWithNull(::getTypeMapForParameter)
                    ?: JsonObject(
                        mapOf(
                            "anyOf" to JsonArray(
                                type.types.map { option ->
                                    val optionSchema = getTypeMapForParameter(option.type).toMutableMap()
                                    if (option.description.isNotBlank()) {
                                        optionSchema["description"] = JsonPrimitive(option.description)
                                    }
                                    JsonObject(optionSchema)
                                }
                            )
                        )
                    )
            }
        }
    }

    public override suspend fun models(): List<LLModel> {
        if (requestDialect is AnthropicRequestDialect.Vertex) {
            return requestDialect.modelVersionsMap.keys.toList()
        }
        logger.debug { "Fetching available models from Anthropic" }

        val response = httpClient.get(
            path = settings.modelsPath,
            responseType = AnthropicModelsResponse::class
        )

        val modelsById = AnthropicModels.modelsById()

        return response.data.map { modelsById[it.id] ?: LLModel(id = it.id, provider = LLMProvider.Anthropic) }
    }

    override fun getBasicJsonSchemaGenerator(): AnthropicBasicJsonSchemaGenerator {
        return AnthropicBasicJsonSchemaGenerator
    }

    override fun getStandardJsonSchemaGenerator(): AnthropicStandardJsonSchemaGenerator {
        return AnthropicStandardJsonSchemaGenerator
    }

    /**
     * Moderation is not supported by the Anthropic API.
     *
     * @throws UnsupportedOperationException Always thrown.
     */
    public override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
        logger.warn { "Moderation is not supported by Anthropic API" }
        throw UnsupportedOperationException("Moderation is not supported by Anthropic API.")
    }

    /**
     * Embedding is not supported by the Anthropic API.
     *
     * @throws UnsupportedOperationException Always thrown.
     */
    override suspend fun embed(
        text: String,
        model: LLModel
    ): List<Double> {
        logger.warn { "Embedding is not supported by Anthropic API" }
        throw UnsupportedOperationException("Embedding is not supported by Anthropic API.")
    }

    /**
     * Batch embedding is not supported by the Anthropic API.
     *
     * @throws UnsupportedOperationException Always thrown.
     */
    override suspend fun embed(
        inputs: List<String>,
        model: LLModel
    ): List<List<Double>> {
        logger.warn { "Embedding is not supported by Anthropic API" }
        throw UnsupportedOperationException("Embedding is not supported by Anthropic API.")
    }

    override fun close() {
        httpClient.close()
    }

    private fun requireUnsupportedCitationsAbsent(content: AnthropicContent.Text) {
        if (content.citations != null) {
            throw LLMClientException(clientName, "Anthropic citations are not supported")
        }
    }

    private fun decodeAnthropicStreamResponse(payload: String): AnthropicStreamResponse {
        try {
            val element = json.parseToJsonElement(payload)
            val root = element as? JsonObject
                ?: throw LLMClientException(clientName, "Anthropic stream event must be a JSON object")
            val eventType = (root["type"] as? JsonPrimitive)?.content
            var signature: String? = null
            if (eventType == AnthropicStreamEventType.CONTENT_BLOCK_START.value) {
                val block = root["content_block"] as? JsonObject
                    ?: throw LLMClientException(clientName, "Anthropic stream content block is missing")
                val blockType = block.requireStringField("content block", "type")
                if (blockType !in setOf("text", "tool_use", "thinking", "redacted_thinking")) {
                    throw LLMClientException(clientName, "Unsupported Anthropic stream content block type: $blockType")
                }
                if (block.containsKey("citations")) {
                    throw LLMClientException(clientName, "Anthropic citations are not supported")
                }
                when (blockType) {
                    "thinking" -> {
                        block.requireStringField("thinking", "thinking")
                        if (block.containsKey("signature")) {
                            block.requireStringField("thinking", "signature")
                        }
                    }

                    "redacted_thinking" -> block.requireOpaqueData()
                }
            }
            if (eventType == AnthropicStreamEventType.CONTENT_BLOCK_DELTA.value) {
                val delta = root["delta"] as? JsonObject
                    ?: throw LLMClientException(clientName, "Anthropic stream delta is missing")
                val deltaType = (delta["type"] as? JsonPrimitive)?.content
                if (
                    deltaType !in
                    setOf(
                        AnthropicStreamDeltaContentType.TEXT_DELTA.value,
                        AnthropicStreamDeltaContentType.INPUT_JSON_DELTA.value,
                        AnthropicStreamDeltaContentType.THINKING_DELTA.value,
                        AnthropicStreamDeltaContentType.SIGNATURE_DELTA.value,
                    )
                ) {
                    throw LLMClientException(clientName, "Unsupported Anthropic stream delta type: $deltaType")
                }
                if (deltaType == AnthropicStreamDeltaContentType.SIGNATURE_DELTA.value) {
                    signature = delta.requireStringField("signature_delta", "signature").also {
                        if (it.isEmpty()) {
                            throw LLMClientException(
                                clientName,
                                "Malformed Anthropic signature_delta block: 'signature' must not be empty",
                            )
                        }
                    }
                }
            }
            val decodeElement = root.withMissingThinkingStartSignature()
            return json.decodeFromJsonElement<AnthropicStreamResponse>(decodeElement).also { response ->
                response.delta?.signature = signature
            }
        } catch (cause: LLMClientException) {
            throw cause
        } catch (cause: Exception) {
            throw LLMClientException(clientName, "Malformed Anthropic stream event: ${cause.message}", cause)
        }
    }

    private fun decodeAnthropicResponse(payload: String): AnthropicResponse {
        try {
            val element = json.parseToJsonElement(payload)
            val root = element as? JsonObject
                ?: throw LLMClientException(clientName, "Anthropic response must be a JSON object")
            (root["content"] as? JsonArray)?.forEach { contentElement ->
                val content = contentElement as? JsonObject
                    ?: throw LLMClientException(clientName, "Anthropic content block must be a JSON object")
                when (val contentType = content.requireStringField("content block", "type")) {
                    "text" -> if (content.containsKey("citations")) {
                        throw LLMClientException(clientName, "Anthropic citations are not supported")
                    }

                    "thinking" -> {
                        content.requireStringField("thinking", "thinking")
                        content.requireStringField("thinking", "signature").also { signature ->
                            if (signature.isEmpty()) {
                                throw LLMClientException(
                                    clientName,
                                    "Malformed Anthropic thinking block: 'signature' must not be empty",
                                )
                            }
                        }
                    }

                    "redacted_thinking" -> content.requireOpaqueData()
                    "tool_use" -> Unit
                    else -> throw LLMClientException(
                        clientName,
                        "Unsupported Anthropic content block type: $contentType",
                    )
                }
            }
            return json.decodeFromJsonElement(element)
        } catch (cause: LLMClientException) {
            throw cause
        } catch (cause: Exception) {
            throw LLMClientException(clientName, "Malformed Anthropic response: ${cause.message}", cause)
        }
    }

    private fun JsonObject.requireOpaqueData(): String =
        requireStringField("redacted_thinking", "data").also { data ->
            if (data.isEmpty()) {
                throw LLMClientException(
                    clientName,
                    "Malformed Anthropic redacted_thinking block: 'data' must not be empty",
                )
            }
        }

    private fun JsonObject.requireStringField(blockType: String, field: String): String {
        val value = this[field] as? JsonPrimitive
        if (value == null || !value.isString) {
            throw LLMClientException(
                clientName,
                "Malformed Anthropic $blockType block: '$field' must be a string",
            )
        }
        return value.content
    }

    private fun JsonObject.withMissingThinkingStartSignature(): JsonObject {
        if ((this["type"] as? JsonPrimitive)?.content != AnthropicStreamEventType.CONTENT_BLOCK_START.value) {
            return this
        }
        val block = this["content_block"] as? JsonObject ?: return this
        if ((block["type"] as? JsonPrimitive)?.content != "thinking" || block.containsKey("signature")) {
            return this
        }
        return JsonObject(this + ("content_block" to JsonObject(block + ("signature" to JsonPrimitive("")))))
    }
}
