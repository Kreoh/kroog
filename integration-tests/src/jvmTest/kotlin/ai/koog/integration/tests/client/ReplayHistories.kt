package ai.koog.integration.tests.client

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.bedrock.converse.BedrockConverseParams
import ai.koog.prompt.executor.clients.google.GoogleHostedExecutionConfig
import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.openai.OpenAICodeInterpreterConfig
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.message.MessagePart
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal data class ReplayCase(
    val name: String,
    val sourceSupports: (ReplayRoute) -> Boolean = { true },
    val destinationSupports: (ReplayRoute) -> Boolean = { true },
    val generate: suspend (ReplayRoute) -> ReplayHistory,
)

internal class ReplayFixtureUnavailable(message: String) : Exception(message)

private fun requireFixture(condition: Boolean, description: String) {
    if (!condition) throw ReplayFixtureUnavailable(description)
}

internal data class ReplayHistory(
    val prompt: Prompt,
    val tools: List<ToolDescriptor> = emptyList(),
    val control: ReplayHistory? = null,
    val verify: (Message.Assistant) -> Unit,
)

private fun receipt() = UUID.randomUUID().toString().take(13)
private fun Message.Assistant.answer() = parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()

internal class ReplayAnswerShapeError(kind: String, finish: String, calls: Int) :
    AssertionError("Expected a JSON object, received $kind (finish=$finish, tool calls=$calls)")

private fun Message.Assistant.answerFields(): Map<String, String> {
    val answer = answer()
    val json = answer.removeSurrounding("```json\n", "\n```")
    fun invalid(description: String): Nothing {
        // Provider replies may contain private reasoning or authenticated payloads. Report only their shape.
        val finish = finishReason?.lowercase()?.takeIf {
            it in setOf("refusal", "content_filtered", "tool_calls", "tool_use", "stop", "end_turn", "completed", "length", "max_tokens")
        } ?: "unknown"
        val calls = parts.count { it is MessagePart.Tool.Call }
        throw ReplayAnswerShapeError(description, finish, calls)
    }
    if (answer.isEmpty()) invalid("no answer text")
    val parsed = try {
        Json.parseToJsonElement(json)
    } catch (_: SerializationException) {
        invalid("text outside valid JSON")
    }
    val fields = parsed as? JsonObject ?: invalid("a non-object JSON value")
    return fields.mapValues { (_, value) ->
        (value as? JsonPrimitive)?.content ?: invalid("a nested JSON value")
    }
}

private fun checkReasoning(reply: Message.Assistant, enabled: Boolean?) {
    if (enabled != null) {
        requireFixture(enabled == reply.parts.any { it is MessagePart.Reasoning }, "Unexpected source reasoning shape")
    }
}

private suspend fun correctedFact(route: ReplayRoute, reasoning: Boolean? = null, textOnly: Boolean = false): ReplayHistory {
    fun saved(reply: Message.Assistant) = if (textOnly) {
        Message.Assistant(reply.answer(), ResponseMetaInfo(timestamp = reply.metaInfo.timestamp))
    } else reply
    val first = receipt()
    val latest = receipt()
    val initial = prompt("corrected-fact") { user("The sample label is $first. Reply only ACK.") }
    val ack = route.execute(initial)
    val correction = Prompt.build(initial) {
        message(saved(ack))
        val condition = if (reasoning == true) " Check whether 74,933 × 271 = 20,306,843. Reply ACK only if it is correct, otherwise reply ERROR." else " Reply only ACK."
        user("Correction: the sample label is now $latest.$condition")
    }
    val corrected = route.execute(correction)
    checkReasoning(corrected, reasoning)
    return ReplayHistory(Prompt.build(correction) {
        message(saved(corrected))
        user("What is the current sample label? Reply with the label alone.")
    }) { assertEquals(latest, it.answer()) }
}

private enum class ResultKind { VALUE, EMPTY, ERROR, IMAGE }

private data class Lookup(val label: String, val tool: String = "lookup", val kind: ResultKind = ResultKind.VALUE)

