package ai.koog.prompt.executor.clients.bedrock.modelfamilies.anthropic

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicContent
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicStreamDeltaContentType
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicStreamEventType
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicStreamResponse
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicUsage
import ai.koog.prompt.executor.clients.bedrock.modelfamilies.BedrockAnthropicInvokeModel
import ai.koog.prompt.executor.clients.bedrock.modelfamilies.BedrockAnthropicInvokeModelContent
import ai.koog.prompt.executor.clients.bedrock.modelfamilies.BedrockAnthropicInvokeModelMessage
import ai.koog.prompt.executor.clients.bedrock.modelfamilies.BedrockAnthropicInvokeModelTool
import ai.koog.prompt.executor.clients.bedrock.modelfamilies.BedrockAnthropicResponse
import ai.koog.prompt.executor.clients.bedrock.modelfamilies.BedrockAnthropicToolChoice
import ai.koog.prompt.executor.clients.bedrock.modelfamilies.BedrockToolSerialization
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.message.isClientManagedExecutionPresentation
import ai.koog.prompt.message.validateClientManagedExecutionPresentation
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.buildStreamFrameFlow
import ai.koog.utils.time.KoogClock
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

internal object BedrockAnthropicClaudeSerialization {

    private val logger = KotlinLogging.logger {}

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    private fun Message.User.toAnthropicMessage(): BedrockAnthropicInvokeModelMessage.User {
        return BedrockAnthropicInvokeModelMessage.User(
            content = parts.map { part ->
                when (part) {
                    is MessagePart.Text -> BedrockAnthropicInvokeModelContent.Text(text = part.text)
                    is MessagePart.Attachment -> throw IllegalArgumentException("No attachments are supported in user messages")
                    is MessagePart.Tool.Result -> BedrockAnthropicInvokeModelContent.ToolResult(
                        toolUseId = part.id!!,
                        content = part.parts.map { p ->
                            require(p is MessagePart.Text) {
                                "Bedrock InvokeModel (legacy) path only supports text content in tool results, got: ${p::class}"
                            }
                            BedrockAnthropicInvokeModelContent.Text(p.text)
                        },
                        isError = part.isError
                    )
                }
            }
        )
    }

    private fun Message.Assistant.toAnthropicMessage(): BedrockAnthropicInvokeModelMessage.Assistant {
        return BedrockAnthropicInvokeModelMessage.Assistant(
            content = parts.flatMap { part ->
                when (part) {
                    is MessagePart.Text -> listOf(
                        BedrockAnthropicInvokeModelContent.Text(text = part.text)
                    )

                    is MessagePart.Reasoning -> part.toBedrockReasoningBlocks()

                    is MessagePart.Tool.Call -> listOf(
                        BedrockAnthropicInvokeModelContent.ToolCall(
                            part.id!!,
                            part.tool,
                            part.argsJson
                        )
                    )

                    is MessagePart.CodeExecution ->
                        throw IllegalArgumentException(
                            "Bedrock Anthropic cannot replay provider-hosted code execution items"
                        )

                    is MessagePart.HostedExecution ->
                        if (part.isClientManagedExecutionPresentation()) {
                            emptyList()
                        } else {
                            throw IllegalArgumentException(
                                "Bedrock Anthropic cannot replay provider-hosted execution items"
                            )
                        }

                    is MessagePart.GeneratedFile ->
                        if (part.isClientManagedExecutionPresentation()) {
                            emptyList()
                        } else {
                            throw IllegalArgumentException(
                                "Bedrock Anthropic cannot replay provider-hosted execution items"
                            )
                        }

                    is MessagePart.Attachment -> throw IllegalArgumentException("No attachments are supported in assistant messages")
                }
            }
        )
    }

