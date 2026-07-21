package ai.koog.prompt.executor.clients.openai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationCategory
import ai.koog.prompt.dsl.ModerationCategoryResult
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.modelsById
import ai.koog.prompt.executor.clients.openai.base.AbstractOpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.OpenAIBaseSettings
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIAudioConfig
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIAudioFormat
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIAudioVoice
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIContentPart
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIModalities
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIStaticContent
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIStreamOptions
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.InputContent
import ai.koog.prompt.executor.clients.openai.models.Item
import ai.koog.prompt.executor.clients.openai.models.OpenAIChatCompletionRequest
import ai.koog.prompt.executor.clients.openai.models.OpenAIChatCompletionRequestSerializer
import ai.koog.prompt.executor.clients.openai.models.OpenAIChatCompletionResponse
import ai.koog.prompt.executor.clients.openai.models.OpenAIChatCompletionStreamResponse
import ai.koog.prompt.executor.clients.openai.models.OpenAICodeInterpreterContainer
import ai.koog.prompt.executor.clients.openai.models.OpenAIEmbeddingRequest
import ai.koog.prompt.executor.clients.openai.models.OpenAIEmbeddingResponse
import ai.koog.prompt.executor.clients.openai.models.OpenAIInputStatus
import ai.koog.prompt.executor.clients.openai.models.OpenAIAnnotations
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.executor.clients.openai.models.OpenAIModelsResponse
import ai.koog.prompt.executor.clients.openai.models.OpenAIOutputFormat
import ai.koog.prompt.executor.clients.openai.models.OpenAIResponsesAPIRequest
import ai.koog.prompt.executor.clients.openai.models.OpenAIResponsesAPIRequestSerializer
import ai.koog.prompt.executor.clients.openai.models.OpenAIResponsesAPIResponse
import ai.koog.prompt.executor.clients.openai.models.OpenAIResponsesTool
import ai.koog.prompt.executor.clients.openai.models.OpenAIResponsesTool.Function
import ai.koog.prompt.executor.clients.openai.models.OpenAIResponsesToolChoice
import ai.koog.prompt.executor.clients.openai.models.OpenAIStreamEvent
import ai.koog.prompt.executor.clients.openai.models.OpenAITextConfig
import ai.koog.prompt.executor.clients.openai.models.OutputContent
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.buildStreamFrameFlow
import ai.koog.prompt.streaming.requireEndFrame
import ai.koog.utils.io.SuitableForIO
import ai.koog.utils.time.KoogClock
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmOverloads
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import ai.koog.prompt.executor.clients.openai.base.models.Content as OpenAIContent

/** Wire dialect used by the Responses client. Compatible endpoints must declare Responses support explicitly. */
public enum class OpenAIResponsesDialect { OpenAI, Azure, Compatible }

/** Authentication header mechanism applied by the factory-backed client constructor. */
public enum class OpenAICredentialMechanism { Bearer, ApiKey }

/** Closed declaration of Responses support for one configured source. */
public sealed interface OpenAIResponsesCapability {
    /** The source supports the Responses API. */
    public data object Supported : OpenAIResponsesCapability

    /** The source does not declare Responses support and cannot serve a Responses request. */
    public data class Unsupported(public val reason: String) : OpenAIResponsesCapability
}

/** Typed configuration failure raised before an unsupported Responses source performs inference. */
public class OpenAIResponsesConfigurationException(public val reason: String) : IllegalArgumentException(reason)

/**
 * Represents the settings for configuring an OpenAI client.
 *
 * @property responsesAPIPath Explicit Responses endpoint path.
 * @property embeddingsPath Explicit embeddings endpoint path.
 * @property moderationsPath Explicit moderation endpoint path.
 * @property modelsPath Explicit model-catalogue endpoint path.
 * @property responsesDialect Provider wire dialect, never inferred from the hostname.
 * @property credentialMechanism Authentication header mechanism used by the factory constructor.
 * @property queryParameters Provider query parameters, including an explicit Azure API version.
 * @property deployment Explicit Azure deployment name when the dialect is Azure.
 * @property apiVersion Explicit Azure API version when the dialect is Azure.
 */
public class OpenAIClientSettings(
    baseUrl: String = "https://api.openai.com",
    timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig(),
    chatCompletionsPath: String = "v1/chat/completions",
    public val responsesAPIPath: String = "v1/responses",
    public val embeddingsPath: String = "v1/embeddings",
    public val moderationsPath: String = "v1/moderations",
    public val modelsPath: String = "v1/models",
    public val responsesDialect: OpenAIResponsesDialect = OpenAIResponsesDialect.OpenAI,
    declaredResponsesCapability: OpenAIResponsesCapability? = null,
    public val credentialMechanism: OpenAICredentialMechanism = OpenAICredentialMechanism.Bearer,
    public val queryParameters: Map<String, String> = emptyMap(),
    public val deployment: String? = null,
    public val apiVersion: String? = null,
) : OpenAIBaseSettings(baseUrl, chatCompletionsPath, timeoutConfig) {
    /** Preserves the pre-Responses-settings JVM constructor for already compiled callers. */
    public constructor(
        baseUrl: String,
        timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig(),
        chatCompletionsPath: String = "v1/chat/completions",
        responsesAPIPath: String = "v1/responses",
        embeddingsPath: String = "v1/embeddings",
        moderationsPath: String = "v1/moderations",
        modelsPath: String = "v1/models",
    ) : this(
        baseUrl = baseUrl,
        timeoutConfig = timeoutConfig,
        chatCompletionsPath = chatCompletionsPath,
        responsesAPIPath = responsesAPIPath,
        embeddingsPath = embeddingsPath,
        moderationsPath = moderationsPath,
        modelsPath = modelsPath,
        responsesDialect = OpenAIResponsesDialect.OpenAI,
    )

    /** Effective capability. Compatible endpoints default to unsupported until callers declare support. */
    public val responsesCapability: OpenAIResponsesCapability = declaredResponsesCapability ?: when (responsesDialect) {
        OpenAIResponsesDialect.OpenAI,
        OpenAIResponsesDialect.Azure -> OpenAIResponsesCapability.Supported
        OpenAIResponsesDialect.Compatible -> OpenAIResponsesCapability.Unsupported(
            "OpenAI-compatible source must declare Responses support"
        )
    }
}

/**
 * Implementation of [LLMClient] for OpenAI API.
 *
 * @param settings The base URL and timeouts for the OpenAI API, defaults to "https://api.openai.com" and 900 s
 * @param httpClient A fully configured [KoogHttpClient] for making API requests. Use the secondary constructor
 *   that accepts an API key and a [KoogHttpClient.Factory] to create a client with standard defaults.
 * @param clock Clock instance used for tracking response metadata timestamps.
 */