private val alpha = Lookup("alpha")
private val beta = Lookup("beta")
private val gamma = Lookup("gamma")
private val delta = Lookup("delta")

private fun hasToolResultVision(route: ReplayRoute) =
    hasVision(route) && when (route.params) {
        is AnthropicParams, is BedrockConverseParams, is GoogleParams, is OpenAIResponsesParams -> true
        else -> false
    }

private fun hasVision(route: ReplayRoute) = LLMCapability.Vision.Image in route.model.capabilities.orEmpty()

private fun colourStrip(): Pair<AttachmentSource.Image, String> {
    val colours = mapOf("red" to Color.RED, "blue" to Color.BLUE, "yellow" to Color.YELLOW, "green" to Color.GREEN)
    val order = colours.keys.shuffled()
    val pixels = BufferedImage(320, 80, BufferedImage.TYPE_INT_RGB)
    val brush = pixels.createGraphics()
    try {
        order.forEachIndexed { index, name ->
            brush.color = colours.getValue(name)
            brush.fillRect(index * 80, 0, 80, 80)
        }
    } finally {
        brush.dispose()
    }
    val bytes = ByteArrayOutputStream().use { buffer ->
        check(ImageIO.write(pixels, "png", buffer))
        buffer.toByteArray()
    }
    return AttachmentSource.Image(AttachmentContent.Binary.Bytes(bytes), "png") to order.joinToString("-")
}

internal suspend fun parallelRepeatedToolHistory(route: ReplayRoute, streaming: Boolean = false): ReplayHistory =
    toolHistory(route, listOf(listOf(alpha, beta)), streaming = streaming)

