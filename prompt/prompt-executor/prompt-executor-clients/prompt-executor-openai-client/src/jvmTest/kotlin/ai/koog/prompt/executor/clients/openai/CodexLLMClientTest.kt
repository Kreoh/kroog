package ai.koog.prompt.executor.clients.openai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.Base64
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CodexLLMClientTest {
    private val prompt = Prompt.build("codex", params = OpenAIResponsesParams(temperature = 0.5, maxTokens = 10)) {
        system("Be brief")
        user("Hello")
    }
    private val model = OpenAIModels.Chat.GPT6_1Sol
    private val source = CodexCredentialSource { CodexCredentials("secret-token", "account-1") }

    @Test
    fun testExecuteUsesSseAndPreservesUsageAndRestrictions() = runTest {
        val http = Transport(listOf(text, """{"type":"response.metadata","metadata":{"moderation":{}}}""", completed, "[DONE]"))
        val client = CodexLLMClient(source, http)
        val result = client.execute(prompt, model)
        assertEquals("ok", result.parts.filterIsInstance<MessagePart.Text>().single().text)
        assertEquals(7, result.metaInfo.inputTokensCount)
        assertEquals(2, result.metaInfo.outputTokensCount)
        assertEquals(3, result.metaInfo.cacheReadTokensCount)
        assertEquals(1, result.metaInfo.reasoningTokensCount)
        val request = Json.parseToJsonElement(http.bodies.single()).jsonObject
        assertEquals("false", request["store"].toString())
        assertEquals("true", request["stream"].toString())
        assertEquals("Be brief", request["instructions"]!!.jsonPrimitive.content)
        assertFalse("temperature" in request)
        assertFalse("max_output_tokens" in request)
        assertEquals("Bearer secret-token", http.headers.single()["Authorization"])
        assertEquals("account-1", http.headers.single()["ChatGPT-Account-Id"])
        assertEquals("user", request["input"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content)
        client.close()
        assertTrue(http.closed)
    }

    @Test
    fun testAllEightModelsUseResponses() = runTest {
        listOf(
            OpenAIModels.Chat.GPT6_1Sol,
            OpenAIModels.Chat.GPT6_1Terra,
            OpenAIModels.Chat.GPT6Astra,
            OpenAIModels.Chat.GPT6Sol,
            OpenAIModels.Chat.GPT6Luna,
            OpenAIModels.Chat.GPT5_6Sol,
            OpenAIModels.Chat.GPT5_6Terra,
            OpenAIModels.Chat.GPT5_6Luna
        ).forEach { target ->
            val http = Transport(listOf(text, completed))
            CodexLLMClient(source, http).execute(prompt, target)
            assertEquals(target.id, Json.parseToJsonElement(http.bodies.single()).jsonObject["model"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun testHeaderlessCodexStreamUsesExistingJavaSseParser() = runTest {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val contentType = java.util.concurrent.atomic.AtomicReference<String>()
        server.createContext("/responses") { exchange ->
            contentType.set(exchange.requestHeaders.getFirst("Content-Type"))
            exchange.requestBody.use { it.readBytes() }
            val payload = "data: $text\n\ndata: $completed\n\n".toByteArray()
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val http = ai.koog.http.client.java.JavaKoogHttpClient.Factory().create(
                clientName = "local fixture",
                baseUrl = "http://127.0.0.1:${server.address.port}/",
            )
            CodexLLMClient(source, http).use { client ->
                val response = client.execute(prompt, model)
                assertEquals("ok", response.parts.filterIsInstance<MessagePart.Text>().single().text)
                assertEquals(7, response.metaInfo.inputTokensCount)
                assertEquals("application/json", contentType.get())
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testCapturedCodexStreamWithMetadataAndUnknownFields() = runTest {
        val events = javaClass.getResource("/codex/response-events.json")!!.readText()
        val http = Transport(Json.parseToJsonElement(events).jsonArray.map { it.toString() })
        val response = CodexLLMClient(source, http).execute(prompt, model)
        assertEquals("PONG", response.parts.filterIsInstance<MessagePart.Text>().single().text)
        assertEquals(11, response.metaInfo.inputTokensCount)
        assertEquals(6, response.metaInfo.outputTokensCount)
    }

    @Test
    fun testToolStreamAndResultContinuation() = runTest {
        val event = """{"type":"response.output_item.done","output_index":0,"sequence_number":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"lookup","arguments":"{}","status":"completed"}}"""
        val http = Transport(listOf(event, completed))
        val client = CodexLLMClient(source, http)
        val tools = listOf(ToolDescriptor("lookup", "Look up a value"))
        val response = client.execute(prompt, model, tools)
        val call = response.parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals("call_1", call.id)
        assertEquals("fc_1", call.providerItemId)
        val continuation = prompt.copy(
            messages = prompt.messages + response + Message.User(
                part = MessagePart.Tool.Result(id = call.id, tool = call.tool, output = "42"),
                metaInfo = RequestMetaInfo.Empty,
            )
        )
        client.execute(continuation, model, tools)
        val input = Json.parseToJsonElement(http.bodies.last()).jsonObject["input"]!!.jsonArray
        val replay = input.map { it.jsonObject }.first { it["type"]?.jsonPrimitive?.content == "function_call" }
        val result = input.map { it.jsonObject }.first { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertFalse("id" in replay)
        assertEquals("call_1", replay["call_id"]!!.jsonPrimitive.content)
        assertEquals("call_1", result["call_id"]!!.jsonPrimitive.content)
        assertEquals("42", result["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun testCacheIdentityBecomesHeaderAndUnsupportedToolsFailBeforeTransport() = runTest {
        val http = Transport(listOf(text, completed))
        val client = CodexLLMClient(source, http)
        client.execute(prompt.copy(params = OpenAIResponsesParams(promptCacheKey = "private-user-chat")), model)
        assertEquals(64, http.headers.single()["session_id"]!!.length)
        assertFalse(http.headers.single()["session_id"]!!.contains("private-user-chat"))
        assertFalse("prompt_cache_key" in Json.parseToJsonElement(http.bodies.single()).jsonObject)
        assertFailsWith<IllegalArgumentException> {
            client.execute(prompt.copy(params = OpenAIResponsesParams(codeInterpreter = OpenAICodeInterpreterConfig())), model)
        }
        assertEquals(1, http.bodies.size)
        assertFailsWith<IllegalArgumentException> { CodexCredentials("token\nheader", "account") }
        assertFailsWith<IllegalArgumentException> { CodexCredentials("", "account") }
    }

    @Test
    fun testWireNormalisationPreservesToolIdentityAndMidConversationInstructions() {
        val original = Json.parseToJsonElement("""{"input":[{"role":"system","content":"a"},{"role":"user","content":"hi"},{"role":"system","content":"b"},{"type":"function_call","id":"fc_1","call_id":"call_1","name":"lookup","arguments":"{}"},{"type":"function_call_output","call_id":"call_1","output":"42"}],"store":true,"stream":false,"max_output_tokens":1,"temperature":0.7,"top_p":0.2,"text":{"verbosity":"high","format":{"type":"json_schema"}}}""").jsonObject
        val request = codexRequest(original)
        assertEquals("a", request["instructions"]!!.jsonPrimitive.content)
        val input = request["input"]!!.jsonArray
        assertEquals("system", input[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertFalse("id" in input[2].jsonObject)
        assertEquals("call_1", input[2].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("call_1", input[3].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertFalse("verbosity" in request["text"]!!.jsonObject)
        assertTrue("format" in request["text"]!!.jsonObject)
        assertFalse("top_p" in request)
        assertFalse("max_output_tokens" in request)
    }

    @Test
    fun testEmptyInstructionsAndInvalidReasoning() {
        assertEquals("", codexRequest(Json.parseToJsonElement("""{"input":[]}""").jsonObject)["instructions"]!!.jsonPrimitive.content)
        listOf("none", "minimal", "ultra").forEach { effort ->
            assertFailsWith<IllegalArgumentException> {
                codexRequest(Json.parseToJsonElement("""{"input":[],"reasoning":{"effort":"$effort"}}""").jsonObject)
            }
        }
    }

    @Test
    fun testMissingTerminalFailsAndCancellationPropagates() = runTest {
        assertFailsWith<Exception> { CodexLLMClient(source, Transport(listOf(text))).execute(prompt, model) }
        val cancellation = CancellationException("caller cancelled")
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                CodexLLMClient(source, Transport(listOf(cancellation))).execute(prompt, model)
            }
        )
        val consumerFailure = IllegalStateException("consumer failed")
        assertSame(
            consumerFailure,
            assertFailsWith<IllegalStateException> {
                CodexLLMClient(source, Transport(listOf(text, completed))).executeStreaming(prompt, model).collect {
                    throw consumerFailure
                }
            }
        )
    }

    @Test
    fun testFailedEventAndHttpErrorsAreRedacted() = runTest {
        for (event in listOf("""{"type":"response.failed","secret":"secret-token"}""", """{"type":"error","message":"secret-token"}""", KoogHttpClientException(statusCode = 500, errorBody = "secret-token"))) {
            val failure = assertFailsWith<CodexTransportException> {
                CodexLLMClient(source, Transport(listOf(event))).execute(prompt, model)
            }
            assertFalse(failure.stackTraceToString().contains("secret-token"))
        }
        val failure = assertFailsWith<CodexAuthenticationException> {
            CodexLLMClient(source, Transport(listOf(KoogHttpClientException(statusCode = 401, errorBody = "secret-token")))).execute(prompt, model)
        }
        assertFalse(failure.stackTraceToString().contains("secret-token"))
    }

    @Test
    fun testCredentialReloadFailureAfterUnauthorizedPreservesRefreshGuidance() = runTest {
        for (reason in listOf("expired", "malformed")) {
            var loads = 0
            val authFailure = CodexAuthenticationException(
                "Configured Codex auth is $reason. Refresh this account with the external Codex CLI."
            )
            val failingReload = CodexCredentialSource {
                if (++loads == 1) CodexCredentials("secret-token", "account-1") else throw authFailure
            }
            val http = Transport(
                listOf(text, completed),
                firstFailure = KoogHttpClientException(statusCode = 401, errorBody = "secret-token"),
            )
            val failure = assertFailsWith<CodexAuthenticationException> {
                CodexLLMClient(failingReload, http).execute(prompt, model)
            }
            assertSame(authFailure, failure)
            assertTrue(failure.message!!.contains("Refresh this account"))
            assertFalse(failure.stackTraceToString().contains("secret-token"))
            assertEquals(2, loads)
            assertEquals(1, http.bodies.size)
        }
    }

    @Test
    fun testChangedTokenRetriesOnceAndEachRequestReloads() = runTest {
        var loads = 0
        val changing = CodexCredentialSource { CodexCredentials("token-${++loads}", "account-1") }
        val http = Transport(listOf(text, completed), firstFailure = KoogHttpClientException(statusCode = 401))
        val client = CodexLLMClient(changing, http)
        client.execute(prompt, model)
        client.execute(prompt, model)
        assertEquals(3, loads)
        assertEquals(listOf("Bearer token-1", "Bearer token-2", "Bearer token-3"), http.headers.map { it["Authorization"] })
    }

    @Test
    fun testAuthFileReloadExpiryMissingAndMalformedAreSafe() = runTest {
        val path = Files.createTempFile("codex-auth-test", ".json")
        try {
            val loader = CodexAuthFileSource(path)
            fun write(token: String, account: String = "account-1") {
                Files.writeString(path, """{"tokens":{"access_token":"$token","account_id":"$account"}}""")
            }
            val first = jwt("""{"exp":4102444800}""")
            write(first)
            assertEquals(first, loader.load().accessToken)
            write(jwt("""{"exp":4102444801}"""), "account-2")
            assertEquals("account-2", loader.load().accountId)
            assertFalse(loader.load().toString().contains("account-2"))
            write(jwt("""{"exp":1}"""))
            assertTrue(assertFailsWith<CodexAuthenticationException> { loader.load() }.message!!.contains("expired"))
            write(jwt("""{}"""))
            assertTrue(assertFailsWith<CodexAuthenticationException> { loader.load() }.message!!.contains("expiry"))
            val claimsAccount = jwt("""{"exp":4102444800,"https://api.openai.com/auth":{"chatgpt_account_id":"claims-account"}}""")
            Files.writeString(path, """{"tokens":{"access_token":"$claimsAccount"}}""")
            assertEquals("claims-account", loader.load().accountId)
            Files.writeString(path, "secret-token malformed JSON")
            assertFalse(assertFailsWith<CodexAuthenticationException> { loader.load() }.stackTraceToString().contains("secret-token"))
            Files.delete(path)
            assertTrue(assertFailsWith<CodexAuthenticationException> { loader.load() }.message!!.contains("missing"))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun jwt(payload: String): String = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".signature"

    private class Transport(private val events: List<Any>, private var firstFailure: Throwable? = null) : KoogHttpClient {
        override val clientName: String = "CodexTest"
        val bodies = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        var closed = false
        override suspend fun <R : Any> get(path: String, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = error("Unexpected GET")
        override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: KClass<T>, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R = error("Codex must stream")
        override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: KClass<T>, parameters: Map<String, String>, headers: Map<String, String>): Flow<String> = error("Unexpected lines")
        override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): Flow<O> = flow {
            assertEquals("responses", path)
            bodies += requestBody.toString()
            this@Transport.headers += headers
            firstFailure?.let {
                firstFailure = null
                throw it
            }
            events.forEach { event ->
                if (event is Throwable) throw event
                if (dataFilter(event as String)) processStreamingChunk(decodeStreamingResponse(event))?.let { emit(it) }
            }
        }
        override fun close() {
            closed = true
        }
    }

    private companion object {
        val text = """{"type":"response.output_text.done","item_id":"msg_1","output_index":0,"content_index":0,"text":"ok","sequence_number":1}"""
        val completed = """{"type":"response.completed","sequence_number":2,"response":{"created_at":1,"id":"resp_1","model":"gpt-6.1-sol","output":[],"parallel_tool_calls":false,"status":"completed","text":{},"usage":{"input_tokens":7,"output_tokens":2,"total_tokens":9,"input_tokens_details":{"cached_tokens":3},"output_tokens_details":{"reasoning_tokens":1}}}}"""
    }
}
