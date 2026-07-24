package ai.koog.prompt.executor.clients.bedrock.converse

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.cache.PromptCachePolicy
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.bedrock.BedrockCacheControl
import ai.koog.prompt.executor.clients.bedrock.BedrockGuardrailsSettings
import ai.koog.prompt.executor.clients.bedrock.modelfamilies.BedrockToolSerialization
import ai.koog.prompt.executor.clients.bedrock.util.JsonDocumentConverters
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.CacheControl
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.MessagePart.ContentPart
import ai.koog.prompt.message.PromptCacheControl
import ai.koog.prompt.message.PromptCacheTtl
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.message.isClientManagedExecutionPresentation
import ai.koog.prompt.message.validateClientManagedExecutionPresentation
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.buildStreamFrameFlow
import ai.koog.utils.time.KoogClock
import aws.sdk.kotlin.services.bedrockruntime.model.AnyToolChoice
import aws.sdk.kotlin.services.bedrockruntime.model.AutoToolChoice
import aws.sdk.kotlin.services.bedrockruntime.model.CachePointBlock
import aws.sdk.kotlin.services.bedrockruntime.model.CachePointType
import aws.sdk.kotlin.services.bedrockruntime.model.CacheTtl
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockDelta
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockStart
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamOutput
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamRequest
import aws.sdk.kotlin.services.bedrockruntime.model.DocumentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.DocumentFormat
import aws.sdk.kotlin.services.bedrockruntime.model.DocumentSource
import aws.sdk.kotlin.services.bedrockruntime.model.GuardrailConfiguration
import aws.sdk.kotlin.services.bedrockruntime.model.GuardrailStreamConfiguration
import aws.sdk.kotlin.services.bedrockruntime.model.ImageBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ImageFormat
import aws.sdk.kotlin.services.bedrockruntime.model.ImageSource
import aws.sdk.kotlin.services.bedrockruntime.model.InferenceConfiguration
import aws.sdk.kotlin.services.bedrockruntime.model.JsonSchemaDefinition
import aws.sdk.kotlin.services.bedrockruntime.model.OutputConfig
import aws.sdk.kotlin.services.bedrockruntime.model.OutputFormat
import aws.sdk.kotlin.services.bedrockruntime.model.OutputFormatStructure
import aws.sdk.kotlin.services.bedrockruntime.model.OutputFormatType
import aws.sdk.kotlin.services.bedrockruntime.model.PerformanceConfiguration
import aws.sdk.kotlin.services.bedrockruntime.model.PromptVariableValues
import aws.sdk.kotlin.services.bedrockruntime.model.ReasoningContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ReasoningContentBlockDelta
import aws.sdk.kotlin.services.bedrockruntime.model.ReasoningTextBlock
import aws.sdk.kotlin.services.bedrockruntime.model.S3Location
import aws.sdk.kotlin.services.bedrockruntime.model.SpecificToolChoice
import aws.sdk.kotlin.services.bedrockruntime.model.SystemContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ToolConfiguration
import aws.sdk.kotlin.services.bedrockruntime.model.ToolInputSchema
import aws.sdk.kotlin.services.bedrockruntime.model.ToolResultBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ToolResultContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ToolSpecification
import aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock
import aws.sdk.kotlin.services.bedrockruntime.model.VideoBlock
import aws.sdk.kotlin.services.bedrockruntime.model.VideoFormat
import aws.sdk.kotlin.services.bedrockruntime.model.VideoSource
import aws.smithy.kotlin.runtime.content.Document
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import aws.sdk.kotlin.services.bedrockruntime.model.Message as BedrockMessage
import aws.sdk.kotlin.services.bedrockruntime.model.Tool as BedrockTool
import aws.sdk.kotlin.services.bedrockruntime.model.ToolChoice as BedrockToolChoice

internal object BedrockConverseConverters {
    private val logger = KotlinLogging.logger {}

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private fun CacheControl.toBedrockCachePointContentBlock(): ContentBlock? =
        toBedrockCachePointBlock()?.let { ContentBlock.CachePoint(it) }

    private fun CacheControl.toBedrockSystemCachePoint(): SystemContentBlock? =
        toBedrockCachePointBlock()?.let { SystemContentBlock.CachePoint(it) }

    private fun CacheControl.toBedrockCachePointBlock(): CachePointBlock? {
        val cacheTtl = when (this) {
            is PromptCacheControl -> when {
                !cacheable -> return null
                ttl == PromptCacheTtl.FiveMinutes -> CacheTtl.FiveMinutes
                else -> CacheTtl.OneHour
            }
            BedrockCacheControl.Default -> null
            BedrockCacheControl.FiveMinutes -> CacheTtl.FiveMinutes
            BedrockCacheControl.OneHour -> CacheTtl.OneHour
            else -> error("Unsupported Bedrock cache control: $this")
        }
        return CachePointBlock {
            type = CachePointType.Default
            ttl = cacheTtl
        }
    }