private suspend fun toolHistory(
    route: ReplayRoute,
    batches: List<List<Lookup>>,
    reverse: Boolean = false,
    streaming: Boolean = false,
    reasoning: Boolean? = null,
): ReplayHistory {
    val lookups = batches.flatten()
    val tools = lookups.map { it.tool }.distinct().map { name ->
        ToolDescriptor(name, "Read the requested record.", listOf(ToolParameterDescriptor("label", "Record label", ToolParameterType.String)))
    }
    val prerequisite = if (reasoning == true) "Check whether 74,933 × 271 = 20,306,843. Only proceed if correct. " else ""
    val schedule = batches.joinToString(" then ") { batch ->
        batch.joinToString(" and ", prefix = "[", postfix = "]") { "${it.tool}(label=${it.label})" }
    }
    val labels = lookups.map { it.label }.distinct()
    val verifyError = lookups.any { it.kind == ResultKind.ERROR }
    val answerInstruction = if (verifyError) {
        "After receiving both final results, reply only ACK, regardless of their statuses."
    } else {
        val valueInstruction = when {
            lookups.any { it.kind == ResultKind.IMAGE } ->
                "Give the four lower-case colour names joined by hyphens from left to right for an image."
            lookups.any { it.kind == ResultKind.EMPTY } ->
                "If a result was blank, use an empty JSON string for that label."
            else -> ""
        }
        "After the final batch return only a JSON object with these keys: ${labels.joinToString()}. " +
            "Use the latest value for each label. $valueInstruction".trimEnd()
    }
    var history = prompt("tool-batches") {
        user(
            "${prerequisite}Call the provided tools now. Perform the following batches exactly in order: $schedule. " +
                "Call the tool once for each listed label. Issue all calls within a bracket in parallel in one response, " +
                "and wait for their results before the next batch. $answerInstruction"
        )
    }
    val expected = mutableMapOf<String, String>()
    for (batch in batches) {
        val reply = if (streaming) route.executeStreaming(history, tools) else route.execute(history, tools)
        checkReasoning(reply, reasoning)
        val calls = reply.parts.filterIsInstance<MessagePart.Tool.Call>()
        val requested = batch.associateBy { it.tool to it.label }
        val keys = calls.map { it.tool to ((it.argsJson["label"] as? JsonPrimitive)?.content ?: throw ReplayFixtureUnavailable("Source omitted its tool label")) }
        requireFixture(batch.size == calls.size, "Source did not produce the scheduled batch")
        requireFixture(requested.keys == keys.toSet(), "Source changed the tool or label")
        if (calls.size > 1) {
            requireFixture(calls.all { !it.id.isNullOrBlank() } && calls.map { it.id }.distinct().size == calls.size, "Ambiguous parallel call IDs")
        }
        val results = calls.zip(keys).map { (call, key) ->
            val lookup = requested.getValue(key)
            if (lookup.kind == ResultKind.IMAGE) {
                val (image, value) = colourStrip()
                expected[lookup.label] = value
                MessagePart.Tool.Result(call.id, call.tool, listOf(MessagePart.Attachment(image)))
            } else {
                val value = if (lookup.kind == ResultKind.EMPTY) "" else receipt()
                expected[lookup.label] = value
                MessagePart.Tool.Result(call.id, call.tool, value, isError = lookup.kind == ResultKind.ERROR)
            }
        }
        history = Prompt.build(history) {
            message(reply)
            user(if (reverse) results.reversed() else results)
        }
    }
    if (verifyError) {
        val final = history.messages.last() as Message.User
        val results = final.parts.filterIsInstance<MessagePart.Tool.Result>()
        check(results.size == 2 && results.count { it.isError } == 1)
        val inverted = final.copy(parts = results.map { it.copy(isError = !it.isError) })
        val alternate = history.withMessages { it.dropLast(1) + inverted }
        val ack = route.execute(history, tools)
        requireFixture(ack.answer() == "ACK", "Source did not acknowledge the completed tool batch")
        fun followUp(batch: Prompt) = Prompt.build(batch) {
            // A neutral acknowledgement keeps the control independent of any source reasoning about the statuses.
            assistant(ack.answer())
            user("Which lookup succeeded and which failed in the final batch? " +
                "Reply only with one JSON object containing successful_raw_value and failed_raw_value. " +
                "Use the receipt from each lookup as the corresponding string value.")
        }
        val failed = results.single { it.isError }.output
        val successful = results.single { !it.isError }.output
        val alternateExpected = mapOf("failed_raw_value" to successful, "successful_raw_value" to failed)
        val control = ReplayHistory(followUp(alternate), tools) { assertEquals(alternateExpected, it.answerFields()) }
        val primaryExpected = mapOf("failed_raw_value" to failed, "successful_raw_value" to successful)
        return ReplayHistory(followUp(history), tools, control) { assertEquals(primaryExpected, it.answerFields()) }
    }
    return ReplayHistory(history, tools) { assertEquals(expected, it.answerFields()) }
}

private suspend fun userImage(route: ReplayRoute, multiple: Boolean = false): ReplayHistory {
    val (image, order) = colourStrip()
    val start = prompt("image") {
        user {
            image(image)
            text("Keep this image for later. Reply only ACK.")
        }
    }
    val ack = route.execute(start)
    requireFixture(ack.answer() == "ACK", "Source did not acknowledge the fixture")
    var history = Prompt.build(start) { message(ack) }
    var expected = order
    if (multiple) {
        val (next, nextOrder) = generateSequence(::colourStrip).first { it.second != order }
        history = Prompt.build(history) {
            user {
                image(next)
                text("Keep this second image too. Reply only ACK.")
            }
        }
        val secondAck = route.execute(history)
        requireFixture(secondAck.answer() == "ACK", "Source did not acknowledge the fixture")
        history = Prompt.build(history) { message(secondAck) }
        expected += "/$nextOrder"
    }
    return ReplayHistory(Prompt.build(history) {
        user("List the four colour names in each image left to right, lower-case, joined by hyphens. " +
            "Separate images by a slash in the order received. No other text.")
    }) { assertEquals(expected, it.answer()) }
}

