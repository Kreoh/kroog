package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParityTest.ScriptedResponsesTransport
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAIHostedShellTest {
    @Test
    fun testStreamsShellExecutionAndReplaysCommandsAndOutputsInTheSameContainer() = runTest {
        val requested = call()
        val returned = output()
        val frames = listOf(
            itemEvent("added", 0, 1, call(status = "in_progress")),
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
            val progress = assertIs<MessagePart.HostedExecution.Progress>(updates[0])
            val request = assertIs<MessagePart.HostedExecution.Request>(updates[1])
            val result = assertIs<MessagePart.HostedExecution.Result>(updates[2])
            assertEquals(3, updates.size)
            assertEquals("printf shell-receipt\nprintf stderr-receipt >&2", request.code)
            assertEquals("bash", request.language)
            assertEquals("shell-receipt\nstderr-receipt", result.output)
            assertEquals(7, result.exitCode)
            assertEquals(request.executionId, progress.executionId)
            assertEquals(request.executionId, result.executionId)
            assertEquals("cntr_fixture", result.containerId)
            assertEquals("shell_output_item", result.providerItemId)
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
            assertEquals(Json.parseToJsonElement(requested), input[0])
            assertEquals(Json.parseToJsonElement(returned), input[1])
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

    private fun JsonObject.shellEnvironment(): JsonObject {
        val tool = getValue("tools").jsonArray.single().jsonObject
        assertEquals("shell", tool.getValue("type").jsonPrimitive.content)
        return tool.getValue("environment").jsonObject
    }

    private class Reply(
        val body: String,
        val status: HttpStatusCode = HttpStatusCode.OK,
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
            assertEquals("/v1/responses", request.url.encodedPath)
            requests.add(Json.parseToJsonElement((request.body as TextContent).text).jsonObject)
            val reply = remaining.removeFirst()
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

        fun call(status: String = "completed") =
            """{"type":"shell_call","id":"shell_tool_item","call_id":"call_fixture","status":"$status","action":{"commands":["printf shell-receipt","printf stderr-receipt >&2"],"timeout_ms":1000,"max_output_length":64},"environment":{"type":"container_reference","container_id":"cntr_fixture"}}"""

        fun output(outcome: String = """{"type":"exit","exit_code":7}""") =
            """{"type":"shell_call_output","id":"shell_output_item","call_id":"call_fixture","status":"completed","max_output_length":64,"output":[{"stdout":"shell-receipt","stderr":"stderr-receipt","outcome":$outcome}]}"""

        fun response(vararg items: String) =
            """{"id":"resp_fixture","object":"response","model":"${model.id}","created_at":0,"status":"completed","parallel_tool_calls":false,"output":[${items.joinToString(",")}],"text":{"format":{"type":"text"}}}"""

        fun itemEvent(state: String, index: Int, sequence: Int, item: String) =
            """{"type":"response.output_item.$state","output_index":$index,"sequence_number":$sequence,"item":$item}"""
    }
}