    private fun CacheControl.toPromptCacheMetadata(): PromptCacheControl? = when (this) {
        is PromptCacheControl -> this
        BedrockCacheControl.Default,
        BedrockCacheControl.FiveMinutes -> PromptCacheControl(cacheable = true)
        BedrockCacheControl.OneHour -> PromptCacheControl(cacheable = true, ttl = PromptCacheTtl.OneHour)
        else -> null
    }

    /**
     * Even though [ConverseRequest] and [ConverseStreamRequest] are structurally identical, they don't share a common
     * parent class. This class extracts common request parameters to avoid excessive code duplication.
     */
    private class ConverseRequestParams(
        val modelId: String,
        val inferenceConfig: InferenceConfiguration,
        val additionalModelRequestFields: Document?,
        val outputConfig: OutputConfig?,
        val performanceConfig: PerformanceConfiguration?,
        val promptVariables: Map<String, PromptVariableValues>?,
        val requestMetadata: Map<String, String>?,
        val toolConfig: ToolConfiguration?,
        val system: List<SystemContentBlock>,
        val messages: List<BedrockMessage>,
        val guardrailSettings: BedrockGuardrailsSettings?,
    )

    /**
     * Creates a common set of Converse API requests parameters.
     */
    private fun createConverseRequestParams(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
        guardrailSettings: BedrockGuardrailsSettings? = null,
    ): ConverseRequestParams {
        prompt.validateClientManagedExecutionPresentation()
        validateUnsupportedCacheMarkers(prompt)
        val params = prompt.params.toBedrockConverseParams()
        val toolBreakpoints = tools.mapNotNull { tool ->
            tool.cacheControl?.toPromptCacheMetadata()?.takeIf(PromptCacheControl::cacheable)?.ttl
        }
        val requestPrompt = PromptCachePolicy.requestView(
            prompt = prompt,
            leadingBreakpoints = toolBreakpoints,
            metadata = { it.toPromptCacheMetadata() },
        )

        val systemMessages = mutableListOf<SystemContentBlock>()
        val messages = mutableListOf<BedrockMessage>()

        // Convert Prompt messages to bedrock message formats
        requestPrompt.messages.forEach { message ->
            when (message) {
                is Message.System -> {
                    message.parts.forEach {
                        systemMessages.add(SystemContentBlock.Text(it.text))
                        it.cacheControl.takeIf { _ -> it.text.isNotBlank() }?.let { cc ->
                            cc.toBedrockSystemCachePoint()?.let(systemMessages::add)
                        }
                    }
                }

                is Message.User ->
                    messages.add(message.toBedrockMessage(model))

                is Message.Assistant ->
                    messages.add(message.toBedrockMessage(model))
            }
        }

        val outputConfig = params.schema?.let { schema ->
            require(schema is LLMParams.Schema.JSON) {
                "Bedrock Converse only supports JSON schemas for structured output"
            }
            OutputConfig {
                this.textFormat = OutputFormat {
                    this.type = OutputFormatType.JsonSchema
                    this.structure = OutputFormatStructure.JsonSchema(
                        JsonSchemaDefinition {
                            this.name = schema.name
                            this.schema = schema.schema.toString()
                        }
                    )
                }
            }
        }

        return ConverseRequestParams(
            modelId = model.id,
            inferenceConfig = InferenceConfiguration {
                this.maxTokens = params.maxTokens
                this.temperature = params.temperature
                    ?.takeIf { model.supports(LLMCapability.Temperature) }
                    ?.toFloat()
            },
            additionalModelRequestFields = params.toAdditionalModelRequestFields(model),
            outputConfig = outputConfig,
            performanceConfig = params.performanceConfig,
            promptVariables = params.promptVariables,
            requestMetadata = params.requestMetadata,
            toolConfig = if (tools.isNotEmpty()) {
                ToolConfiguration {
                    this.toolChoice = when (val toolChoice = params.toolChoice) {
                        is LLMParams.ToolChoice.Named ->
                            BedrockToolChoice.Tool(
                                SpecificToolChoice {
                                    this.name = toolChoice.name
                                }
                            )

                        is LLMParams.ToolChoice.None ->
                            throw IllegalArgumentException("Bedrock Converse API doesn't support 'none' tool choice.")

                        is LLMParams.ToolChoice.Auto ->
                            BedrockToolChoice.Auto(
                                AutoToolChoice {
                                    // no params in SDK
                                }
                            )

                        is LLMParams.ToolChoice.Required ->
                            BedrockToolChoice.Any(
                                AnyToolChoice {
                                    // no params in SDK
                                }
                            )

                        null -> null
                    }

                    this.tools = tools.flatMap { it.toConverseTools() }
                }
            } else {
                null
            },
            system = systemMessages,
            messages = messages,
            guardrailSettings = guardrailSettings,
        )
    }