private suspend fun referenceLookup(route: ReplayRoute, web: Boolean): ReplayHistory {
    val tool = if (web) "web_search" else "read_file"
    val key = if (web) "query" else "path"
    val argument = if (web) "inventory audit" else "audit.txt"
    val descriptor = ToolDescriptor(tool, "Retrieve an audit record.", listOf(ToolParameterDescriptor(key, "Lookup key", ToolParameterType.String)))
    val initial = prompt("reference-lookup") { user("Call $tool with $key set to '$argument'. After the result reply only ACK.") }
    val reply = route.execute(initial, listOf(descriptor))
    val call = reply.parts.filterIsInstance<MessagePart.Tool.Call>().singleOrNull()
        ?: throw ReplayFixtureUnavailable("Source did not produce one reference lookup")
    requireFixture(tool == call.tool, "Unexpected reference tool")
    requireFixture(argument == (call.argsJson[key] as? JsonPrimitive)?.content, "Unexpected reference lookup argument")
    val source = if (web) "https://audit.example.invalid/records/${receipt()}" else argument
    val value = receipt()
    val record = "Source: $source\nInventory audit\nReceipt: $value"
    val result = Prompt.build(initial) {
        message(reply)
        toolResult(tool = tool, output = record, id = call.id)
    }
    val ack = route.execute(result, listOf(descriptor))
    requireFixture(ack.answer() == "ACK", "Source did not acknowledge the fixture")
    return ReplayHistory(Prompt.build(result) {
        message(ack)
        user("Using the saved record, return only a JSON object with the receipt in 'value' and its exact source in 'source'.")
    }, listOf(descriptor)) {
        val fields = it.answerFields()
        assertEquals(value, fields.getValue("value"))
        assertEquals(source, fields.getValue("source"))
    }
}

internal fun executionTranscript(reply: Message.Assistant, output: String): MessagePart.Text {
    val code = reply.parts.mapNotNull { part ->
        when (part) {
            is MessagePart.CodeExecution -> part.code
            is MessagePart.HostedExecution.Request -> part.code
            else -> null
        }
    }
    requireFixture(code.isNotEmpty(), "Source omitted executed code")
    return MessagePart.Text("Recorded code execution from a previous workspace.\nCode:\n${code.joinToString("\n")}\nOutput:\n$output")
}

