package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParityTest.ScriptedResponsesTransport
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.message.CLIENT_MANAGED_EXECUTION_TOOL_NAME
import ai.koog.prompt.message.ExecutionOrigin
import ai.koog.prompt.message.ManagedExecutionSessionReference
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.provider.ProviderApi
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAIHostedShellTest {
    @Test
    fun testStreamsShellExecutionAndReplaysCommandsAndOutputsInTheSameContainer() = runTest {
        val requested = call()
        val returned = output()
        val frames = listOf(
            itemEvent("added", 0, 1, call(status = "in_progress", commands = "[]")),
            """{"type":"response.shell_call_command.added","sequence_number":2,"output_index":0,"command_index":0,"command":""}""",
            """{"type":"response.shell_call_command.delta","sequence_number":3,"output_index":0,"command_index":0,"delta":"printf "}""",
            """{"type":"response.shell_call_command.done","sequence_number":4,"output_index":0,"command_index":0,"command":"printf shell-receipt"}""",
            itemEvent("done", 0, 5, requested),
            """{"type":"response.shell_call_output_content.delta","sequence_number":6,"output_index":1,"item_id":"shell_output_item","command_index":0,"delta":{"stdout":"shell-receipt"}}""",
            """{"type":"response.shell_call_output_content.done","sequence_number":7,"output_index":1,"item_id":"shell_output_item","command_index":0,"output":[{"stdout":"shell-receipt","stderr":"","outcome":{"type":"exit","exit_code":0}}]}""",
            itemEvent("done", 1, 8, returned),
            """{"type":"response.completed","sequence_number":9,"response":${response(requested, returned)}}""",
        )
        val streaming = ScriptedResponsesTransport(streamAttempts = ArrayDeque(listOf(frames, frames)))
        val transport = Transport(listOf(Reply.json(response(ANSWER))))
        val streamingClient = OpenAILLMClient(OpenAIClientSettings(), streaming)
        transport.client.use { client ->
            val execution = streamingClient.executeStreaming(prompt(OpenAIHostedShellConfig(fileIds = listOf("file_input"))), model)
            val stream = execution.toList()
            val updates = stream.filterIsInstance<StreamFrame.HostedExecutionUpdate>().map { it.update }
            assertEquals(updates, execution.toList().filterIsInstance<StreamFrame.HostedExecutionUpdate>().map { it.update })
            assertEquals(3, updates.size)
            val request = assertIs<MessagePart.HostedExecution.Request>(updates[0])
            val progress = assertIs<MessagePart.HostedExecution.Progress>(updates[1])
            val result = assertIs<MessagePart.HostedExecution.Result>(updates[2])
            assertEquals("printf shell-receipt\nprintf stderr-receipt >&2", request.code)
            assertEquals("bash", request.language)
            assertEquals("shell-receipt\nstderr-receipt", result.output)
            assertEquals(7, result.exitCode)
            assertEquals(1, progress.sequence)
            assertEquals(request.executionId, progress.executionId)
            assertEquals(request.executionId, result.executionId)
            assertEquals("cntr_fixture", result.containerId)
            assertEquals("shell_output_item", result.providerItemId)
            assertEquals(Json.parseToJsonElement(requested), Json.parseToJsonElement(assertNotNull(request.replay).payload))
            assertEquals(Json.parseToJsonElement(returned), Json.parseToJsonElement(assertNotNull(result.replay).payload))
            assertIs<StreamFrame.End>(stream.last())

            val continuation = prompt(OpenAIHostedShellConfig(containerId = result.containerId)).copy(
                messages = listOf(stream.toMessageResponse(), Message.User("Continue", RequestMetaInfo.Empty)),
            )
            val reply = client.execute(continuation, model)
            assertEquals("ready", assertIs<MessagePart.Text>(reply.parts.single()).text)

            assertEquals(2, streaming.requests.size)
            val first = Json.parseToJsonElement(streaming.requests.first()).jsonObject
            assertTrue(first.getValue("stream").jsonPrimitive.boolean)
            val environment = first.shellEnvironment()
            assertEquals("container_auto", environment.getValue("type").jsonPrimitive.content)
            assertEquals(listOf("file_input"), environment.getValue("file_ids").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("disabled", environment.getValue("network_policy").jsonObject.getValue("type").jsonPrimitive.content)

            val next = transport.requests.single()
            val reused = next.shellEnvironment()
            assertEquals("container_reference", reused.getValue("type").jsonPrimitive.content)
            assertEquals("cntr_fixture", reused.getValue("container_id").jsonPrimitive.content)
            val input = next.getValue("input").jsonArray
            assertEquals(Json.parseToJsonElement(call(includeCreatedBy = false)), input[0])
            assertEquals(Json.parseToJsonElement(output(includeCreatedBy = false)), input[1])
        }
    }

    @Test
    fun testIncompleteStreamPreservesTextAndUsageWhenShellCallHasNoCommands() = runTest {
        val unfinished = call(status = "in_progress", commands = "[]")
        val terminal = response(
            ANSWER,
            unfinished,
            status = "incomplete",
            usage = """{"input_tokens":10,"output_tokens":5,"total_tokens":15}""",
        )
        val events = listOf(
            """{"type":"response.output_text.done","sequence_number":1,"item_id":"msg_fixture","output_index":0,"content_index":0,"text":"ready"}""",
            itemEvent("added", 1, 2, unfinished),
            """{"type":"response.incomplete","sequence_number":3,"response":$terminal}""",
        )
        val transport = ScriptedResponsesTransport(streamAttempts = ArrayDeque(listOf(events)))
        OpenAILLMClient(OpenAIClientSettings(), transport).use { client ->
            val initial = prompt(OpenAIHostedShellConfig())
            val stream = client.executeStreaming(initial, model).toList()
            val end = assertIs<StreamFrame.End>(stream.last())
            val history = stream.toMessageResponse()
            assertEquals(10, end.metaInfo.inputTokensCount)
            assertEquals(5, end.metaInfo.outputTokensCount)
            assertEquals(15, history.metaInfo.totalTokensCount)
            assertEquals(3, history.parts.size)
            assertEquals("ready", assertIs<MessagePart.Text>(history.parts[0]).text)
            val progress = assertIs<MessagePart.HostedExecution.Progress>(history.parts[1])
            val error = assertIs<MessagePart.HostedExecution.Error>(history.parts[2])
            assertEquals(progress.executionId, error.executionId)
            assertEquals("incomplete", error.code)
            assertEquals(Json.parseToJsonElement(unfinished), Json.parseToJsonElement(assertNotNull(error.replay).payload))

            val continuation = initial.copy(
                messages = initial.messages + history + Message.User("Continue", RequestMetaInfo.Empty)
            )
            assertFailsWith<IllegalArgumentException> { client.execute(continuation, model) }
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun testIncompleteStreamReportsUnfinishedShellOutputAsAnErrorAndRejectsReplay() = runTest {
        val unfinished = """{"type":"shell_call_output","id":"shell_output_item","call_id":"call_fixture","status":"in_progress","output":[]}"""
        val terminal = response(call(), unfinished, status = "incomplete")
        val events = listOf(
            itemEvent("added", 0, 1, call(status = "in_progress", commands = "[]")),
            """{"type":"response.incomplete","sequence_number":2,"response":$terminal}""",
        )
        val transport = ScriptedResponsesTransport(streamAttempts = ArrayDeque(listOf(events)))
        OpenAILLMClient(OpenAIClientSettings(), transport).use { client ->
            val initial = prompt(OpenAIHostedShellConfig())
            val stream = client.executeStreaming(initial, model).toList()
            assertIs<StreamFrame.End>(stream.last())
            val history = stream.toMessageResponse()
            assertEquals(3, history.parts.size)
            val request = assertIs<MessagePart.HostedExecution.Request>(history.parts[0])
            val progress = assertIs<MessagePart.HostedExecution.Progress>(history.parts[1])
            val error = assertIs<MessagePart.HostedExecution.Error>(history.parts[2])
            assertEquals(request.executionId, progress.executionId)
            assertEquals(request.executionId, error.executionId)
            assertEquals("incomplete", error.code)

            val continuation = initial.copy(
                messages = initial.messages + history + Message.User("Continue", RequestMetaInfo.Empty)
            )
            assertFailsWith<IllegalArgumentException> { client.execute(continuation, model) }
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun testSynchronousIncompleteShellCallIsNotAReplayableRequest() = runTest {
        val unfinished = call(status = "incomplete", commands = """["print"]""")
        val transport = Transport(listOf(Reply.json(response(unfinished, status = "incomplete"))))
        transport.client.use { client ->
            val initial = prompt(OpenAIHostedShellConfig())
            val history = client.execute(initial, model)
            val error = assertIs<MessagePart.HostedExecution.Error>(history.parts.single())
            assertEquals("incomplete", error.code)
            assertEquals("execution:call_fixture", error.executionId)

            val continuation = initial.copy(
                messages = initial.messages + history + Message.User("Continue", RequestMetaInfo.Empty)
            )
            assertFailsWith<IllegalArgumentException> { client.execute(continuation, model) }
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun testDecodesSynchronousTimeoutAndGeneratedFileCitation() = runTest {
        val item = output(outcome = """{"type":"timeout"}""")
        val transport = Transport(listOf(Reply.json(response(call(), item, ANSWER_WITH_FILE))))
        transport.client.use { client ->
            val answer = client.execute(prompt(OpenAIHostedShellConfig(containerId = "cntr_fixture")), model)
            val result = assertIs<MessagePart.HostedExecution.Error>(answer.parts[1])
            assertEquals("timeout", result.code)
            assertTrue(result.message.contains("shell-receipt"))
            assertEquals("execution:call_fixture", result.executionId)
            val citation = assertIs<MessagePart.Text>(answer.parts[2]).generatedFileCitations.single()
            assertEquals("cntr_fixture", citation.containerId)
            assertEquals("cfile_receipt", citation.providerFileId)
            assertEquals("receipt.txt", citation.filename)
        }
    }

    @Test
    fun testRejectsMismatchedAndIncompleteReplayBeforeSendingTheFollowup() = runTest {
        val transport = Transport(listOf(Reply.json(response(call(), output()))))
        transport.client.use { client ->
            val prompt = prompt(OpenAIHostedShellConfig())
            val response = client.execute(prompt, model)
            val output = assertIs<MessagePart.HostedExecution.Result>(response.parts[1])
            val changed = response.copy(parts = listOf(response.parts[0], output.copy(executionId = "execution:call_other")))
            assertFailsWith<IllegalArgumentException> {
                client.execute(prompt.copy(messages = listOf(changed) + prompt.messages), model)
            }
            val interrupted = response.copy(parts = response.parts.take(1))
            assertFailsWith<IllegalArgumentException> {
                client.execute(prompt.copy(messages = listOf(interrupted) + prompt.messages), model)
            }
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun testShellReplayWithTheToolDisabledRequiresDirectResponses() = runTest {
        val requested = call()
        val returned = output()
        val direct = Transport(listOf(Reply.json(response(requested, returned)), Reply.json(response(ANSWER))))
        val codex = ScriptedResponsesTransport()
        direct.client.use { client ->
            val initial = prompt(OpenAIHostedShellConfig())
            val execution = client.execute(initial, model)
            val followUp = initial.copy(
                messages = initial.messages + execution + Message.User("Continue", RequestMetaInfo.Empty),
                params = OpenAIResponsesParams().asStateless(),
            )
            CodexLLMClient(CodexCredentialSource { CodexCredentials("fixture-token", "fixture-account") }, codex).use {
                assertFailsWith<IllegalArgumentException> { it.execute(followUp, model) }
            }
            assertTrue(codex.requests.isEmpty())

            val answer = client.execute(followUp, model)
            assertEquals("ready", assertIs<MessagePart.Text>(answer.parts.single()).text)
            val next = direct.requests.last()
            assertTrue(next["tools"]?.jsonArray.orEmpty().isEmpty())
            val input = next.getValue("input").jsonArray
            assertEquals(Json.parseToJsonElement(call(includeCreatedBy = false)), input[1])
            assertEquals(Json.parseToJsonElement(output(includeCreatedBy = false)), input[2])
        }
    }

    @Test
    fun testClientManagedExecutionCannotReplayNativeShellItems() = runTest {
        val transport = Transport(listOf(Reply.json(response(ANSWER))))
        val session = ManagedExecutionSessionReference.VertexAgentEngine(
            "project-1",
            "us-central1",
            "reasoningEngines/engine-1",
            "sandbox-1",
        )
        val toolName = CLIENT_MANAGED_EXECUTION_TOOL_NAME
        val toolCallId = "managed_call"
        val request = MessagePart.HostedExecution.Request(
            code = "printf shell-receipt",
            executionId = "execution:call_fixture",
            containerId = "cntr_fixture",
            origin = ExecutionOrigin.CLIENT_MANAGED,
            managedSession = session,
            toolCallId = toolCallId,
            replay = replayWithoutItemId(call()),
        )
        val result = MessagePart.HostedExecution.Result(
            output = "shell-receipt",
            executionId = request.executionId,
            containerId = request.containerId,
            origin = ExecutionOrigin.CLIENT_MANAGED,
            managedSession = session,
            toolCallId = toolCallId,
            replay = replayWithoutItemId(output()),
        )
        val ordinaryCall = MessagePart.Tool.Call(toolCallId, toolName, "{}")
        val ordinaryResult = Message.User(
            MessagePart.Tool.Result(toolCallId, toolName, "shell-receipt"),
            RequestMetaInfo.Empty,
        )
        val transcript = Message.Assistant(listOf(ordinaryCall, request, result), ResponseMetaInfo.Empty)
        val followUp = Prompt(
            id = "client-managed-execution",
            messages = listOf(transcript, ordinaryResult, Message.User("Continue", RequestMetaInfo.Empty)),
            params = OpenAIResponsesParams().asStateless(),
        )
        transport.client.use { client ->
            assertFailsWith<IllegalArgumentException> { client.execute(followUp, model) }
            assertTrue(transport.requests.isEmpty())

            val cleanTranscript = transcript.copy(
                parts = listOf(ordinaryCall, request.copy(replay = null), result.copy(replay = null)),
            )
            client.execute(followUp.copy(messages = listOf(cleanTranscript) + followUp.messages.drop(1)), model)
            val inputTypes = transport.requests.single().getValue("input").jsonArray.map {
                it.jsonObject.getValue("type").jsonPrimitive.content
            }
            assertEquals(listOf("function_call", "function_call_output", "message"), inputTypes)
        }
    }

    @Test
    fun testChatRejectsNativeShellHistoryBeforeDispatch() = runTest {
        val transport = Transport(listOf(Reply.json(response(call(), output()))))
        transport.client.use { client ->
            val initial = prompt(OpenAIHostedShellConfig())
            val shellTurn = client.execute(initial, model)
            for (params in listOf(OpenAIChatParams(), LLMParams())) {
                val followUp = initial.copy(
                    messages = initial.messages + shellTurn + Message.User("Continue", RequestMetaInfo.Empty),
                    params = params,
                )
                val chatModel = OpenAIModels.Chat.GPT5_4
                assertFailsWith<IllegalArgumentException> { client.execute(followUp, chatModel) }
                assertFailsWith<IllegalArgumentException> { client.executeStreaming(followUp, chatModel).toList() }
                assertFailsWith<IllegalArgumentException> { client.executeMultipleChoices(followUp, chatModel) }
            }
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun testUnsupportedModelsAndConflictingToolsDoNotSendARequest() = runTest {
        val transport = Transport(emptyList())
        transport.client.use { client ->
            assertFailsWith<OpenAIResponsesConfigurationException> {
                client.execute(prompt(OpenAIHostedShellConfig()), OpenAIModels.Chat.GPT4o)
            }
            assertFailsWith<IllegalArgumentException> {
                val conflict = OpenAIResponsesParams(codeInterpreter = OpenAICodeInterpreterConfig())
                    .withHostedShell(OpenAIHostedShellConfig())
                client.execute(prompt(OpenAIHostedShellConfig()).copy(params = conflict), model)
            }
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test
    fun testExplicitToolArrayWithHostedShellFailsBeforeDispatch() = runTest {
        val transport = Transport(listOf(Reply.json(response(ANSWER))))
        transport.client.use { client ->
            val tools = Json.parseToJsonElement("""[{"type":"web_search"}]""")
            val params = OpenAIResponsesParams(additionalProperties = mapOf("tools" to tools))
            val request = prompt(OpenAIHostedShellConfig())
            client.execute(request.copy(params = params), model)
            assertEquals(tools, transport.requests.single()["tools"])

            val conflict = request.copy(params = params.withHostedShell(OpenAIHostedShellConfig()))
            assertFailsWith<IllegalArgumentException> { client.execute(conflict, model) }
            assertFailsWith<IllegalArgumentException> { client.executeStreaming(conflict, model).toList() }
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun testDiscoveredHostedShellSnapshotsKeepTheirLimitsAndWireIdentifiers() = runTest {
        val expected = mapOf(
            "gpt-5.2" to OpenAIModels.Chat.GPT5_2,
            "gpt-5.2-2025-12-11" to OpenAIModels.Chat.GPT5_2,
            "gpt-5.4" to OpenAIModels.Chat.GPT5_4,
            "gpt-5.4-2026-03-05" to OpenAIModels.Chat.GPT5_4,
            "gpt-5.4-mini-2026-03-17" to OpenAIModels.Chat.GPT5_4Mini,
            "gpt-5.4-nano-2026-03-17" to OpenAIModels.Chat.GPT5_4Nano,
            "gpt-5.5-2026-04-23" to OpenAIModels.Chat.GPT5_5,
        )
        val unknown = "gpt-5.2-unlisted-snapshot"
        val ids = expected.keys + unknown
        val listing = ids.joinToString { """{"id":"$it","object":"model","created":1,"owned_by":"openai"}""" }
        val efforts = listOf(ReasoningEffort.HIGH, ReasoningEffort.NONE)
        val transport = Transport(
            listOf(Reply("""{"object":"list","data":[$listing]}""", path = "/v1/models")) +
                List(expected.size * efforts.size) { Reply.json(response(ANSWER)) },
        )
        transport.client.use { client ->
            val discovered = client.models().associateBy { it.id }
            assertEquals(ids, discovered.keys)
            for ((id, canonical) in expected) {
                val selected = discovered.getValue(id)
                assertEquals(canonical.capabilities, selected.capabilities)
                assertEquals(canonical.contextLength, selected.contextLength)
                assertEquals(canonical.maxOutputTokens, selected.maxOutputTokens)
                val config = OpenAIHostedShellConfig()
                for (effort in efforts) {
                    val params = OpenAIResponsesParams(
                        temperature = 0.7,
                        reasoning = ReasoningConfig(effort = effort),
                    ).asStateless().withHostedShell(config)
                    client.execute(prompt(config).copy(params = params), selected)
                    val request = transport.requests.last()
                    assertEquals(id, request.getValue("model").jsonPrimitive.content)
                    assertEquals(effort.name.lowercase(), request.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
                    assertEquals(if (effort == ReasoningEffort.NONE) 0.7 else null, request["temperature"]?.jsonPrimitive?.double)
                    assertEquals("container_auto", request.shellEnvironment().getValue("type").jsonPrimitive.content)
                }
            }
            val unlisted = discovered.getValue(unknown)
            assertEquals(null, unlisted.capabilities)
            assertFailsWith<OpenAIResponsesConfigurationException> {
                client.execute(prompt(OpenAIHostedShellConfig()), unlisted)
            }
            assertEquals(expected.size * efforts.size, transport.requests.size)
        }
    }

    @Test
    fun testGpt55ProAndItsDiscoveredSnapshotUseHostedShellThroughResponses() = runTest {
        val canonical = OpenAIModels.Chat.GPT5_5Pro
        val snapshotId = "gpt-5.5-pro-2026-04-23"
        val modelEntry = """{"id":"$snapshotId","object":"model","created":1,"owned_by":"openai"}"""
        val listing = """{"object":"list","data":[$modelEntry]}"""
        val shellReply = Reply.json(response(call(), output()))
        val transport = Transport(listOf(Reply(listing, path = "/v1/models"), shellReply, shellReply))
        transport.client.use { client ->
            val snapshot = client.models().single()
            assertEquals(canonical.copy(id = snapshotId), snapshot)
            assertTrue(snapshot.supports(LLMCapability.OpenAIEndpoint.Responses))
            assertFalse(snapshot.supports(LLMCapability.OpenAIEndpoint.Completions))

            for (selected in listOf(canonical, snapshot)) {
                val result = client.execute(prompt(OpenAIHostedShellConfig()), selected)
                val output = result.parts.filterIsInstance<MessagePart.HostedExecution.Result>().single()
                assertEquals("cntr_fixture", output.containerId)
                val request = transport.requests.last()
                assertEquals(selected.id, request.getValue("model").jsonPrimitive.content)
                assertEquals("container_auto", request.shellEnvironment().getValue("type").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun testStatelessRequestRecreatesExpiredContainerWithOriginalInputs() = runTest {
        val failure = """{"error":{"message":"Container is expired.","code":"container_expired","param":"tools[0].environment.container_id"}}"""
        val transport = Transport(
            listOf(Reply.json(failure, HttpStatusCode.BadRequest), Reply.json(response(call(), output()))),
        )
        transport.client.use { client ->
            val initial = prompt(OpenAIHostedShellConfig(fileIds = listOf("file_original"), containerId = "cntr_expired"))
            val configured = assertIs<OpenAIResponsesParams>(initial.params).withContainerRecovery { retry, error ->
                assertEquals("cntr_expired", error.containerId)
                assertEquals(OpenAIContainerUnavailableReason.Expired, error.reason)
                retry
            }
            val response = client.execute(initial.copy(params = configured), model)
            val recovered = assertIs<MessagePart.HostedExecution.Progress>(response.parts.first())
            assertEquals("stale_container_recovered", recovered.message)
            assertEquals("cntr_expired", recovered.containerId)
            val result = assertIs<MessagePart.HostedExecution.Result>(response.parts.last())
            assertEquals("cntr_fixture", result.containerId)
            val first = transport.requests[0].shellEnvironment()
            assertEquals("cntr_expired", first.getValue("container_id").jsonPrimitive.content)
            val next = transport.requests[1].shellEnvironment()
            assertEquals("container_auto", next.getValue("type").jsonPrimitive.content)
            assertEquals("file_original", next.getValue("file_ids").jsonArray.single().jsonPrimitive.content)
        }
    }

    @Test
    fun testValidatesAndPreservesHostedShellConfigurationInStatelessCopies() {
        assertFailsWith<IllegalArgumentException> { OpenAIHostedShellConfig(fileIds = listOf("file_a", "file_a")) }
        assertFailsWith<IllegalArgumentException> { OpenAIHostedShellConfig(containerId = " ") }
        val files = mutableListOf("file_a")
        val source = OpenAIResponsesParams().withHostedShell(OpenAIHostedShellConfig(fileIds = files))
        files.clear()
        val copy = source.asStateless().copy()
        assertEquals(listOf("file_a"), assertNotNull(copy.hostedShell).fileIds)
        assertEquals(null, copy.withHostedShell(null).hostedShell)
    }

    private fun prompt(config: OpenAIHostedShellConfig): Prompt = Prompt.build(
        id = "openai-hosted-shell",
        params = OpenAIResponsesParams(maxToolCalls = 3).asStateless().withHostedShell(config),
    ) { user("Run the command and report its output.") }

    private fun replayWithoutItemId(payload: String): MessagePart.HostedExecutionReplay =
        MessagePart.HostedExecutionReplay(
            ProviderApi.OPENAI_RESPONSES,
            JsonObject(Json.parseToJsonElement(payload).jsonObject - "id").toString(),
        )

    private fun JsonObject.shellEnvironment(): JsonObject {
        val tool = getValue("tools").jsonArray.single().jsonObject
        assertEquals("shell", tool.getValue("type").jsonPrimitive.content)
        return tool.getValue("environment").jsonObject
    }

    private class Reply(
        val body: String,
        val status: HttpStatusCode = HttpStatusCode.OK,
        val path: String = "/v1/responses",
    ) {
        companion object {
            fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
                Reply(body, status)
        }
    }

    private class Transport(replies: List<Reply>) {
        private val remaining = ArrayDeque(replies)
        val requests = mutableListOf<JsonObject>()
        private val engine = MockEngine { request ->
            val reply = remaining.removeFirst()
            assertEquals(reply.path, request.url.encodedPath)
            if (reply.path == "/v1/models") {
                assertEquals(HttpMethod.Get, request.method)
            } else {
                assertEquals(HttpMethod.Post, request.method)
                requests.add(Json.parseToJsonElement((request.body as TextContent).text).jsonObject)
            }
            respond(
                content = reply.body,
                status = reply.status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = OpenAILLMClient(
            apiKey = "fixture-api-key",
            httpClientFactory = KtorKoogHttpClient.Factory(baseClient = HttpClient(engine)),
        )
    }

    private companion object {
        val model = OpenAIModels.Chat.GPT6Luna

        const val ANSWER = """{"type":"message","id":"msg_fixture","role":"assistant","status":"completed","content":[{"type":"output_text","text":"ready","annotations":[]}]}"""
        const val ANSWER_WITH_FILE = """{"type":"message","id":"msg_fixture","role":"assistant","status":"completed","content":[{"type":"output_text","text":"ready","annotations":[{"type":"container_file_citation","container_id":"cntr_fixture","file_id":"cfile_receipt","filename":"receipt.txt","start_index":0,"end_index":5}]}]}"""

        fun call(
            status: String = "completed",
            commands: String = """["printf shell-receipt","printf stderr-receipt >&2"]""",
            includeCreatedBy: Boolean = true,
        ): String {
            val creator = if (includeCreatedBy) ""","created_by":"actor_call"""" else ""
            return """{"type":"shell_call","id":"shell_tool_item","call_id":"call_fixture","status":"$status","action":{"commands":$commands,"timeout_ms":1000,"max_output_length":64},"environment":{"type":"container_reference","container_id":"cntr_fixture"},"caller":{"type":"program","caller_id":"call_parent"}$creator}"""
        }

        fun output(outcome: String = """{"type":"exit","exit_code":7}""", includeCreatedBy: Boolean = true): String {
            val creator = if (includeCreatedBy) ""","created_by":"actor_output"""" else ""
            val entryCreator = if (includeCreatedBy) ""","created_by":"actor_entry"""" else ""
            return """{"type":"shell_call_output","id":"shell_output_item","call_id":"call_fixture","status":"completed","max_output_length":64,"output":[{"stdout":"shell-receipt","stderr":"stderr-receipt","outcome":$outcome$entryCreator}],"caller":{"type":"direct"}$creator}"""
        }

        fun response(vararg items: String, status: String = "completed", usage: String = "null") =
            """{"id":"resp_fixture","object":"response","model":"${model.id}","created_at":0,"status":"$status","parallel_tool_calls":false,"output":[${items.joinToString(",")}],"text":{"format":{"type":"text"}},"usage":$usage}"""

        fun itemEvent(state: String, index: Int, sequence: Int, item: String) =
            """{"type":"response.output_item.$state","output_index":$index,"sequence_number":$sequence,"item":$item}"""
    }
}