    private fun MessagePart.Reasoning.toBedrockReasoningBlocks(): List<BedrockAnthropicInvokeModelContent> {
        if (replay.isNotEmpty()) {
            return replay.map { replayBlock ->
                when (replayBlock) {
                    is MessagePart.ReasoningReplay.Signed -> BedrockAnthropicInvokeModelContent.Thinking(
                        signature = replayBlock.signature,
                        thinking = replayBlock.text,
                    )

                    is MessagePart.ReasoningReplay.OpaqueRedacted ->
                        BedrockAnthropicInvokeModelContent.RedactedThinking(replayBlock.data)
                }
            }
        }

        val thinking = when (content.size) {
            0 -> ""
            1 -> content.single()
            else -> throw IllegalArgumentException("Reasoning content must have at most one part")
        }
        return listOf(
            BedrockAnthropicInvokeModelContent.Thinking(
                signature = encrypted
                    ?: throw IllegalArgumentException("Encrypted signature is required for reasoning messages but was null"),
                thinking = thinking,
            )
        )
    }

    internal fun createAnthropicRequest(
        prompt: Prompt,
        tools: List<ToolDescriptor>
    ): BedrockAnthropicInvokeModel {
        prompt.validateClientManagedExecutionPresentation()
        val systemMessages = mutableListOf<Message.System>()
        val messages = buildList {
            prompt.messages.forEach { message ->
                when (message) {
                    is Message.System -> systemMessages.add(message)
                    is Message.User -> add(message.toAnthropicMessage())
                    is Message.Assistant -> add(message.toAnthropicMessage())
                }
            }
        }
        val systemText = systemMessages.flatMap { it.parts.map { part -> part.text } }.joinToString("\n")

        val params: LLMParams = prompt.params
        val temperature = params.temperature
        val maxTokens = params.maxTokens ?: 4000

        val bedrockTools = if (tools.isNotEmpty()) {
            tools.map { tool ->
                BedrockAnthropicInvokeModelTool(
                    name = tool.name,
                    description = tool.description,
                    inputSchema = buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                (tool.requiredParameters + tool.optionalParameters).forEach { param ->
                                    put(param.name, BedrockToolSerialization.buildToolParameterSchema(param))
                                }
                            }
                        )
                        put(
                            "required",
                            buildJsonArray {
                                tool.requiredParameters.forEach { param ->
                                    add(json.encodeToJsonElement(param.name))
                                }
                            }
                        )
                    }
                )
            }
        } else {
            null
        }

        val bedrockToolChoice = if (tools.isNotEmpty()) {
            when (val choice = params.toolChoice) {
                LLMParams.ToolChoice.Auto -> BedrockAnthropicToolChoice(type = "auto")
                LLMParams.ToolChoice.None -> BedrockAnthropicToolChoice(type = "none")
                LLMParams.ToolChoice.Required -> BedrockAnthropicToolChoice(type = "any")
                is LLMParams.ToolChoice.Named -> BedrockAnthropicToolChoice(type = "tool", name = choice.name)
                null -> null
            }
        } else {
            null
        }

        val outputConfig = params.schema?.let { schema ->
            require(schema is LLMParams.Schema.JSON) {
                "Bedrock Anthropic only supports JSON schemas for structured output"
            }
            buildJsonObject {
                put(
                    "format",
                    buildJsonObject {
                        put("type", "json_schema")
                        put("schema", schema.schema)
                    }
                )
            }
        }

        return BedrockAnthropicInvokeModel(
            anthropicVersion = "bedrock-2023-05-31",
            maxTokens = maxTokens,
            system = systemText,
            temperature = temperature,
            messages = messages,
            tools = bedrockTools,
            toolChoice = bedrockToolChoice,
            outputConfig = outputConfig
        )
    }

    internal fun parseAnthropicResponse(responseBody: String, clock: KoogClock = KoogClock.System): Message.Assistant {
        val response = decodeAnthropicResponse(responseBody)

        val inputTokens = response.usage?.inputTokens
        val outputTokens = response.usage?.outputTokens
        val totalTokens = inputTokens?.let { input -> outputTokens?.let { output -> input + output } }
        val metaInfo = ResponseMetaInfo.create(
            clock,
            totalTokensCount = totalTokens,
            inputTokensCount = inputTokens,
            outputTokensCount = outputTokens
        )

        return Message.Assistant(
            parts = response.content.map { content ->
                when (content) {
                    is AnthropicContent.Text -> MessagePart.Text(
                        text = content.text,
                    )

                    is AnthropicContent.Thinking -> MessagePart.Reasoning(
                        content = content.thinking.takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty(),
                        encrypted = content.signature,
                        replay = listOf(
                            MessagePart.ReasoningReplay.Signed(
                                text = content.thinking,
                                signature = content.signature,
                            )
                        ),
                    )

                    is AnthropicContent.RedactedThinking -> MessagePart.Reasoning(
                        content = emptyList(),
                        replay = listOf(MessagePart.ReasoningReplay.OpaqueRedacted(content.data)),
                    )

                    is AnthropicContent.ToolUse -> MessagePart.Tool.Call(
                        id = content.id,
                        tool = content.name,
                        args = content.input
                    )

                    else -> throw IllegalArgumentException("Unhandled AnthropicContent type. Content: $content")
                }
            },
            metaInfo = metaInfo,
            finishReason = response.stopReason,
        )
    }

    internal fun transformAnthropicStreamChunks(
        chunkJsonStringFlow: Flow<String>,
        clock: KoogClock = KoogClock.System
    ): Flow<StreamFrame> = buildStreamFrameFlow {
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

        chunkJsonStringFlow.collect { chunkJsonString ->
            val response = decodeAnthropicStreamResponse(chunkJsonString)

            when (response.type) {
                AnthropicStreamEventType.MESSAGE_START.value -> {
                    response.message?.usage?.let(::updateUsage)
                }

                AnthropicStreamEventType.CONTENT_BLOCK_START.value -> {
                    val index = response.index
                        ?: throw LLMClientException(BEDROCK_ANTHROPIC_CLIENT_NAME, "Content block index is missing")
                    when (val contentBlock = response.contentBlock) {
                        is AnthropicContent.Text -> {
                            activeBlockTypes[index] = "text"
                            emitTextDelta(contentBlock.text, index = index)
                        }

                        is AnthropicContent.ToolUse -> {
                            activeBlockTypes[index] = "tool_use"
                            emitToolCallDelta(
                                index = index,
                                id = contentBlock.id,
                                name = contentBlock.name,
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
                                emitReasoningDelta(contentBlock.thinking, index = index)
                            }
                        }

                        is AnthropicContent.RedactedThinking -> {
                            activeBlockTypes[index] = "redacted_thinking"
                            attachReasoningReplay(
                                MessagePart.ReasoningReplay.OpaqueRedacted(contentBlock.data),
                                index = index,
                            )
                        }

                        null -> throw LLMClientException(
                            BEDROCK_ANTHROPIC_CLIENT_NAME,
                            "Anthropic stream content block is missing",
                        )

                        else -> throw LLMClientException(
                            BEDROCK_ANTHROPIC_CLIENT_NAME,
                            "Unsupported Anthropic stream content block type: ${contentBlock::class}",
                        )
                    }
                }

                AnthropicStreamEventType.CONTENT_BLOCK_DELTA.value -> {
                    response.delta?.let { delta ->
                        // Handles deltas for tool calls and text

                        when (delta.type) {
                            AnthropicStreamDeltaContentType.INPUT_JSON_DELTA.value -> {
                                emitToolCallDelta(
                                    index = response.index
                                        ?: throw LLMClientException(
                                            BEDROCK_ANTHROPIC_CLIENT_NAME,
                                            "Tool index is missing",
                                        ),
                                    args = delta.partialJson
                                        ?: throw LLMClientException(
                                            BEDROCK_ANTHROPIC_CLIENT_NAME,
                                            "Tool args are missing",
                                        )
                                )
                            }

                            AnthropicStreamDeltaContentType.TEXT_DELTA.value -> {
                                emitTextDelta(
                                    delta.text
                                        ?: throw LLMClientException(
                                            BEDROCK_ANTHROPIC_CLIENT_NAME,
                                            "Text delta is missing",
                                        ),
                                    index = response.index,
                                )
                            }

                            AnthropicStreamDeltaContentType.THINKING_DELTA.value -> {
                                val index = response.index
                                    ?: throw LLMClientException(
                                        BEDROCK_ANTHROPIC_CLIENT_NAME,
                                        "Reasoning index is missing",
                                    )
                                val thinking = delta.thinking
                                    ?: throw LLMClientException(
                                        BEDROCK_ANTHROPIC_CLIENT_NAME,
                                        "Reasoning delta is missing",
                                    )
                                reasoningTextByIndex[index] = reasoningTextByIndex[index].orEmpty() + thinking
                                emitReasoningDelta(thinking, index = index)
                            }

                            AnthropicStreamDeltaContentType.SIGNATURE_DELTA.value -> {
                                val index = response.index
                                    ?: throw LLMClientException(
                                        BEDROCK_ANTHROPIC_CLIENT_NAME,
                                        "Reasoning index is missing",
                                    )
                                val signature = delta.signature
                                    ?: throw LLMClientException(
                                        BEDROCK_ANTHROPIC_CLIENT_NAME,
                                        "Reasoning signature delta is missing",
                                    )
                                reasoningSignatureByIndex[index] = signature
                                attachReasoningEncrypted(signature, index = index)
                            }

                            else -> throw LLMClientException(
                                BEDROCK_ANTHROPIC_CLIENT_NAME,
                                "Unsupported Anthropic stream delta type: ${delta.type}",
                            )
                        }
                    } ?: throw LLMClientException(BEDROCK_ANTHROPIC_CLIENT_NAME, "Anthropic stream delta is missing")
                }

                AnthropicStreamEventType.CONTENT_BLOCK_STOP.value -> {
                    val index = response.index
                        ?: throw LLMClientException(BEDROCK_ANTHROPIC_CLIENT_NAME, "Content block stop index is missing")
                    when (activeBlockTypes.remove(index)) {
                        "text" -> tryEmitPendingText()
                        "tool_use" -> tryEmitPendingToolCall()
                        "thinking" -> {
                            val signature = reasoningSignatureByIndex.remove(index)
                                ?: throw LLMClientException(
                                    BEDROCK_ANTHROPIC_CLIENT_NAME,
                                    "Malformed Anthropic thinking block: signature is missing",
                                )
                            attachReasoningReplay(
                                MessagePart.ReasoningReplay.Signed(
                                    reasoningTextByIndex.remove(index).orEmpty(),
                                    signature,
                                ),
                                index = index,
                            )
                            tryEmitPendingReasoning()
                        }
                        "redacted_thinking" -> tryEmitPendingReasoning()
                        null -> throw LLMClientException(
                            BEDROCK_ANTHROPIC_CLIENT_NAME,
                            "Content block stop has no matching start for index $index",
                        )
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
                    throw LLMClientException(BEDROCK_ANTHROPIC_CLIENT_NAME, "Anthropic error: ${response.error}")
                }

                AnthropicStreamEventType.PING.value -> {
                    logger.debug { "Received ping from Anthropic" }
                }
            }
        }
    }

    private fun decodeAnthropicResponse(payload: String): BedrockAnthropicResponse =
        parseAnthropicPayload(payload, "response") { root ->
            validateReasoningBlocks(root["content"] as? JsonArray)
            json.decodeFromJsonElement(root)
        }

    private fun decodeAnthropicStreamResponse(payload: String): AnthropicStreamResponse =
        parseAnthropicPayload(payload, "stream event") { root ->
            var signature: String? = null
            when ((root["type"] as? JsonPrimitive)?.content) {
                AnthropicStreamEventType.CONTENT_BLOCK_START.value -> {
                    val block = root["content_block"] as? JsonObject
                        ?: throw LLMClientException(
                            BEDROCK_ANTHROPIC_CLIENT_NAME,
                            "Anthropic stream content block is missing",
                        )
                    validateReasoningBlock(block, allowMissingOrEmptySignature = true)
                }

                AnthropicStreamEventType.CONTENT_BLOCK_DELTA.value -> {
                    val delta = root["delta"] as? JsonObject
                        ?: throw LLMClientException(BEDROCK_ANTHROPIC_CLIENT_NAME, "Anthropic stream delta is missing")
                    if ((delta["type"] as? JsonPrimitive)?.content == AnthropicStreamDeltaContentType.SIGNATURE_DELTA.value) {
                        signature = delta.requireStringField("signature_delta", "signature")
                        if (signature.isNullOrEmpty()) {
                            throw LLMClientException(
                                BEDROCK_ANTHROPIC_CLIENT_NAME,
                                "Malformed Anthropic signature_delta block: 'signature' must not be empty",
                            )
                        }
                    }
                }
            }
            json.decodeFromJsonElement<AnthropicStreamResponse>(
                root.withMissingThinkingStartSignature()
            ).also { response -> response.delta?.signature = signature }
        }

    private inline fun <T> parseAnthropicPayload(
        payload: String,
        description: String,
        decode: (JsonObject) -> T,
    ): T {
        try {
            val root = json.parseToJsonElement(payload) as? JsonObject
                ?: throw LLMClientException(
                    BEDROCK_ANTHROPIC_CLIENT_NAME,
                    "Anthropic $description must be a JSON object",
                )
            return decode(root)
        } catch (cause: LLMClientException) {
            throw cause
        } catch (cause: Exception) {
            throw LLMClientException(
                BEDROCK_ANTHROPIC_CLIENT_NAME,
                "Malformed Anthropic $description: ${cause.message}",
                cause,
            )
        }
    }

    private fun validateReasoningBlocks(content: JsonArray?) {
        content?.forEach { element ->
            val block = element as? JsonObject
                ?: throw LLMClientException(BEDROCK_ANTHROPIC_CLIENT_NAME, "Anthropic content block must be an object")
            validateReasoningBlock(block, allowMissingOrEmptySignature = false)
        }
    }

    private fun validateReasoningBlock(block: JsonObject, allowMissingOrEmptySignature: Boolean) {
        when ((block["type"] as? JsonPrimitive)?.content) {
            "thinking" -> {
                block.requireStringField("thinking", "thinking")
                val signature = if (allowMissingOrEmptySignature && !block.containsKey("signature")) {
                    null
                } else {
                    block.requireStringField("thinking", "signature")
                }
                if (!allowMissingOrEmptySignature && signature.isNullOrEmpty()) {
                    throw LLMClientException(
                        BEDROCK_ANTHROPIC_CLIENT_NAME,
                        "Malformed Anthropic thinking block: 'signature' must not be empty",
                    )
                }
            }

            "redacted_thinking" -> {
                val data = block.requireStringField("redacted_thinking", "data")
                if (data.isEmpty()) {
                    throw LLMClientException(
                        BEDROCK_ANTHROPIC_CLIENT_NAME,
                        "Malformed Anthropic redacted_thinking block: 'data' must not be empty",
                    )
                }
            }
        }
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

    private fun JsonObject.requireStringField(blockType: String, field: String): String {
        val value = this[field] as? JsonPrimitive
        if (value == null || !value.isString) {
            throw LLMClientException(
                BEDROCK_ANTHROPIC_CLIENT_NAME,
                "Malformed Anthropic $blockType block: '$field' must be a string",
            )
        }
        return value.content
    }

    private const val BEDROCK_ANTHROPIC_CLIENT_NAME = "Bedrock Anthropic"
}