private suspend fun codeOutput(route: ReplayRoute, kind: String, portable: Boolean = false): ReplayHistory {
    val expression = if (kind == "number") "secrets.randbelow(900000000) + 100000000" else "secrets.token_hex(8)"
    val format = if (kind == "number") "[0-9]{9}" else "[0-9a-f]{16}"
    val code = when (kind) {
        "file" -> "from pathlib import Path; p = Path('/mnt/data') / ('receipt-' + secrets.token_hex(8) + '.txt'); " +
            "p.write_text('RECEIPT=' + str($expression)); print(p.read_text())"
        "error" -> "raise ValueError('RECEIPT=' + str($expression))"
        else -> "print('RECEIPT=' + str($expression))"
    }
    val instruction = if (kind == "file") "Return a download link to the generated file, without disclosing its contents." else "Reply only ACK."
    val initial = prompt("code-output") {
        user("Use the Python execution tool to run: import secrets; $code. Run it only once. $instruction")
    }
    val params = when (val current = route.params) {
        is OpenAIResponsesParams -> {
            val expired = if (kind == "expired") requireNotNull(System.getenv("KROOG_REPLAY_EXPIRED_CONTAINER_ID")) else null
            current.copy(include = listOf(OpenAIInclude.CODE_INTERPRETER_CALL_OUTPUTS))
                .withCodeInterpreter(OpenAICodeInterpreterConfig(containerId = expired))
        }
        is GoogleParams -> current.withHostedExecution(GoogleHostedExecutionConfig())
        else -> error("Unsupported hosted execution route ${route.name}")
    }
    val reply = route.execute(initial, params = params)
    if (kind == "expired") {
        assertTrue(reply.parts.filterIsInstance<MessagePart.HostedExecution.Progress>().any { it.message == "stale_container_recovered" })
    }
    if (kind == "file") {
        val citations = reply.parts.filterIsInstance<MessagePart.Text>().flatMap { it.generatedFileCitations }
        val citation = citations.singleOrNull { it.filename?.substringAfterLast('/')?.matches(Regex("receipt-[0-9a-f]{16}\\.txt")) == true }
            ?: throw ReplayFixtureUnavailable("Source did not supply the requested citation")
        val fileId = citation.providerFileId
        requireFixture(fileId.isNotBlank(), "Source omitted its file ID")
        val withoutCitations = reply.parts.map { part ->
            if (part is MessagePart.Text) part.copy(generatedFileCitations = emptyList()) else part
        }
        requireFixture(fileId !in withoutCitations.toString(), "Generated file ID appeared outside the citation")
        return ReplayHistory(Prompt.build(initial) {
            message(reply)
            user("What provider file ID does the saved generated file citation reference? Return only the ID, with no path or filename.")
        }) { assertEquals(fileId, it.answer()) }
    }
    val output = reply.parts.flatMap {
        when (it) {
            is MessagePart.HostedExecution.Result -> listOfNotNull(it.output)
            is MessagePart.HostedExecution.Error -> listOf(it.message)
            is MessagePart.CodeExecution -> it.outputs.filterIsInstance<MessagePart.CodeExecution.Output.Logs>().map { log -> log.logs }
            else -> emptyList()
        }
    }.joinToString("\n")
    if (kind == "error") {
        val typedError = reply.parts.any {
            it is MessagePart.HostedExecution.Error || it is MessagePart.CodeExecution && it.failure != null
        }
        // Python can raise inside a completed interpreter call; the traceback then carries the error.
        requireFixture(typedError || Regex("ValueError: RECEIPT=[0-9a-f]{16}").containsMatchIn(output),
            "Source did not return an execution error")
    }
    val value = Regex("RECEIPT=($format)\\b").find(output)?.groupValues?.get(1) ?: throw ReplayFixtureUnavailable("Missing executed receipt")
    requireFixture(value !in reply.answer(), "Source disclosed the receipt outside execution output")
    return ReplayHistory(Prompt.build(initial) {
        if (portable) {
            val transcript = executionTranscript(reply, output)
            message(reply.copy(parts = listOf(transcript) + reply.parts.filterIsInstance<MessagePart.Text>().map { MessagePart.Text(it.text) }))
        } else {
            message(reply)
        }
        user("What exact value followed RECEIPT= in the saved output? Reply with the value alone.")
    }) { assertEquals(value, it.answer()) }
}

private suspend fun codeImage(route: ReplayRoute): ReplayHistory {
    val initial = prompt("code-image") {
        user(
            "Use Python to create a 320x80 RGB image of red, blue, yellow and green equal vertical stripes. " +
                "Shuffle their order with secrets.SystemRandom().shuffle. Display the PIL image as notebook output. " +
                "Do not print the order. After execution reply only ACK."
        )
    }
    val params = (route.params as OpenAIResponsesParams)
        .copy(include = listOf(OpenAIInclude.CODE_INTERPRETER_CALL_OUTPUTS))
        .withCodeInterpreter(OpenAICodeInterpreterConfig())
    val reply = route.execute(initial, params = params)
    val images = reply.parts.filterIsInstance<MessagePart.CodeExecution>().flatMap { it.outputs }
        .filterIsInstance<MessagePart.CodeExecution.Output.Image>()
    val url = images.singleOrNull()?.url ?: throw ReplayFixtureUnavailable("Missing unique execution image")
    requireFixture(url.startsWith("data:image/") && ";base64," in url, "Execution image has no saved bytes")
    val pixels = ImageIO.read(Base64.getDecoder().decode(url.substringAfter(";base64,")).inputStream())
    val names = mapOf(Color.RED.rgb to "red", Color.BLUE.rgb to "blue", Color.YELLOW.rgb to "yellow",
        Color.GREEN.rgb to "green", Color(0, 128, 0).rgb to "green")
    val stripes = (0..3).map { names[pixels.getRGB((2 * it + 1) * pixels.width / 8, pixels.height / 2)] }
    requireFixture(null !in stripes && stripes.distinct().size == 4, "Missing requested image colours")
    requireFixture(reply.answer() == "ACK", "Source did not acknowledge the fixture")
    return ReplayHistory(Prompt.build(initial) {
        message(reply)
        user("From the saved output image, list the four lower-case colour names left to right, joined by hyphens.")
    }) { assertEquals(stripes.joinToString("-"), it.answer()) }
}