    private fun validateUnsupportedCacheMarkers(prompt: Prompt) {
        prompt.messages.forEach { message ->
            message.parts.forEach { part ->
                when (part) {
                    is MessagePart.ContentPart,
                    is MessagePart.Tool.Call -> Unit
                    is MessagePart.Tool.Result -> if (part.parts.any { it.cacheControl != null }) {
                        throw IllegalArgumentException(
                            "Bedrock Converse cache control is unsupported inside tool-result content"
                        )
                    }
                    is MessagePart.Reasoning,
                    is MessagePart.CodeExecution,
                    is MessagePart.HostedExecution,
                    is MessagePart.GeneratedFile -> if (part.cacheControl != null) {
                        throw IllegalArgumentException(
                            "Bedrock Converse cache control is unsupported on this content type"
                        )
                    }
                }
            }
        }
    }

    private fun Message.User.toBedrockMessage(model: LLModel): BedrockMessage {
        return BedrockMessage {
            this.role = ConversationRole.User
            this.content = buildList {
                parts.forEach { part ->
                    when (part) {
                        is MessagePart.ContentPart -> {
                            add(part.toConverseContentBlock(model))
                            part.cacheControl.takeIf { _ -> part.isBedrockCacheableContent() }?.let { cc ->
                                cc.toBedrockCachePointContentBlock()?.let(::add)
                            }
                        }

                        is MessagePart.Tool.Result -> {
                            add(
                                ContentBlock.ToolResult(
                                    ToolResultBlock {
                                        this.toolUseId = requireNotNull(part.id) {
                                            "Bedrock tool-result replay requires a call ID"
                                        }
                                        this.content = part.parts.map { it.toToolResultContentBlock(model) }
                                    }
                                )
                            )
                            part.cacheControl.takeIf { _ -> part.hasBedrockCacheableContent() }?.let { cc ->
                                cc.toBedrockCachePointContentBlock()?.let(::add)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun Message.Assistant.toBedrockMessage(model: LLModel): BedrockMessage {
        return BedrockMessage {
            this.role = ConversationRole.Assistant
            this.content = buildList {
                parts.forEach { part ->
                    when (part) {
                        is MessagePart.ContentPart -> {
                            add(part.toConverseContentBlock(model))
                            part.cacheControl.takeIf { _ -> part.isBedrockCacheableContent() }?.let { cc ->
                                cc.toBedrockCachePointContentBlock()?.let(::add)
                            }
                        }
                        is MessagePart.Reasoning -> {
                            addAll(part.toBedrockReasoningContentBlocks())
                        }

                        is MessagePart.HostedExecution -> {
                            if (!part.isClientManagedExecutionPresentation()) {
                                throw IllegalArgumentException(
                                    "Bedrock Converse cannot replay provider-hosted execution items"
                                )
                            }
                        }

                        is MessagePart.GeneratedFile -> {
                            if (!part.isClientManagedExecutionPresentation()) {
                                throw IllegalArgumentException(
                                    "Bedrock Converse cannot replay provider-hosted execution items"
                                )
                            }
                        }

                        is MessagePart.CodeExecution -> {
                            throw IllegalArgumentException(
                                "Bedrock Converse cannot replay provider-hosted code execution items"
                            )
                        }

                        is MessagePart.Tool.Call -> {
                            add(
                                ContentBlock.ToolUse(
                                    ToolUseBlock {
                                        this.name = part.tool
                                        this.toolUseId = requireNotNull(part.id) {
                                            "Bedrock tool-call replay requires a call ID"
                                        }
                                        this.input = JsonDocumentConverters.convertToDocument(part.argsJson)
                                    }
                                )
                            )
                            part.cacheControl?.let { cc ->
                                cc.toBedrockCachePointContentBlock()?.let(::add)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun MessagePart.Reasoning.toBedrockReasoningContentBlocks(): List<ContentBlock> {
        if (replay.isNotEmpty()) {
            return replay.map { replayBlock ->
                when (replayBlock) {
                    is MessagePart.ReasoningReplay.Signed -> ContentBlock.ReasoningContent(
                        ReasoningContentBlock.ReasoningText(
                            ReasoningTextBlock {
                                text = replayBlock.text
                                signature = replayBlock.signature
                            }
                        )
                    )

                    is MessagePart.ReasoningReplay.OpaqueRedacted -> ContentBlock.ReasoningContent(
                        ReasoningContentBlock.RedactedContent(
                            try {
                                Base64.decode(replayBlock.data)
                            } catch (cause: IllegalArgumentException) {
                                throw IllegalArgumentException(
                                    "Bedrock redacted reasoning replay data must be valid base64",
                                    cause,
                                )
                            }
                        )
                    )
                }
            }
        }

        val replayText = when (content.size) {
            0 -> {
                require(encrypted != null) {
                    "Reasoning content may be empty only when an encrypted payload is present"
                }
                ""
            }

            1 -> content.single()
            else -> throw IllegalArgumentException("Reasoning content must have at most one part")
        }
        return listOf(
            ContentBlock.ReasoningContent(
                ReasoningContentBlock.ReasoningText(
                    ReasoningTextBlock {
                        text = replayText
                        signature = encrypted
                    }
                )
            )
        )
    }

    /**
     * Creates regular [ConverseRequest].
     */
    fun createConverseRequest(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
        guardrailSettings: BedrockGuardrailsSettings? = null,
    ): ConverseRequest {
        val params = createConverseRequestParams(prompt, model, tools, guardrailSettings)

        @Suppress("DuplicatedCode") // AWS SDK requires duplication
        return ConverseRequest {
            this.modelId = params.modelId
            this.inferenceConfig = params.inferenceConfig
            this.additionalModelRequestFields = params.additionalModelRequestFields
            this.outputConfig = params.outputConfig
            this.performanceConfig = params.performanceConfig
            this.promptVariables = params.promptVariables
            this.toolConfig = params.toolConfig
            this.system = params.system
            this.messages = params.messages
            params.guardrailSettings?.let { gs ->
                this.guardrailConfig = GuardrailConfiguration {
                    this.guardrailIdentifier = gs.guardrailIdentifier
                    this.guardrailVersion = gs.guardrailVersion
                }
            }
        }
    }

    /**
     * Creates [ConverseStreamRequest] for streaming response.
     */
    fun createConverseStreamRequest(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
        guardrailSettings: BedrockGuardrailsSettings? = null,
    ): ConverseStreamRequest {
        val params = createConverseRequestParams(prompt, model, tools, guardrailSettings)

        @Suppress("DuplicatedCode") // AWS SDK requires duplication
        return ConverseStreamRequest {
            this.modelId = params.modelId
            this.inferenceConfig = params.inferenceConfig
            this.additionalModelRequestFields = params.additionalModelRequestFields
            this.outputConfig = params.outputConfig
            this.performanceConfig = params.performanceConfig
            this.promptVariables = params.promptVariables
            this.toolConfig = params.toolConfig
            this.system = params.system
            this.messages = params.messages
            params.guardrailSettings?.let { gs ->
                this.guardrailConfig = GuardrailStreamConfiguration {
                    this.guardrailIdentifier = gs.guardrailIdentifier
                    this.guardrailVersion = gs.guardrailVersion
                }
            }
        }
    }

    /**
     * Converts [ConverseRequest] response.
     */
    fun convertConverseResponse(
        response: ConverseResponse,
        clock: KoogClock,
    ): Message.Assistant {
        // Extract token count from the response
        val inputTokensCount = response.usage?.inputTokens
        val outputTokensCount = response.usage?.outputTokens
        val totalTokensCount = response.usage?.totalTokens
        val cacheReadInputTokens = response.usage?.cacheReadInputTokens
        val cacheWriteInputTokens = response.usage?.cacheWriteInputTokens
        val cacheMetadata = buildJsonObject {
            cacheReadInputTokens?.let { put("cacheReadInputTokens", it) }
            cacheWriteInputTokens?.let { put("cacheWriteInputTokens", it) }
        }.takeIf { it.isNotEmpty() }
        val metaInfo = ResponseMetaInfo.create(
            clock,
            totalTokensCount = totalTokensCount,
            inputTokensCount = inputTokensCount,
            outputTokensCount = outputTokensCount,
            metadata = cacheMetadata,
        )

        val content = response.output?.asMessageOrNull()?.content.orEmpty()
        // Convert content blocks to messages
        val parts = content.map { contentBlock ->
            when (contentBlock) {
                is ContentBlock.ReasoningContent -> when (val reasoningContent = contentBlock.value) {
                    is ReasoningContentBlock.ReasoningText -> {
                        val reasoningBlock = reasoningContent.value

                        MessagePart.Reasoning(
                            encrypted = reasoningBlock.signature,
                            content = reasoningBlock.text.takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty(),
                            replay = listOf(
                                MessagePart.ReasoningReplay.Signed(
                                    text = reasoningBlock.text,
                                    signature = reasoningBlock.signature
                                        ?: throw LLMClientException(
                                            BEDROCK_CONVERSE_CLIENT_NAME,
                                            "Malformed signed reasoning block: signature is missing",
                                        ),
                                )
                            ),
                        )
                    }

                    is ReasoningContentBlock.RedactedContent -> MessagePart.Reasoning(
                        content = emptyList(),
                        replay = listOf(
                            MessagePart.ReasoningReplay.OpaqueRedacted(
                                encodeRedactedReasoning(reasoningContent.value)
                            )
                        ),
                    )
                    ReasoningContentBlock.SdkUnknown -> throw LLMClientException(
                        BEDROCK_CONVERSE_CLIENT_NAME,
                        "Unknown reasoning content type from Bedrock Converse API."
                    )
                }

                is ContentBlock.ToolUse -> {
                    val toolUseBlock = contentBlock.value

                    MessagePart.Tool.Call(
                        id = toolUseBlock.toolUseId,
                        tool = toolUseBlock.name,
                        args = JsonDocumentConverters.convertToJsonElement(toolUseBlock.input).jsonObject,
                    )
                }

                else -> contentBlock.toContentPart()
            }
        }

        return Message.Assistant(
            parts = parts,
            finishReason = response.stopReason.value,
            metaInfo = ResponseMetaInfo.create(
                clock,
                totalTokensCount = totalTokensCount,
                inputTokensCount = inputTokensCount,
                outputTokensCount = outputTokensCount,
                metadata = cacheMetadata,
            )
        )
    }

    /**
     * Transforms [ConverseStreamRequest] response stream.
     */
    fun transformConverseStreamChunks(
        chunkFlow: Flow<ConverseStreamOutput>,
        clock: KoogClock = KoogClock.System,
    ) = buildStreamFrameFlow {
        var finishReason: String? = null
        val reasoningBlockTypes = mutableMapOf<Int, String>()
        val reasoningTextByIndex = mutableMapOf<Int, String>()
        val reasoningSignatureByIndex = mutableMapOf<Int, String>()
        val redactedReasoningByIndex = mutableMapOf<Int, ByteArray>()

        chunkFlow.collect { chunk ->
            when (chunk) {
                is ConverseStreamOutput.MessageStart -> {
                    logger.debug { "Received start message from Converse" }
                }

                is ConverseStreamOutput.ContentBlockStart -> when (val start = chunk.value.start) {
                    is ContentBlockStart.ToolUse -> {
                        emitToolCallDelta(
                            index = chunk.value.contentBlockIndex,
                            id = start.value.toolUseId,
                            name = start.value.name,
                        )
                    }

                    null -> {
                        // skip
                    }

                    is ContentBlockStart.Image, is ContentBlockStart.ToolResult -> {
                        logger.warn { "Unsupported Converse content block start type: ${start::class.simpleName}" }
                    }

                    ContentBlockStart.SdkUnknown -> {
                        logger.warn { "Unknown Converse content block start type: ${start::class.simpleName}" }
                    }
                }

                is ConverseStreamOutput.ContentBlockDelta -> when (val delta = chunk.value.delta) {
                    is ContentBlockDelta.Text -> {
                        emitTextDelta(delta.value, index = chunk.value.contentBlockIndex)
                    }

                    is ContentBlockDelta.ToolUse -> {
                        emitToolCallDelta(
                            index = chunk.value.contentBlockIndex,
                            args = delta.value.input
                        )
                    }

                    is ContentBlockDelta.ReasoningContent -> when (val reasoning = delta.value) {
                        is ReasoningContentBlockDelta.Text -> {
                            val index = chunk.value.contentBlockIndex
                            reasoningBlockTypes[index] = "thinking"
                            reasoningTextByIndex[index] = reasoningTextByIndex[index].orEmpty() + reasoning.value
                            emitReasoningDelta(
                                text = reasoning.value,
                                index = index,
                            )
                        }

                        is ReasoningContentBlockDelta.Signature -> {
                            val index = chunk.value.contentBlockIndex
                            reasoningBlockTypes[index] = "thinking"
                            reasoningSignatureByIndex[index] = reasoning.value
                            attachReasoningEncrypted(
                                encrypted = reasoning.value,
                                index = index,
                            )
                        }

                        is ReasoningContentBlockDelta.RedactedContent -> {
                            val index = chunk.value.contentBlockIndex
                            reasoningBlockTypes[index] = "redacted_thinking"
                            redactedReasoningByIndex[index] =
                                (redactedReasoningByIndex[index] ?: byteArrayOf()) + reasoning.value
                        }
                        ReasoningContentBlockDelta.SdkUnknown -> throw LLMClientException(
                            BEDROCK_CONVERSE_CLIENT_NAME,
                            "Unknown reasoning content delta type from Bedrock Converse API."
                        )
                    }

                    is ContentBlockDelta.Citation -> {
                        logger.warn { "Unsupported Converse content block delta type: ${delta::class.simpleName}" }
                    }

                    is ContentBlockDelta.Image, is ContentBlockDelta.ToolResult -> {
                        logger.warn { "Unsupported Converse content block delta type: ${delta::class.simpleName}" }
                    }

                    null -> {
                        logger.warn { "null content block delta in Converse chunk" }
                    }

                    ContentBlockDelta.SdkUnknown -> {
                        logger.warn { "Unknown Converse content block delta type: ${delta::class.simpleName}" }
                    }
                }

                is ConverseStreamOutput.ContentBlockStop -> {
                    logger.debug { "Received content block stop from Converse" }
                    val index = chunk.value.contentBlockIndex
                    when (reasoningBlockTypes.remove(index)) {
                        "thinking" -> {
                            val signature = reasoningSignatureByIndex.remove(index)
                                ?: throw LLMClientException(
                                    BEDROCK_CONVERSE_CLIENT_NAME,
                                    "Malformed signed reasoning block: signature is missing",
                                )
                            attachReasoningReplay(
                                MessagePart.ReasoningReplay.Signed(
                                    text = reasoningTextByIndex.remove(index).orEmpty(),
                                    signature = signature,
                                ),
                                index = index,
                            )
                        }

                        "redacted_thinking" -> attachReasoningReplay(
                            MessagePart.ReasoningReplay.OpaqueRedacted(
                                encodeRedactedReasoning(requireNotNull(redactedReasoningByIndex.remove(index)))
                            ),
                            index = index,
                        )
                    }
                    tryEmitPendingReasoning()
                    tryEmitPendingText()
                    tryEmitPendingToolCall()
                }

                is ConverseStreamOutput.MessageStop -> {
                    finishReason = chunk.value.stopReason.value
                }

                is ConverseStreamOutput.Metadata -> {
                    val usage = chunk.value.usage

                    emitEnd(
                        finishReason = finishReason,
                        metaInfo = ResponseMetaInfo.create(
                            clock = clock,
                            totalTokensCount = usage?.totalTokens,
                            inputTokensCount = usage?.inputTokens,
                            outputTokensCount = usage?.outputTokens,
                            metadata = buildJsonObject {
                                usage?.cacheReadInputTokens?.let { put("cacheReadInputTokens", it) }
                                usage?.cacheWriteInputTokens?.let { put("cacheWriteInputTokens", it) }
                            }.takeIf { it.isNotEmpty() },
                        )
                    )
                }

                ConverseStreamOutput.SdkUnknown -> {
                    logger.warn { "Unknown Converse chunk type: ${chunk::class.simpleName}" }
                }
            }
        }
    }

    private fun BedrockConverseParams.toAdditionalModelRequestFields(model: LLModel): Document? {
        val thinkingConfig = thinking
            ?: return additionalProperties?.let { JsonDocumentConverters.convertToDocument(JsonObject(it)) }

        require(model.supports(LLMCapability.Thinking)) {
            "${model.id} doesn't support thinking"
        }
        require("thinking" !in additionalProperties.orEmpty()) {
            "additionalProperties must not contain 'thinking' when BedrockConverseParams.thinking is set"
        }
        require("output_config" !in additionalProperties.orEmpty()) {
            "additionalProperties must not contain 'output_config' when BedrockConverseParams.thinking is set"
        }

        val requestFields = buildJsonObject {
            additionalProperties.orEmpty().forEach { (key, value) -> put(key, value) }
            when (thinkingConfig) {
                is BedrockThinkingConfig.Adaptive -> {
                    put(
                        "thinking",
                        buildJsonObject {
                            put("type", "adaptive")
                            put("display", thinkingConfig.display.name.lowercase())
                        }
                    )
                    put(
                        "output_config",
                        buildJsonObject {
                            put("effort", thinkingConfig.effort.name.lowercase())
                        }
                    )
                }
            }
        }
        return JsonDocumentConverters.convertToDocument(requestFields)
    }

    private fun encodeRedactedReasoning(data: ByteArray): String {
        if (data.isEmpty()) {
            throw LLMClientException(
                BEDROCK_CONVERSE_CLIENT_NAME,
                "Malformed redacted reasoning block: data is empty",
            )
        }
        return Base64.encode(data)
    }

    private const val BEDROCK_CONVERSE_CLIENT_NAME = "Bedrock Converse"

    /**
     * Converts a [ContentPart] to [ContentBlock] for Bedrock Converse API.
     * Some [ContentPart] might be not supported.
     *
     * @throws IllegalArgumentException if the given [ContentPart] is not supported.
     */
    private fun MessagePart.ContentPart.toConverseContentBlock(model: LLModel): ContentBlock {
        return when (val part = this) {
            is MessagePart.Text ->
                ContentBlock.Text(text)

            is MessagePart.Attachment -> {
                when (val source = part.source) {
                    is AttachmentSource.Audio ->
                        throw IllegalArgumentException("Bedrock Converse API doesn't support audio content.")

                    is AttachmentSource.File -> {
                        require(model.supports(LLMCapability.Document)) {
                            "${model.id} doesn't support documents"
                        }

                        ContentBlock.Document(
                            DocumentBlock {
                                this.format = DocumentFormat.fromValue(source.format)
                                // Converse API requires no extension in file names
                                this.name = source.fileName?.substringBefore('.')

                                this.source = when (val content = source.content) {
                                    is AttachmentContent.Binary.Base64, is AttachmentContent.Binary.Bytes ->
                                        DocumentSource.Bytes(content.asBytes())

                                    is AttachmentContent.URL ->
                                        DocumentSource.S3Location(content.toS3Location())

                                    is AttachmentContent.PlainText ->
                                        // Even though DocumentSource.Text exists, Converse API requires bytes or s3 uri here
                                        DocumentSource.Bytes(content.text.encodeToByteArray())
                                }
                            }
                        )
                    }

                    is AttachmentSource.Image -> {
                        require(model.supports(LLMCapability.Vision.Image)) {
                            "${model.id} doesn't support images"
                        }

                        ContentBlock.Image(
                            ImageBlock {
                                this.format = ImageFormat.fromValue(source.format)

                                this.source = when (val content = source.content) {
                                    is AttachmentContent.Binary.Base64, is AttachmentContent.Binary.Bytes ->
                                        ImageSource.Bytes(content.asBytes())

                                    is AttachmentContent.URL ->
                                        ImageSource.S3Location(content.toS3Location())

                                    is AttachmentContent.PlainText ->
                                        throw IllegalArgumentException("Image can't have plain text content")
                                }
                            }
                        )
                    }

                    is AttachmentSource.Video -> {
                        require(model.supports(LLMCapability.Vision.Video)) {
                            "${model.id} doesn't support videos"
                        }

                        ContentBlock.Video(
                            VideoBlock {
                                this.format = VideoFormat.fromValue(source.format)

                                this.source = when (val content = source.content) {
                                    is AttachmentContent.Binary.Base64, is AttachmentContent.Binary.Bytes ->
                                        VideoSource.Bytes(content.asBytes())

                                    is AttachmentContent.URL ->
                                        VideoSource.S3Location(content.toS3Location())

                                    is AttachmentContent.PlainText ->
                                        throw IllegalArgumentException("Video can't have plain text content")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * Converts a [MessagePart.ContentPart] to [ToolResultContentBlock] for Bedrock Converse API tool results.
     *
     * @throws IllegalArgumentException if the given part is not supported in tool results.
     */
    private fun MessagePart.ContentPart.toToolResultContentBlock(model: LLModel): ToolResultContentBlock {
        return when (val part = this) {
            is MessagePart.Text -> ToolResultContentBlock.Text(part.text)
            is MessagePart.Attachment -> {
                when (val source = part.source) {
                    is AttachmentSource.Audio ->
                        throw IllegalArgumentException("Bedrock Converse API doesn't support audio content in tool results.")
                    is AttachmentSource.File ->
                        ToolResultContentBlock.Document(
                            (part as MessagePart.ContentPart).let {
                                DocumentBlock {
                                    this.format = DocumentFormat.fromValue(source.format)
                                    this.name = source.fileName?.substringBefore('.')
                                    this.source = when (val content = source.content) {
                                        is AttachmentContent.Binary.Base64, is AttachmentContent.Binary.Bytes ->
                                            DocumentSource.Bytes(content.asBytes())
                                        is AttachmentContent.URL ->
                                            DocumentSource.S3Location(content.toS3Location())
                                        is AttachmentContent.PlainText ->
                                            DocumentSource.Bytes(content.text.encodeToByteArray())
                                    }
                                }
                            }
                        )
                    is AttachmentSource.Image -> {
                        require(model.supports(LLMCapability.Vision.Image)) { "${model.id} doesn't support images" }
                        ToolResultContentBlock.Image(
                            ImageBlock {
                                this.format = ImageFormat.fromValue(source.format)
                                this.source = when (val content = source.content) {
                                    is AttachmentContent.Binary.Base64, is AttachmentContent.Binary.Bytes ->
                                        ImageSource.Bytes(content.asBytes())
                                    is AttachmentContent.URL ->
                                        ImageSource.S3Location(content.toS3Location())
                                    is AttachmentContent.PlainText ->
                                        throw IllegalArgumentException("Image can't have plain text content")
                                }
                            }
                        )
                    }
                    is AttachmentSource.Video -> {
                        require(model.supports(LLMCapability.Vision.Video)) { "${model.id} doesn't support videos" }
                        ToolResultContentBlock.Video(
                            VideoBlock {
                                this.format = VideoFormat.fromValue(source.format)
                                this.source = when (val content = source.content) {
                                    is AttachmentContent.Binary.Base64, is AttachmentContent.Binary.Bytes ->
                                        VideoSource.Bytes(content.asBytes())
                                    is AttachmentContent.URL ->
                                        VideoSource.S3Location(content.toS3Location())
                                    is AttachmentContent.PlainText ->
                                        throw IllegalArgumentException("Video can't have plain text content")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * Converts a [ContentBlock] from Bedrock Converse API to [ContentPart].
     * Some [ContentBlock] might be not supported.
     * Some [ContentBlock] correspond to [Message], e.g. tool calls and reasoning, so these are handled separately
     * in the message conversion logic.
     *
     * @throws IllegalArgumentException if the given [ContentBlock] is not supported.
     */
    private fun ContentBlock.toContentPart(): MessagePart.ContentPart {
        return when (val block = this) {
            is ContentBlock.Text ->
                MessagePart.Text(block.value)

            is ContentBlock.Document -> {
                val content = when (val source = block.value.source) {
                    is DocumentSource.Bytes ->
                        AttachmentContent.Binary.Bytes(source.value)

                    is DocumentSource.S3Location ->
                        AttachmentContent.URL(source.value.uri)

                    is DocumentSource.Text ->
                        AttachmentContent.PlainText(source.value)

                    else ->
                        throw IllegalArgumentException("Unsupported document source type from Bedrock Converse API: $source")
                }

                val format = block.value.format.value

                MessagePart.Attachment(
                    source = AttachmentSource.File(
                        content = content,
                        fileName = "${block.value.name}.$format",
                        format = format,
                        mimeType = "" // Bedrock Converse API doesn't have mime type
                    )
                )
            }

            is ContentBlock.Image -> {
                val content = when (val source = block.value.source) {
                    is ImageSource.Bytes ->
                        AttachmentContent.Binary.Bytes(source.value)

                    is ImageSource.S3Location ->
                        AttachmentContent.URL(source.value.uri)

                    else ->
                        throw IllegalArgumentException("Unsupported image source type from Bedrock Converse API: $source")
                }

                MessagePart.Attachment(
                    AttachmentSource.Image(
                        content = content,
                        format = block.value.format.value,
                        fileName = "", // Bedrock Converse API doesn't have file name for images
                        mimeType = "" // Bedrock Converse API doesn't have mime type
                    )
                )
            }

            is ContentBlock.Video -> {
                val content = when (val source = block.value.source) {
                    is VideoSource.Bytes ->
                        AttachmentContent.Binary.Bytes(source.value)

                    is VideoSource.S3Location ->
                        AttachmentContent.URL(source.value.uri)

                    else ->
                        throw IllegalArgumentException("Unsupported video source type from Bedrock Converse API: $source")
                }

                MessagePart.Attachment(
                    AttachmentSource.Video(
                        content = content,
                        format = block.value.format.value,
                        fileName = "", // Bedrock Converse API doesn't have file name for videos
                        mimeType = "" // Bedrock Converse API doesn't have mime type
                    )
                )
            }

            else ->
                throw IllegalArgumentException("Unsupported content block type from Bedrock Converse API: $block")
        }
    }

    /**
     * Helper function to convert URLs in attachment contents to S3 locations.
     * Performs a check if URL is indeed a valid S3 uri.
     */
    private fun AttachmentContent.URL.toS3Location(): S3Location {
        require(url.startsWith("s3://")) {
            "Only S3 locations are supported when URL attachment content is used with Bedrock Converse API."
        }

        return S3Location {
            this.uri = url
        }
    }

    /**
     * Convert [ToolDescriptor] to list of [BedrockTool], including cache point if specified.
     */
    private fun ToolDescriptor.toConverseTools(): List<BedrockTool> {
        val tool = this

        val toolSpec = BedrockTool.ToolSpec(
            ToolSpecification {
                val inputSchema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            (tool.requiredParameters + tool.optionalParameters).forEach { param ->
                                put(
                                    param.name,
                                    BedrockToolSerialization.buildToolParameterSchema(param)
                                )
                            }
                        }
                    )
                    put(
                        "required",
                        buildJsonArray {
                            tool.requiredParameters.forEach { param ->
                                add(param.name)
                            }
                        }
                    )
                }

                this.name = tool.name
                this.description = tool.description
                this.inputSchema = ToolInputSchema.Json(JsonDocumentConverters.convertToDocument(inputSchema))
            }
        )

        return listOfNotNull(
            toolSpec,
            tool.cacheControl?.toBedrockCachePointBlock()?.let { BedrockTool.CachePoint(it) }
        )
    }

    private fun MessagePart.isBedrockCacheableContent(): Boolean = when (this) {
        is MessagePart.Text -> text.isNotBlank()
        is MessagePart.Attachment -> true
        else -> false
    }

    private fun MessagePart.Tool.Result.hasBedrockCacheableContent(): Boolean =
        parts.any { it.isBedrockCacheableContent() }
}