@OptIn(ExperimentalAtomicApi::class)
public open class OpenAILLMClient @JvmOverloads constructor(
    private val settings: OpenAIClientSettings = OpenAIClientSettings(),
    httpClient: KoogHttpClient,
    clock: KoogClock = KoogClock.System,
    private val toolsConverter: OpenAICompatibleToolDescriptorSchemaGenerator = OpenAICompatibleToolDescriptorSchemaGenerator(),
) : AbstractOpenAILLMClient<OpenAIChatCompletionResponse, OpenAIChatCompletionStreamResponse>(
    settings = settings,
    httpClient = httpClient,
    clock = clock,
    logger = staticLogger,
    toolsConverter = toolsConverter
) {

    @JvmOverloads
    public constructor(
        apiKey: String,
        settings: OpenAIClientSettings = OpenAIClientSettings(),
        httpClientFactory: KoogHttpClient.Factory,
        clock: KoogClock = KoogClock.System,
        toolsConverter: OpenAICompatibleToolDescriptorSchemaGenerator = OpenAICompatibleToolDescriptorSchemaGenerator(),
    ) : this(
        settings = settings,
        httpClient = httpClientFactory.create(
            clientName = OPENAI_CLIENT_NAME,
            baseUrl = settings.baseUrl,
            headers = when (settings.credentialMechanism) {
                OpenAICredentialMechanism.Bearer -> mapOf("Authorization" to "Bearer $apiKey")
                OpenAICredentialMechanism.ApiKey -> mapOf("api-key" to apiKey)
            },
            queryParameters = settings.queryParameters,
            requestTimeoutMillis = settings.timeoutConfig.requestTimeoutMillis,
            connectTimeoutMillis = settings.timeoutConfig.connectTimeoutMillis,
            socketTimeoutMillis = settings.timeoutConfig.socketTimeoutMillis,
            json = defaultOpenAIJson,
        ),
        clock = clock,
        toolsConverter = toolsConverter
    )

    /**
     * Returns the specific implementation of the `LLMProvider` associated with this client.
     *
     * In this case, it identifies the `OpenAI` provider as the designated LLM provider
     * for the client.
     *
     * @return The `LLMProvider` instance representing OpenAI.
     */
    override fun llmProvider(): LLMProvider = LLMProvider.OpenAI

    override fun serializeProviderChatRequest(
        messages: List<OpenAIMessage>,
        model: LLModel,
        tools: List<OpenAITool>?,
        toolChoice: OpenAIToolChoice?,
        params: LLMParams,
        stream: Boolean
    ): String {
        val chatParams = params.toOpenAIChatParams()
        val modalities = if (model.supports(LLMCapability.Audio)) {
            listOf(OpenAIModalities.Text, OpenAIModalities.Audio)
        } else {
            null
        }
        val audioConfig = if (chatParams.audio == null && model.supports(LLMCapability.Audio)) {
            OpenAIAudioConfig(OpenAIAudioFormat.MP3, OpenAIAudioVoice.Alloy)
        } else {
            chatParams.audio
        }

        val responseFormat = createResponseFormat(chatParams.schema, model)
        val streamOptions = if (stream) {
            OpenAIStreamOptions(includeUsage = true)
        } else {
            null
        }
        val reasoningEffort = chatParams.reasoningEffort?.let { effort ->
            if (model.isGPT5_6() && effort == ReasoningEffort.MAX) ReasoningEffort.XHIGH else effort
        }

        val request = OpenAIChatCompletionRequest(
            messages = messages,
            model = model.id,
            audio = audioConfig,
            frequencyPenalty = chatParams.frequencyPenalty,
            logprobs = chatParams.logprobs,
            maxCompletionTokens = chatParams.maxTokens,
            modalities = modalities,
            numberOfChoices = model.takeIf { it.supports(LLMCapability.MultipleChoices) }
                ?.let { chatParams.numberOfChoices },
            parallelToolCalls = chatParams.parallelToolCalls,
            prediction = chatParams.speculation?.let { OpenAIStaticContent(OpenAIContent.Text(it)) },
            presencePenalty = chatParams.presencePenalty,
            promptCacheKey = chatParams.promptCacheKey,
            reasoningEffort = model.takeIf { it.supports(LLMCapability.Thinking) }
                ?.let { reasoningEffort },
            responseFormat = responseFormat,
            safetyIdentifier = chatParams.safetyIdentifier,
            serviceTier = chatParams.serviceTier,
            stop = chatParams.stop,
            store = chatParams.store,
            stream = stream,
            streamOptions = streamOptions,
            temperature = model.reasoningTemperature(chatParams.temperature, chatParams.reasoningEffort),
            toolChoice = toolChoice,
            tools = tools,
            topLogprobs = chatParams.topLogprobs,
            topP = chatParams.topP,
            user = chatParams.user,
            webSearchOptions = chatParams.webSearchOptions,
            additionalProperties = chatParams.additionalProperties,
        )

        return json.encodeToString(OpenAIChatCompletionRequestSerializer, request)
    }

    internal fun serializeResponsesAPIRequest(
        messages: List<Item>,
        model: LLModel,
        tools: List<OpenAIResponsesTool>?,
        toolChoice: OpenAIResponsesToolChoice?,
        params: OpenAIResponsesParams,
        stream: Boolean
    ): String {
        if (params.stateless) {
            require(params.store != true) { "Stateless Responses requests require store=false" }
        }
        val responseFormat = params.schema?.let { schema ->
            require(model.supports(schema.capability)) {
                "Model ${model.id} does not support structured output schema ${schema.name}"
            }
            when (schema) {
                is LLMParams.Schema.JSON -> OpenAITextConfig(
                    format = OpenAIOutputFormat.JsonSchema(
                        name = schema.name,
                        schema = schema.schema,
                        strict = true
                    )
                )
            }
        }

        val responsesTools = buildList {
            addAll(tools.orEmpty())
            params.codeInterpreter?.let { add(it.toOpenAIResponsesTool()) }
        }.ifEmpty { null }

        val request = OpenAIResponsesAPIRequest(
            background = params.background,
            include = params.include.withEncryptedReasoningForStateless(params.stateless),
            input = messages,
            maxOutputTokens = params.maxTokens,
            maxToolCalls = params.maxToolCalls,
            model = settings.deployment ?: model.id,
            parallelToolCalls = params.parallelToolCalls,
            promptCacheKey = params.promptCacheKey,
            reasoning = model.takeIf { it.supports(LLMCapability.Thinking) }
                ?.let { params.reasoning },
            safetyIdentifier = params.safetyIdentifier,
            serviceTier = params.serviceTier,
            store = if (params.stateless) false else params.store,
            stream = stream,
            temperature = model.reasoningTemperature(params.temperature, params.reasoning?.effort),
            text = responseFormat,
            toolChoice = toolChoice,
            tools = responsesTools,
            topLogprobs = params.topLogprobs,
            topP = params.topP,
            truncation = params.truncation,
            additionalProperties = params.additionalProperties,
        )

        return json.encodeToString(OpenAIResponsesAPIRequestSerializer, request)
    }

    private fun List<OpenAIInclude>?.withEncryptedReasoningForStateless(stateless: Boolean): List<OpenAIInclude>? {
        if (!stateless) return this
        return (orEmpty() + OpenAIInclude.REASONING_ENCRYPTED_CONTENT).distinct()
    }

    private fun LLModel.reasoningTemperature(
        temperature: Double?,
        effort: ReasoningEffort?,
    ): Double? = temperature.takeUnless { isGPT5_6() && effort != null && effort != ReasoningEffort.NONE }

    private fun OpenAICodeInterpreterConfig.toOpenAIResponsesTool(): OpenAIResponsesTool.CodeInterpreter {
        val validated = OpenAICodeInterpreterConfig(fileIds = fileIds.toList(), containerId = containerId)
        val container = when {
            validated.containerId != null -> OpenAICodeInterpreterContainer.Reused(validated.containerId)
            validated.fileIds.isEmpty() -> OpenAICodeInterpreterContainer.Auto
            else -> OpenAICodeInterpreterContainer.AutoWithFiles(fileIds = validated.fileIds)
        }
        return OpenAIResponsesTool.CodeInterpreter(container)
    }

    private fun LLModel.isGPT5_6(): Boolean =
        contextLength == 1_050_000L &&
            maxOutputTokens == 128_000L &&
            capabilities == OpenAIModels.Chat.GPT5_6Sol.capabilities

    override val clientName: String = OPENAI_CLIENT_NAME

    private companion object {
        private const val OPENAI_CLIENT_NAME = "OpenAILLMClient"
        private val staticLogger = KotlinLogging.logger { }
        private val defaultOpenAIJson = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
            namingStrategy = kotlinx.serialization.json.JsonNamingStrategy.SnakeCase
        }
    }

    override fun processProviderChatResponse(response: OpenAIChatCompletionResponse): List<Message.Assistant> {
        require(response.choices.isNotEmpty()) { "Empty choices in response" }
        return response.choices.map {
            it.message.toMessageResponse(
                it.finishReason,
                createMetaInfo(response.usage),
            )
        }
    }

    override fun decodeStreamingResponse(data: String): OpenAIChatCompletionStreamResponse =
        json.decodeFromString(data)

    override fun decodeResponse(data: String): OpenAIChatCompletionResponse =
        json.decodeFromString(data)

    override fun processStreamingResponse(
        response: Flow<OpenAIChatCompletionStreamResponse>
    ): Flow<StreamFrame> = buildStreamFrameFlow {
        var finishReason: String? = null
        var metaInfo: ResponseMetaInfo? = null

        response.collect { chunk ->
            chunk.choices.firstOrNull()?.let { choice ->
                choice.delta.content?.let { emitTextDelta(it, choice.index) }

                choice.delta.toolCalls?.forEach { openAIToolCall ->
                    val index = openAIToolCall.index
                    val id = openAIToolCall.id
                    val functionName = openAIToolCall.function?.name
                    val functionArgs = openAIToolCall.function?.arguments
                    emitToolCallDelta(id, functionName, functionArgs, index)
                }

                choice.finishReason?.let { finishReason = it }
            }

            chunk.usage?.let { metaInfo = createMetaInfo(it) }
        }

        emitEnd(finishReason, metaInfo)
    }

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        return selectExecutionStrategy(prompt, model) { params ->
            when (params) {
                is OpenAIResponsesParams -> {
                    model.requireCapability(LLMCapability.OpenAIEndpoint.Responses)
                    val result = getResponseWithResponsesAPI(prompt, params, model, tools)
                    processResponsesAPIResponse(result.response, result.recoveredContainerId)
                }

                is OpenAIChatParams -> {
                    model.requireCapability(LLMCapability.OpenAIEndpoint.Completions)
                    super.execute(prompt, model, tools)
                }
            }
        }
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> = selectExecutionStrategy(prompt, model) { params ->
        when (params) {
            is OpenAIResponsesParams -> executeResponsesStreaming(prompt, model, tools, params)
            is OpenAIChatParams -> super.executeStreaming(prompt, model, tools)
        }
    }

    private fun executeResponsesStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
        params: OpenAIResponsesParams
    ): Flow<StreamFrame> {
        logger.debug { "Executing streaming prompt: $prompt with model: $model" }

        if (tools.isNotEmpty() || params.codeInterpreter != null) {
            model.requireCapability(LLMCapability.Tools)
        }
        val llmTools = tools.takeIf { it.isNotEmpty() }?.map {
            Function(
                name = it.name,
                parameters = toolsConverter.generate(it),
                description = it.description
            )
        }

        val messages = convertPromptToResponsesMessages(prompt, model)
        val request = serializeResponsesAPIRequest(
            messages = messages,
            model = model,
            tools = llmTools,
            toolChoice = prompt.params.toolChoice?.toOpenAIResponseToolChoice(),
            params = params,
            stream = true
        )
        val toolCallsByItemId = mutableMapOf<String, Item.FunctionToolCall>()
        val codeContainerByItemId = mutableMapOf<String, String>()
        val citationsByItemId = mutableMapOf<String, MutableList<MessagePart.GeneratedFileCitation>>()
        var providerEventSeen = false

        return httpClient.sse(
                path = settings.responsesAPIPath,
                requestBody = request,
                requestBodyType = String::class,
                decodeStreamingResponse = {
                    json.decodeFromString<OpenAIStreamEvent>(it)
                },
                processStreamingChunk = { event ->
                    providerEventSeen = true
                    buildList {
                        when (event) {
                            is OpenAIStreamEvent.ResponseOutputItemAdded -> {
                                when (val item = event.item) {
                                    is Item.FunctionToolCall ->
                                        item.id?.let { itemId -> toolCallsByItemId[itemId] = item }
                                    is Item.CodeInterpreterToolCall -> {
                                        codeContainerByItemId[item.id] = item.containerId
                                        add(
                                            StreamFrame.CodeExecutionStart(
                                                id = "execution:${item.id}",
                                                containerId = item.containerId,
                                                index = event.outputIndex,
                                                providerItemId = item.id,
                                            )
                                        )
                                    }
                                    else -> Unit
                                }
                            }

                            is OpenAIStreamEvent.ResponseOutputTextDelta -> {
                                add(
                                    StreamFrame.TextDelta(
                                        text = event.delta,
                                        index = event.outputIndex,
                                        providerItemId = event.itemId,
                                    )
                                )
                            }

                            is OpenAIStreamEvent.ResponseOutputTextDone -> {
                                add(
                                    StreamFrame.TextComplete(
                                        text = event.text,
                                        index = event.outputIndex,
                                        providerItemId = event.itemId,
                                        generatedFileCitations = citationsByItemId.remove(event.itemId).orEmpty(),
                                    )
                                )
                            }

                            is OpenAIStreamEvent.ResponseOutputTextAnnotationAdded -> {
                                event.annotation.toGeneratedFileCitation(event.itemId)?.let { citation ->
                                    citationsByItemId.getOrPut(event.itemId, ::mutableListOf).add(citation)
                                }
                            }

                            is OpenAIStreamEvent.ResponseReasoningTextDelta -> {
                                // https://developers.openai.com/api/reference/resources/responses/streaming-events#response.reasoning_text.delta
                                add(
                                    StreamFrame.ReasoningDelta(
                                        text = event.delta,
                                        index = event.outputIndex,
                                        providerItemId = event.itemId,
                                    )
                                )
                            }

                            is OpenAIStreamEvent.ResponseReasoningSummaryTextDelta -> {
                                // https://developers.openai.com/api/reference/resources/responses/streaming-events#response.reasoning_text.delta
                                add(
                                    StreamFrame.ReasoningDelta(
                                        summary = event.delta,
                                        index = event.outputIndex,
                                        providerItemId = event.itemId,
                                    )
                                )
                            }

                            is OpenAIStreamEvent.ResponseFunctionCallArgumentsDelta -> {
                                val toolCall = toolCallsByItemId[event.itemId]
                                add(
                                    StreamFrame.ToolCallDelta(
                                        id = toolCall?.callId ?: event.itemId,
                                        name = toolCall?.name,
                                        content = event.delta,
                                        index = event.outputIndex,
                                        providerItemId = event.itemId,
                                    )
                                )
                            }

                            is OpenAIStreamEvent.ResponseCodeInterpreterCallCodeDelta -> {
                                val containerId = requireNotNull(codeContainerByItemId[event.itemId]) {
                                    "Code Interpreter delta arrived before its output item was added"
                                }
                                add(
                                    StreamFrame.CodeExecutionCodeDelta(
                                        id = "execution:${event.itemId}",
                                        containerId = containerId,
                                        code = event.delta,
                                        index = event.outputIndex,
                                        providerItemId = event.itemId,
                                    )
                                )
                            }

                            is OpenAIStreamEvent.ResponseOutputItemDone -> {
                                when (val item = event.item) {
                                    is Item.Text -> {
                                        add(StreamFrame.TextComplete(item.value, event.outputIndex))
                                    }

                                    is Item.Reasoning -> {
                                        // https://developers.openai.com/api/reference/resources/responses/streaming-events#response.reasoning_text.done
                                        if (
                                            item.summary.isEmpty() &&
                                            item.content.isNullOrEmpty() &&
                                            item.encryptedContent == null
                                        ) {
                                            logger.debug { "Got and empty (hidden) reasoning from the model, ignoring it." }
                                        } else {
                                            add(
                                                StreamFrame.ReasoningComplete(
                                                    id = null,
                                                    content = item.content?.map { content -> content.text } ?: emptyList(),
                                                    summary = item.summary.map { content -> content.text },
                                                    encrypted = item.encryptedContent,
                                                    index = event.outputIndex,
                                                    providerItemId = item.id,
                                                )
                                            )
                                        }
                                    }

                                    is Item.FunctionToolCall -> {
                                        item.id?.let { itemId -> toolCallsByItemId.remove(itemId) }
                                        add(
                                            StreamFrame.ToolCallComplete(
                                                id = item.callId,
                                                name = item.name,
                                                content = item.arguments,
                                                index = event.outputIndex,
                                                providerItemId = item.id,
                                            )
                                        )
                                    }

                                    is Item.CodeInterpreterToolCall -> {
                                        codeContainerByItemId.remove(item.id)
                                        val part = item.toMessagePart()
                                        part.outputs.forEach { output ->
                                            add(
                                                StreamFrame.CodeExecutionOutput(
                                                    id = part.id,
                                                    containerId = part.containerId,
                                                    output = output,
                                                    index = event.outputIndex,
                                                    providerItemId = item.id,
                                                )
                                            )
                                        }
                                        part.failure?.let { failure ->
                                            add(
                                                StreamFrame.CodeExecutionFailure(
                                                    id = part.id,
                                                    containerId = part.containerId,
                                                    failure = failure,
                                                    index = event.outputIndex,
                                                    providerItemId = item.id,
                                                )
                                            )
                                        }
                                        add(
                                            StreamFrame.CodeExecutionComplete(
                                                id = part.id,
                                                code = part.code,
                                                containerId = part.containerId,
                                                outputs = part.outputs,
                                                failure = part.failure,
                                                index = event.outputIndex,
                                                providerItemId = item.id,
                                            )
                                        )
                                        item.toHostedExecutionParts().forEach { hostedPart ->
                                            add(StreamFrame.HostedExecutionUpdate(hostedPart, event.outputIndex))
                                        }
                                    }

                                    else -> Unit
                                }
                            }

                            is OpenAIStreamEvent.ResponseCompleted -> {
                                add(
                                    StreamFrame.End(
                                        finishReason = null,
                                        metaInfo = event.response.usage.let { usage ->
                                            ResponseMetaInfo.create(
                                                clock = clock,
                                                totalTokensCount = usage?.totalTokens,
                                                inputTokensCount = usage?.inputTokens,
                                                outputTokensCount = usage?.outputTokens,
                                            )
                                        },
                                    )
                                )
                            }

                            else -> Unit
                        }
                    }
                }
            ).transform { frames ->
            frames.forEach { emit(it) }
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            val recoveryParams = params.staleContainerRecoveryParams(failure, providerEventSeen)
            if (recoveryParams != null) {
                val staleContainerId = requireNotNull(params.codeInterpreter?.containerId)
                emit(
                    StreamFrame.HostedExecutionUpdate(
                        MessagePart.HostedExecution.Progress(
                            message = "stale_container_recovered",
                            executionId = staleContainerRecoveryExecutionId(staleContainerId),
                            containerId = staleContainerId,
                        )
                    )
                )
                emitAll(executeResponsesStreaming(prompt, model, tools, recoveryParams))
            } else {
                throw LLMClientException(clientName = clientName, message = failure.message, cause = failure)
            }
        }.requireEndFrame()
    }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): List<Message.Assistant> = selectExecutionStrategy(prompt, model) { params ->
        when (params) {
            is OpenAIChatParams -> super.executeMultipleChoices(prompt, model, tools)

            is OpenAIResponsesParams -> {
                /*
                Responses API does not currently expose a native "n" parameter,
                 so we issue multiple independent responses and aggregate them.
                 This path is required for models like gpt-5.1-codex that only
                 support the Responses endpoint and return 404 on Chat Completions.
                 */
                val choices = (params.numberOfChoices ?: 1).coerceAtLeast(1)
                coroutineScope {
                    List(choices) {
                        async {
                            val result = getResponseWithResponsesAPI(prompt, params, model, tools)
                            processResponsesAPIResponse(result.response, result.recoveredContainerId)
                        }
                    }.awaitAll()
                }
            }
        }
    }

    /**
     * Embeds the given text using the OpenAI embeddings API.
     *
     * @param text The text to embed.
     * @param model The model to use for embedding. Must have the Embed capability.
     * @return A list of floating-point values representing the embedding.
     * @throws IllegalArgumentException if the model does not have the Embed capability.
     */
    override suspend fun embed(text: String, model: LLModel): List<Double> {
        model.requireCapability(LLMCapability.Embed)

        logger.debug { "Embedding text with model: ${model.id}" }

        val request = OpenAIEmbeddingRequest(
            model = model.id,
            input = text
        )

        val openAIResponse = try {
            httpClient.post(
                path = settings.embeddingsPath,
                requestBody = request,
                requestBodyType = OpenAIEmbeddingRequest::class,
                responseType = OpenAIEmbeddingResponse::class
            )
        } catch (e: Exception) {
            throw LLMClientException(
                clientName = clientName,
                message = e.message,
                cause = e
            )
        }
        if (openAIResponse.data.isEmpty()) {
            val exception = LLMClientException(clientName, "Empty data in OpenAI embedding response")
            logger.error(exception) { exception.message }
            throw exception
        }
        return openAIResponse.data.first().embedding
    }

    /**
     * Batch embedding is not supported by the OpenAI API.
     *
     * @throws UnsupportedOperationException Always thrown.
     */
    override suspend fun embed(inputs: List<String>, model: LLModel): List<List<Double>> {
        logger.warn { "Batch embedding is not supported by OpenAI API" }
        throw UnsupportedOperationException("Batch embedding is not supported by OpenAI API.")
    }

    /**
     * Moderates text and image content based on the provided model's capabilities.
     *
     * @param prompt The prompt containing text messages and optional attachments to be moderated.
     * @param model The language model to use for moderation. Must have the `Moderation` capability.
     * @return The moderation result, including flagged content, categories, scores, and associated metadata.
     * @throws IllegalArgumentException If the specified model does not support moderation.
     */
    public override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
        logger.debug { "Moderating text and image content with model: $model" }

        model.requireCapability(LLMCapability.Moderation)

        require(prompt.messages.isNotEmpty()) { "Can't moderate an empty prompt" }

        val input = prompt.messages
            .map { message ->
                require(message.parts.all { it is MessagePart.Text || (it is MessagePart.Attachment && it.source is AttachmentSource.Image) }) {
                    "Only image attachments are supported for moderation"
                }

                message.parts.filterIsInstance<MessagePart.Text>().toMessageContent(model)
            }
            .let { contents ->
                /*
                 If all messages contain only text, merge it all in a single text input,
                 to support OpenAI-compatible providers that do not support attachments.

                 Otherwise create a single content instance with all the parts
                 */
                if (contents.all { it is OpenAIContent.Text }) {
                    val text = contents.joinToString(separator = "\n\n") { (it as OpenAIContent.Text).value }

                    OpenAIContent.Text(text)
                } else {
                    val parts = contents.flatMap { content ->
                        when (content) {
                            is OpenAIContent.Parts -> content.value
                            is OpenAIContent.Text -> listOf(OpenAIContentPart.Text(content.value))
                        }
                    }

                    OpenAIContent.Parts(parts)
                }
            }

        val request = OpenAIModerationRequest(
            input = input,
            model = model.id
        )

        val openAIResponse = withContext(Dispatchers.SuitableForIO) {
            try {
                httpClient.post(
                    path = settings.moderationsPath,
                    requestBody = request,
                    requestBodyType = OpenAIModerationRequest::class,
                    responseType = OpenAIModerationResponse::class
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw LLMClientException(
                    clientName = clientName,
                    message = e.message,
                    cause = e
                )
            }
        }

        if (openAIResponse.results.isEmpty()) {
            val exception = LLMClientException(clientName, "Empty results in OpenAI moderation response")
            logger.error(exception) { exception.message }
            throw exception
        }
        val result = openAIResponse.results.first()

        // Convert OpenAI categories to a map
        return convertModerationResult(result)
    }

    /**
     * Retrieves the list of available models from OpenAI.
     * https://platform.openai.com/docs/api-reference/models/list
     *
     * @return A list of model identifiers available from OpenAI.
     */
    override suspend fun models(): List<LLModel> {
        logger.debug { "Fetching available models from OpenAI" }

        val models = httpClient.get(
            path = settings.modelsPath,
            responseType = OpenAIModelsResponse::class
        )

        val modelsById = OpenAIModels.modelsById()

        return models.data.map { modelsById[it.id] ?: LLModel(provider = llmProvider(), id = it.id) }
    }

    private fun convertModerationResult(result: OpenAIModerationResult): ModerationResult {
        // Convert OpenAI categories to a map
        val categories = mapOf(
            ModerationCategory.Harassment to result.categories.harassment,
            ModerationCategory.HarassmentThreatening to result.categories.harassmentThreatening,
            ModerationCategory.Hate to result.categories.hate,
            ModerationCategory.HateThreatening to result.categories.hateThreatening,
            ModerationCategory.Sexual to result.categories.sexual,
            ModerationCategory.SexualMinors to result.categories.sexualMinors,
            ModerationCategory.Violence to result.categories.violence,
            ModerationCategory.ViolenceGraphic to result.categories.violenceGraphic,
            ModerationCategory.SelfHarm to result.categories.selfHarm,
            ModerationCategory.SelfHarmIntent to result.categories.selfHarmIntent,
            ModerationCategory.SelfHarmInstructions to result.categories.selfHarmInstructions,
            ModerationCategory.Illicit to (result.categories.illicit ?: false),
            ModerationCategory.IllicitViolent to (result.categories.illicitViolent ?: false)
        )

        // Convert OpenAI category scores to a map
        val categoryScores = mapOf(
            ModerationCategory.Harassment to result.categoryScores.harassment,
            ModerationCategory.HarassmentThreatening to result.categoryScores.harassmentThreatening,
            ModerationCategory.Hate to result.categoryScores.hate,
            ModerationCategory.HateThreatening to result.categoryScores.hateThreatening,
            ModerationCategory.Sexual to result.categoryScores.sexual,
            ModerationCategory.SexualMinors to result.categoryScores.sexualMinors,
            ModerationCategory.Violence to result.categoryScores.violence,
            ModerationCategory.ViolenceGraphic to result.categoryScores.violenceGraphic,
            ModerationCategory.SelfHarm to result.categoryScores.selfHarm,
            ModerationCategory.SelfHarmIntent to result.categoryScores.selfHarmIntent,
            ModerationCategory.SelfHarmInstructions to result.categoryScores.selfHarmInstructions,
            ModerationCategory.Illicit to (result.categoryScores.illicit ?: 0.0),
            ModerationCategory.IllicitViolent to (result.categoryScores.illicitViolent ?: 0.0)
        )

        // Convert category applied input types if available
        val categoryAppliedInputTypes = result.categoryAppliedInputTypes?.let { appliedTypes ->
            buildMap {
                appliedTypes.harassment?.map { ModerationResult.InputType.valueOf(it.uppercase()) }
                    ?.let { put(ModerationCategory.Harassment, it) }
                appliedTypes.harassmentThreatening?.map {
                    ModerationResult.InputType.valueOf(it.uppercase())
                }
                    ?.let { put(ModerationCategory.HarassmentThreatening, it) }
                appliedTypes.hate?.map { ModerationResult.InputType.valueOf(it.uppercase()) }
                    ?.let { put(ModerationCategory.Hate, it) }
                appliedTypes.hateThreatening?.map { ModerationResult.InputType.valueOf(it.uppercase()) }
                    ?.let { put(ModerationCategory.HateThreatening, it) }
                appliedTypes.sexual?.map { ModerationResult.InputType.valueOf(it.uppercase()) }
                    ?.let { put(ModerationCategory.Sexual, it) }
                appliedTypes.sexualMinors?.map { ModerationResult.InputType.valueOf(it.uppercase()) }
                    ?.let { put(ModerationCategory.SexualMinors, it) }
                appliedTypes.violence?.map { ModerationResult.InputType.valueOf(it.uppercase()) }
                    ?.let { put(ModerationCategory.Violence, it) }
                appliedTypes.violenceGraphic?.map { ModerationResult.InputType.valueOf(it.uppercase()) }
                    ?.let { put(ModerationCategory.ViolenceGraphic, it) }
                appliedTypes.selfHarm?.map { ModerationResult.InputType.valueOf(it.uppercase()) }
                    ?.let { put(ModerationCategory.SelfHarm, it) }
                appliedTypes.selfHarmIntent?.map { ModerationResult.InputType.valueOf(it.uppercase()) }
                    ?.let { put(ModerationCategory.SelfHarmIntent, it) }
                appliedTypes.selfHarmInstructions?.map {
                    ModerationResult.InputType.valueOf(it.uppercase())
                }
                    ?.let { put(ModerationCategory.SelfHarmInstructions, it) }
                appliedTypes.illicit?.map { ModerationResult.InputType.valueOf(it.uppercase()) }
                    ?.let { put(ModerationCategory.Illicit, it) }
                appliedTypes.illicitViolent?.map { ModerationResult.InputType.valueOf(it.uppercase()) }
                    ?.let { put(ModerationCategory.IllicitViolent, it) }
            }
        } ?: emptyMap()

        return ModerationResult(
            isHarmful = result.flagged,
            categories = categories.mapValues { (category, detected) ->
                ModerationCategoryResult(
                    detected,
                    categoryScores[category],
                    categoryAppliedInputTypes[category] ?: emptyList()
                )
            }
        )
    }

    private data class ResponsesExecutionResult(
        val response: OpenAIResponsesAPIResponse,
        val recoveredContainerId: String? = null,
    )

    private suspend fun getResponseWithResponsesAPI(
        prompt: Prompt,
        params: OpenAIResponsesParams,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): ResponsesExecutionResult {
        logger.debug { "Executing prompt: $prompt with tools: $tools and model: $model" }

        if (tools.isNotEmpty() || params.codeInterpreter != null) {
            model.requireCapability(LLMCapability.Tools)
        }

        val llmTools = tools.takeIf { it.isNotEmpty() }?.map {
            Function(
                name = it.name,
                parameters = toolsConverter.generate(it),
                description = it.description
            )
        }

        val messages = convertPromptToResponsesMessages(prompt, model)

        suspend fun post(requestParams: OpenAIResponsesParams): OpenAIResponsesAPIResponse {
            val request = serializeResponsesAPIRequest(
                messages,
                model,
                llmTools,
                prompt.params.toolChoice?.toOpenAIResponseToolChoice(),
                requestParams,
                false
            )
            return httpClient.post(
                path = settings.responsesAPIPath,
                requestBody = request,
                requestBodyType = String::class,
                responseType = OpenAIResponsesAPIResponse::class
            )
        }

        return try {
            ResponsesExecutionResult(post(params))
        } catch (failure: Exception) {
            val recoveryParams = params.staleContainerRecoveryParams(failure, providerEventSeen = false)
                ?: throw failure
            ResponsesExecutionResult(
                response = post(recoveryParams),
                recoveredContainerId = params.codeInterpreter?.containerId,
            )
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun convertPromptToResponsesMessages(prompt: Prompt, model: LLModel): List<Item> {
        return buildList {
            prompt.messages.forEach { message ->
                when (message) {
                    is Message.System -> {
                        add(
                            Item.InputMessage(
                                role = "developer",
                                content = message.parts.map { InputContent.Text(it.text) }
                            )
                        )
                    }

                    is Message.User -> {
                        // First add tool results
                        message.parts.filterIsInstance<MessagePart.Tool.Result>().forEach { part ->
                            if (model.supports(LLMCapability.Tools)) {
                                val output: JsonElement = if (part.parts.size == 1 && part.parts.single() is MessagePart.Text) {
                                    JsonPrimitive((part.parts.single() as MessagePart.Text).text)
                                } else {
                                    buildJsonArray {
                                        part.parts.forEach { p ->
                                            when (p) {
                                                is MessagePart.Text -> add(
                                                    buildJsonObject {
                                                        put("type", "input_text")
                                                        put("text", p.text)
                                                    }
                                                )
                                                is MessagePart.Attachment -> {
                                                    when (val source = p.source) {
                                                        is AttachmentSource.Image -> {
                                                            val imageUrl = when (val c = source.content) {
                                                                is AttachmentContent.URL -> c.url
                                                                is AttachmentContent.Binary -> "data:${source.mimeType};base64,${c.asBase64()}"
                                                                else -> {
                                                                    logger.warn { "Unsupported image content type in tool result for OpenAI: ${c::class}, skipping" }
                                                                    null
                                                                }
                                                            }
                                                            if (imageUrl != null) {
                                                                add(
                                                                    buildJsonObject {
                                                                        put("type", "input_image")
                                                                        put("image_url", imageUrl)
                                                                        put("detail", "auto")
                                                                    }
                                                                )
                                                            }
                                                        }
                                                        is AttachmentSource.File -> {
                                                            when (val c = source.content) {
                                                                is AttachmentContent.Binary -> add(
                                                                    buildJsonObject {
                                                                        put("type", "input_file")
                                                                        put("file_data", "data:${source.mimeType};base64,${c.asBase64()}")
                                                                        source.fileName?.let { put("filename", it) }
                                                                    }
                                                                )
                                                                is AttachmentContent.URL -> add(
                                                                    buildJsonObject {
                                                                        put("type", "input_file")
                                                                        put("file_url", c.url)
                                                                        source.fileName?.let { put("filename", it) }
                                                                    }
                                                                )
                                                                else -> logger.warn { "Unsupported file content type in tool result for OpenAI: ${c::class}, skipping" }
                                                            }
                                                        }
                                                        else -> logger.warn { "Unsupported attachment type in tool result for OpenAI: ${source::class}, skipping" }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                add(
                                    Item.FunctionToolCallOutput(
                                        callId = part.id ?: Uuid.random().toString(),
                                        output = output,
                                        id = part.providerItemId,
                                    )
                                )
                            } else {
                                logger.debug { "Model does not support tools, ignoring tool result message" }
                            }
                        }

                        // Only after add user texts if not empty
                        message.parts.filterIsInstance<MessagePart.ContentPart>().let {
                            if (it.isNotEmpty()) {
                                add(
                                    Item.InputMessage(
                                        role = "user",
                                        content = it.toResponsesMessageContent(model)
                                    )
                                )
                            }
                        }
                    }

                    is Message.Assistant -> {
                        val codeInterpreterReplay = message.parts.canonicalOpenAICodeInterpreterReplay()
                        val emittedCodeInterpreterIds = mutableSetOf<String>()
                        message.parts.forEach { part ->
                            when (part) {
                                is MessagePart.ContentPart -> {
                                    when (part) {
                                        is MessagePart.Text -> {
                                            add(
                                                Item.OutputMessage(
                                                    content = listOf(
                                                        OutputContent.Text(
                                                            text = part.text,
                                                            annotations = part.generatedFileCitations.map {
                                                                it.toOpenAIAnnotation()
                                                            },
                                                        )
                                                    ),
                                                    id = part.providerItemId,
                                                )
                                            )
                                        }

                                        is MessagePart.Attachment -> {
                                            // TODO: implement
                                        }
                                    }
                                }

                                is MessagePart.Tool.Call -> {
                                    if (model.supports(LLMCapability.Tools)) {
                                        add(
                                            Item.FunctionToolCall(
                                                callId = part.id ?: Uuid.random().toString(),
                                                name = part.tool,
                                                // `args` already holds the JSON-encoded arguments; re-encoding here would
                                                // double-encode it into a quoted string that strict backends (e.g. DashScope) reject.
                                                arguments = part.args,
                                                id = part.providerItemId,
                                            )
                                        )
                                    } else {
                                        logger.debug { "Model does not support tools, ignoring tool call message" }
                                    }
                                }

                                is MessagePart.CodeExecution -> {
                                    model.requireCapability(LLMCapability.Tools)
                                    val replayId = part.openAIReplayId()
                                    if (emittedCodeInterpreterIds.add(replayId)) {
                                        addAll(codeInterpreterReplay.getValue(replayId))
                                    }
                                }

                                is MessagePart.Reasoning -> {
                                    if (model.supports(LLMCapability.Thinking)) {
                                        add(
                                            Item.Reasoning(
                                                id = part.providerItemId ?: part.id ?: Uuid.random().toString(),
                                                content = part.content.map { Item.Reasoning.Content(text = it) }
                                                    .ifEmpty { null },
                                                encryptedContent = part.encrypted,
                                                summary = part.summary?.map { Item.Reasoning.Summary(text = it) }
                                                    ?: emptyList(),
                                            )
                                        )
                                    } else {
                                        logger.debug { "Model does not support reasoning, ignoring reasoning message" }
                                    }
                                }

                                is MessagePart.GeneratedFile -> {
                                    add(part.toOpenAIItem())
                                }

                                is MessagePart.HostedExecution -> {
                                    val replayId = part.openAIReplayId()
                                    if (emittedCodeInterpreterIds.add(replayId)) {
                                        addAll(codeInterpreterReplay.getValue(replayId))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun List<MessagePart.ContentPart>.toResponsesMessageContent(model: LLModel): List<InputContent> {
        val contentParts = this
        return buildList {
            contentParts.forEach { part ->
                when (part) {
                    is MessagePart.Text -> {
                        add(InputContent.Text(part.text))
                    }

                    is MessagePart.Attachment -> {
                        when (val source = part.source) {
                            is AttachmentSource.Image -> {
                                model.requireCapability(LLMCapability.Vision.Image)

                                val imageUrl: String = when (val content = source.content) {
                                    is AttachmentContent.URL -> content.url
                                    is AttachmentContent.Binary -> "data:${source.mimeType};base64,${content.asBase64()}"
                                    else -> throw IllegalArgumentException("Unsupported image attachment content: ${content::class}")
                                }

                                add(InputContent.Image(imageUrl = imageUrl))
                            }

                            is AttachmentSource.File -> {
                                model.requireCapability(LLMCapability.Document)

                                val fileData = when (val content = source.content) {
                                    is AttachmentContent.Binary -> "data:${source.mimeType};base64,${content.asBase64()}"
                                    else -> null
                                }

                                val fileUrl = when (val content = source.content) {
                                    is AttachmentContent.URL -> content.url
                                    else -> null
                                }

                                add(
                                    InputContent.File(
                                        fileData = fileData,
                                        fileUrl = fileUrl,
                                        filename = source.fileName
                                    )
                                )
                            }

                            else -> throw LLMClientException(
                                clientName,
                                "Unsupported attachment type: $part, for model: $model with Responses API"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun processResponsesAPIResponse(
        response: OpenAIResponsesAPIResponse,
        recoveredContainerId: String? = null,
    ): Message.Assistant {
        require(response.output.isNotEmpty()) { "Empty output in response" }

        val metaInfo = ResponseMetaInfo.create(
            clock,
            totalTokensCount = response.usage?.totalTokens,
            inputTokensCount = response.usage?.inputTokens,
            outputTokensCount = response.usage?.outputTokens
        )

        var finishReason: String? = null

        val parts = buildList {
            recoveredContainerId?.let { staleContainerId ->
                add(
                    MessagePart.HostedExecution.Progress(
                        message = "stale_container_recovered",
                        executionId = staleContainerRecoveryExecutionId(staleContainerId),
                        containerId = staleContainerId,
                    )
                )
            }
            response.output.forEach { output ->
                when (output) {
                    is Item.Reasoning -> {
                        add(
                            MessagePart.Reasoning(
                                id = null,
                                encrypted = output.encryptedContent,
                                content = output.content?.map { it.text } ?: emptyList(),
                                summary = output.summary.map { it.text },
                                providerItemId = output.id,
                            )
                        )
                    }

                    is Item.OutputMessage -> {
                        finishReason = output.status?.name
                        output.content.forEach { content ->
                            when (content) {
                                is OutputContent.Text -> {
                                    add(
                                        MessagePart.Text(
                                            text = content.text,
                                            providerItemId = output.id,
                                            generatedFileCitations = content.annotations.mapNotNull {
                                                it.toGeneratedFileCitation(output.id)
                                            },
                                        )
                                    )
                                }

                                is OutputContent.Refusal -> {
                                    add(MessagePart.Text(content.refusal, providerItemId = output.id))
                                }
                            }
                        }
                    }

                    is Item.FunctionToolCall -> {
                        add(
                            MessagePart.Tool.Call(
                                id = output.callId,
                                tool = output.name,
                                args = output.arguments,
                                providerItemId = output.id,
                            )
                        )
                    }

                    is Item.CodeInterpreterToolCall -> {
                        add(output.toMessagePart())
                        addAll(output.toHostedExecutionParts())
                    }

                    else -> throw LLMClientException(
                        clientName,
                        "Unexpected response from $clientName: no tool calls and no content"
                    )
                }
            }
        }

        return Message.Assistant(
            parts = parts,
            finishReason = finishReason,
            metaInfo = metaInfo
        )
    }

    private fun MessagePart.CodeExecution.toOpenAIItem(): Item.CodeInterpreterToolCall =
        Item.CodeInterpreterToolCall(
            code = code,
            containerId = requireNotNull(containerId) {
                "OpenAI code execution replay requires a provider container ID"
            },
            id = providerItemId ?: id,
            outputs =
            outputs.map { output ->
                when (output) {
                    is MessagePart.CodeExecution.Output.Logs ->
                        Item.CodeInterpreterToolCall.Output.Logs(output.logs)
                    is MessagePart.CodeExecution.Output.Image ->
                        Item.CodeInterpreterToolCall.Output.Image(output.url)
                }
            },
            status =
            when (failure) {
                null -> OpenAIInputStatus.COMPLETED
                MessagePart.CodeExecution.Failure.FAILED -> OpenAIInputStatus.FAILED
                MessagePart.CodeExecution.Failure.INCOMPLETE -> OpenAIInputStatus.INCOMPLETE
            },
        )

    private fun Item.CodeInterpreterToolCall.toMessagePart(): MessagePart.CodeExecution =
        MessagePart.CodeExecution(
            id = "execution:$id",
            code = code.orEmpty(),
            containerId = containerId,
            outputs =
            outputs.orEmpty().map { output ->
                when (output) {
                    is Item.CodeInterpreterToolCall.Output.Logs ->
                        MessagePart.CodeExecution.Output.Logs(output.logs)
                    is Item.CodeInterpreterToolCall.Output.Image ->
                        MessagePart.CodeExecution.Output.Image(output.url)
                }
            },
            failure =
            when (status) {
                OpenAIInputStatus.FAILED -> MessagePart.CodeExecution.Failure.FAILED
                OpenAIInputStatus.INCOMPLETE -> MessagePart.CodeExecution.Failure.INCOMPLETE
                else -> null
            },
            providerItemId = id,
        )

    private fun Item.CodeInterpreterToolCall.toHostedExecutionParts(): List<MessagePart.HostedExecution> {
        val executionId = "execution:$id"
        val request = MessagePart.HostedExecution.Request(
            code = code.orEmpty(),
            executionId = executionId,
            containerId = containerId,
            providerItemId = id,
        )
        val terminal = when (status) {
            OpenAIInputStatus.FAILED,
            OpenAIInputStatus.INCOMPLETE -> MessagePart.HostedExecution.Error(
                message = requireNotNull(status).name.lowercase(),
                code = status.name.lowercase(),
                executionId = executionId,
                containerId = containerId,
                providerItemId = id,
            )

            else -> MessagePart.HostedExecution.Result(
                output = outputs.orEmpty().joinToString(separator = "") { output ->
                    when (output) {
                        is Item.CodeInterpreterToolCall.Output.Logs -> output.logs
                        is Item.CodeInterpreterToolCall.Output.Image -> output.url
                    }
                },
                executionId = executionId,
                containerId = containerId,
                providerItemId = id,
            )
        }
        return listOf(request, terminal)
    }

    private fun MessagePart.CodeExecution.openAIReplayId(): String = providerItemId ?: id

    private fun MessagePart.HostedExecution.openAIReplayId(): String = providerItemId ?: executionId
        ?: throw IllegalArgumentException("OpenAI hosted execution replay requires a provider item ID")

    private fun List<MessagePart.ResponsePart>.canonicalOpenAICodeInterpreterReplay(): Map<String, List<Item>> =
        filter { it is MessagePart.CodeExecution || it is MessagePart.HostedExecution }
            .groupBy { part ->
                when (part) {
                    is MessagePart.CodeExecution -> part.openAIReplayId()
                    is MessagePart.HostedExecution -> part.openAIReplayId()
                    else -> error("Only code execution parts are grouped")
                }
            }
            .mapValues { (wireId, parts) ->
                val codeExecution = parts.filterIsInstance<MessagePart.CodeExecution>().lastOrNull()
                val hostedParts = parts.filterIsInstance<MessagePart.HostedExecution>()
                val generatedFiles = hostedParts.filterIsInstance<MessagePart.HostedExecution.Result>()
                    .flatMap { it.generatedFiles }
                buildList {
                    if (codeExecution != null) {
                        add(codeExecution.toOpenAIItem())
                    } else {
                        val request = hostedParts.filterIsInstance<MessagePart.HostedExecution.Request>().lastOrNull()
                        val error = hostedParts.filterIsInstance<MessagePart.HostedExecution.Error>().lastOrNull()
                        val result = hostedParts.filterIsInstance<MessagePart.HostedExecution.Result>().lastOrNull()
                        val cumulativeOutput = hostedParts
                            .filterIsInstance<MessagePart.HostedExecution.CumulativeOutput>()
                            .lastOrNull()
                        val progress = hostedParts.filterIsInstance<MessagePart.HostedExecution.Progress>().lastOrNull()
                        val resultOutput = result?.output
                        val progressMessage = progress?.message
                        val output = when {
                            error != null -> listOf(Item.CodeInterpreterToolCall.Output.Logs(error.message))
                            resultOutput != null -> listOf(Item.CodeInterpreterToolCall.Output.Logs(resultOutput))
                            cumulativeOutput != null -> {
                                listOf(Item.CodeInterpreterToolCall.Output.Logs(cumulativeOutput.output))
                            }
                            progressMessage != null -> {
                                listOf(Item.CodeInterpreterToolCall.Output.Logs(progressMessage))
                            }
                            else -> emptyList()
                        }
                        add(
                            Item.CodeInterpreterToolCall(
                                code = request?.code,
                                containerId = requireNotNull(hostedParts.mapNotNull { it.containerId }.lastOrNull()) {
                                    "OpenAI hosted execution replay requires a provider container ID"
                                },
                                id = wireId,
                                outputs = output,
                                status = when {
                                    error != null -> OpenAIInputStatus.FAILED
                                    result != null -> OpenAIInputStatus.COMPLETED
                                    else -> OpenAIInputStatus.IN_PROGRESS
                                },
                            )
                        )
                    }
                    generatedFiles.forEach { add(it.toOpenAIItem()) }
                }
            }

    private fun MessagePart.GeneratedFile.toOpenAIItem(): Item.OutputMessage = Item.OutputMessage(
        content = listOf(
            OutputContent.Text(
                text = "",
                annotations = listOf(asCitation().toOpenAIAnnotation()),
            )
        ),
        id = providerItemId,
        status = OpenAIInputStatus.COMPLETED,
    )

    private fun MessagePart.GeneratedFile.asCitation(): MessagePart.GeneratedFileCitation =
        MessagePart.GeneratedFileCitation(
            providerFileId = providerFileId,
            containerId = containerId,
            filename = filename,
            mediaType = mediaType,
            sizeBytes = sizeBytes,
            producingExecutionId = producingExecutionId,
            providerItemId = providerItemId,
        )

    private fun MessagePart.GeneratedFileCitation.toOpenAIAnnotation(): OpenAIAnnotations = when {
        containerId != null && filename != null -> OpenAIAnnotations.ContainerFileCitation(
            containerId = requireNotNull(containerId),
            fileId = providerFileId,
            filename = requireNotNull(filename),
            startIndex = startIndex ?: 0,
            endIndex = endIndex ?: startIndex ?: 0,
        )
        filename != null -> OpenAIAnnotations.FileCitation(
            fileId = providerFileId,
            filename = requireNotNull(filename),
            index = startIndex ?: 0,
        )
        else -> OpenAIAnnotations.FilePath(fileId = providerFileId, index = startIndex ?: 0)
    }

    private fun OpenAIAnnotations.toGeneratedFileCitation(providerItemId: String?): MessagePart.GeneratedFileCitation? =
        when (this) {
            is OpenAIAnnotations.ContainerFileCitation -> MessagePart.GeneratedFileCitation(
                providerFileId = fileId,
                containerId = containerId,
                filename = filename,
                providerItemId = providerItemId,
                startIndex = startIndex,
                endIndex = endIndex,
            )
            is OpenAIAnnotations.FileCitation -> MessagePart.GeneratedFileCitation(
                providerFileId = fileId,
                filename = filename,
                providerItemId = providerItemId,
                startIndex = index,
            )
            is OpenAIAnnotations.FilePath -> MessagePart.GeneratedFileCitation(
                providerFileId = fileId,
                providerItemId = providerItemId,
                startIndex = index,
            )
            is OpenAIAnnotations.UrlCitation -> null
        }

    private fun JsonObject.toGeneratedFileCitation(providerItemId: String): MessagePart.GeneratedFileCitation? {
        fun string(name: String): String? = get(name)?.jsonPrimitive?.content
        fun integer(name: String): Int? = get(name)?.jsonPrimitive?.intOrNull
        return when (string("type")) {
            "container_file_citation" -> MessagePart.GeneratedFileCitation(
                providerFileId = string("file_id") ?: return null,
                containerId = string("container_id"),
                filename = string("filename"),
                providerItemId = providerItemId,
                startIndex = integer("start_index"),
                endIndex = integer("end_index"),
            )
            "file_citation" -> MessagePart.GeneratedFileCitation(
                providerFileId = string("file_id") ?: return null,
                filename = string("filename"),
                providerItemId = providerItemId,
                startIndex = integer("index"),
            )
            "file_path" -> MessagePart.GeneratedFileCitation(
                providerFileId = string("file_id") ?: return null,
                providerItemId = providerItemId,
                startIndex = integer("index"),
            )
            else -> null
        }
    }

    private fun LLMParams.ToolChoice.toOpenAIResponseToolChoice() = when (this) {
        LLMParams.ToolChoice.Auto -> OpenAIResponsesToolChoice.Mode("auto")
        LLMParams.ToolChoice.None -> OpenAIResponsesToolChoice.Mode("none")
        LLMParams.ToolChoice.Required -> OpenAIResponsesToolChoice.Mode("required")
        is LLMParams.ToolChoice.Named -> OpenAIResponsesToolChoice.FunctionTool(name = name)
    }

    internal fun determineParams(params: LLMParams, model: LLModel): OpenAIParams = when {
        params is OpenAIResponsesParams -> {
            settings.requireResponsesCapability()
            model.requireCapability(
                LLMCapability.OpenAIEndpoint.Responses,
                message = "Must be supported to use OpenAI responses params."
            )
            params
        }

        params is OpenAIChatParams -> {
            model.requireCapability(
                LLMCapability.OpenAIEndpoint.Completions,
                message = "Must be supported to use OpenAI chat params."
            )
            params
        }

        model.supports(LLMCapability.OpenAIEndpoint.Completions) -> params.toOpenAIChatParams()
        model.supports(LLMCapability.OpenAIEndpoint.Responses) -> {
            settings.requireResponsesCapability()
            params.toOpenAIResponsesParams()
        }
        else -> throw LLMClientException(clientName, "Cannot determine proper LLM params for OpenAI model: ${model.id}")
    }

    private fun OpenAIClientSettings.requireResponsesCapability() {
        when (val capability = responsesCapability) {
            OpenAIResponsesCapability.Supported -> Unit
            is OpenAIResponsesCapability.Unsupported -> throw OpenAIResponsesConfigurationException(capability.reason)
        }
    }

    private fun OpenAIResponsesParams.staleContainerRecoveryParams(
        failure: Throwable,
        providerEventSeen: Boolean,
    ): OpenAIResponsesParams? {
        val interpreter = codeInterpreter ?: return null
        val staleContainerId = interpreter.containerId ?: return null
        if (!stateless || providerEventSeen) return null
        val httpFailure = generateSequence(failure as Throwable?) { it.cause }
            .filterIsInstance<KoogHttpClientException>()
            .firstOrNull() ?: return null
        if (httpFailure.statusCode != 404) return null
        val providerError = httpFailure.errorBody?.let { body ->
            runCatching {
                val envelope = json.parseToJsonElement(body).jsonObject
                envelope["error"]?.jsonObject ?: envelope
            }.getOrNull()
        } ?: return null
        val errorCode = providerError["code"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val errorParam = providerError["param"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val identifiesContainer = errorCode in setOf("container_not_found", "invalid_container") &&
            errorParam?.contains("container") == true
        if (!identifiesContainer) return null
        return withCodeInterpreter(
            OpenAICodeInterpreterConfig(fileIds = interpreter.fileIds)
        )
    }

    private fun staleContainerRecoveryExecutionId(containerId: String): String =
        "stale-container-recovery:$containerId"

    private inline fun <T> selectExecutionStrategy(
        prompt: Prompt,
        model: LLModel,
        action: (OpenAIParams) -> T
    ): T = action(determineParams(prompt.params, model))
}