internal val replayCases = buildList {
    add(ReplayCase("corrected-fact-text-only") { correctedFact(it, textOnly = true) })
    add(ReplayCase("corrected-fact") { correctedFact(it) })
    add(ReplayCase("single-tool") { toolHistory(it, listOf(listOf(alpha))) })
    add(ReplayCase("parallel-repeated-tool") { parallelRepeatedToolHistory(it) })
    add(ReplayCase("parallel-reversed-results") { toolHistory(it, listOf(listOf(alpha, beta)), reverse = true) })
    add(ReplayCase("parallel-different-tools") { toolHistory(it, listOf(listOf(alpha, beta.copy(tool = "fetch")))) })
    add(ReplayCase("sequential-tools") { toolHistory(it, listOf(listOf(alpha), listOf(beta))) })
    add(ReplayCase("consecutive-batches") { toolHistory(it, listOf(listOf(alpha, beta), listOf(gamma, delta))) })
    add(ReplayCase("empty-result") { toolHistory(it, listOf(listOf(alpha.copy(kind = ResultKind.EMPTY), beta))) })
    add(ReplayCase("error-retry") { toolHistory(it, listOf(listOf(alpha.copy(kind = ResultKind.ERROR)), listOf(alpha, beta.copy(kind = ResultKind.ERROR)))) })
    add(ReplayCase("user-image", ::hasVision, ::hasVision) { userImage(it) })
    add(ReplayCase("user-images-multiple-turns", ::hasVision, ::hasVision) { userImage(it, multiple = true) })
    add(ReplayCase("tool-result-image", ::hasToolResultVision, ::hasToolResultVision) { toolHistory(it, listOf(listOf(alpha.copy(kind = ResultKind.IMAGE)))) })
    for (enabled in listOf(true, false)) {
        add(ReplayCase("reasoning-$enabled-text", { it.supportsReasoning(enabled) }) { correctedFact(it.withReasoning(enabled), enabled) })
        add(ReplayCase("reasoning-$enabled-tools", { it.supportsReasoning(enabled) }) {
            toolHistory(it.withReasoning(enabled), listOf(listOf(alpha)), reasoning = enabled)
        })
    }
    add(ReplayCase("file-lookup") { referenceLookup(it, web = false) })
    add(ReplayCase("cited-web-result") { referenceLookup(it, web = true) })
    add(ReplayCase("code-expired-container", {
        it.params is OpenAIResponsesParams && !System.getenv("KROOG_REPLAY_EXPIRED_CONTAINER_ID").isNullOrBlank()
    }, { it.params is OpenAIResponsesParams }) { codeOutput(it, "expired") })
    add(ReplayCase("code-output-file", { it.params is OpenAIResponsesParams }, { it.params is OpenAIResponsesParams }) { codeOutput(it, "file") })
    add(ReplayCase("code-output-image", { it.params is OpenAIResponsesParams }, { it.params is OpenAIResponsesParams }, ::codeImage))
    add(ReplayCase("code-native-google-text", { it.params is GoogleParams }, { it.params is GoogleParams }) { codeOutput(it, "text") })
    add(ReplayCase("code-native-responses-text", { it.params is OpenAIResponsesParams }, { it.params is OpenAIResponsesParams }) {
        codeOutput(it, "text")
    })
    for (kind in listOf("number", "text", "error")) {
        add(ReplayCase("code-output-$kind", { it.params is GoogleParams || it.params is OpenAIResponsesParams }) { codeOutput(it, kind, portable = true) })
    }
}
