package ai.koog.prompt.executor.clients.openai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass

/** An access token and account identity. String representations never include either value. */
public class CodexCredentials(public val accessToken: String, public val accountId: String) {
    init {
        require(accessToken.isNotBlank() && accountId.isNotBlank()) { "Codex credentials require a token and account identity" }
        require(listOf(accessToken, accountId).none { '\r' in it || '\n' in it }) { "Invalid Codex credentials" }
    }

    override fun toString(): String = "CodexCredentials(<redacted>)"
}

/** Supplies current credentials. Login and refresh belong to the caller or external Codex CLI. */
public fun interface CodexCredentialSource {
    /** Reloads current credentials without performing login or OAuth refresh. */
    public suspend fun load(): CodexCredentials
}

/** Actionable credential failure with no credential contents or underlying parser error. */
public class CodexAuthenticationException(message: String) : IllegalStateException(message)

/** Sanitised transport/protocol error. Provider bodies and request headers are deliberately excluded. */
public class CodexTransportException(message: String) : IllegalStateException(message)

/**
 * Codex account Responses transport using Kroog's existing Responses conversion and stream assembly.
 * Every request streams with store=false, including [execute]. Tool execution remains owned by Kroog.
 * Credentials are explicit and reloaded for each collection; this client never launches the Codex CLI.
 * Hosted Code Interpreter, moderation and embeddings are not supported by this route.
 *
 * The supplied HTTP client must target the Codex backend (normally https://chatgpt.com/backend-api/codex/).
 * Its ownership transfers to this client. Configure any HTTP logging to redact authentication headers.
 * Codex may omit the SSE Content-Type header. On JVM use an explicit JavaKoogHttpClient.Factory;
 * strict SSE implementations such as Ktor reject such responses. No default factory is selected here.
 */
public class CodexLLMClient(
    credentials: CodexCredentialSource,
    httpClient: KoogHttpClient,
) : LLMClient() {
    private val delegate = OpenAILLMClient(
        settings = OpenAIClientSettings(responsesAPIPath = "responses"),
        httpClient = CodexHttpClient(httpClient, credentials),
    )

    /** Creates the transport with the official backend and caller-selected HTTP implementation. */
    public constructor(
        credentials: CodexCredentialSource,
        httpClientFactory: KoogHttpClient.Factory,
        timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig(),
    ) : this(
        credentials,
        httpClientFactory.create(
            clientName = "Codex",
            baseUrl = "https://chatgpt.com/backend-api/codex/",
            requestTimeoutMillis = timeoutConfig.requestTimeoutMillis,
            connectTimeoutMillis = timeoutConfig.connectTimeoutMillis,
            socketTimeoutMillis = timeoutConfig.socketTimeoutMillis,
        ),
    )

    override fun llmProvider(): LLMProvider = LLMProvider.OpenAI

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        executeStreaming(prompt, model, tools).toList().toMessageResponse()

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = flow {
        require(prompt.params !is OpenAIChatParams) { "Codex requires Responses parameters" }
        val params = prompt.params.toOpenAIResponsesParams()
        require(params.codeInterpreter == null) { "Codex does not support hosted Code Interpreter" }
        require(params.numberOfChoices == null || params.numberOfChoices == 1) { "Codex supports one choice per request" }
        emitAll(
            delegate.executeStreaming(prompt.copy(params = params.asStateless()), model, tools).catch { failure ->
                val credentialFailure = generateSequence(failure as Throwable?) { it.cause }
                    .firstOrNull { it is CodexAuthenticationException || it is CodexTransportException }
                throw credentialFailure ?: failure
            }
        )
    }

    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): List<Message.Assistant> =
        listOf(execute(prompt, model, tools))

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        throw UnsupportedOperationException("Codex does not support moderation")

    override fun getStandardJsonSchemaGenerator(): ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator = delegate.getStandardJsonSchemaGenerator()
    override fun getBasicJsonSchemaGenerator(): ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator = delegate.getBasicJsonSchemaGenerator()
    override fun close(): Unit = delegate.close()
}

private class CodexHttpClient(
    private val delegate: KoogHttpClient,
    private val credentials: CodexCredentialSource,
) : KoogHttpClient by delegate {
    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> = flow {
        val original = Json.parseToJsonElement(requestBody as String).jsonObject
        val body = codexRequest(original).toString()
        var auth = credentials.load()
        var emitted = false
        flow {
            emitAll(
                delegate.sse(
                    path,
                    body,
                    String::class,
                    dataFilter = { data ->
                        dataFilter(data) &&
                            data != "[DONE]" &&
                            data != null &&
                            Json.parseToJsonElement(data).jsonObject["type"]?.jsonPrimitive?.content != "response.metadata"
                    },
                    decodeStreamingResponse = { data ->
                        val type = Json.parseToJsonElement(data).jsonObject["type"]?.jsonPrimitive?.content
                        if (type == "response.failed" || type == "error") {
                            throw CodexTransportException("Codex response failed; no complete response was returned")
                        }
                        decodeStreamingResponse(data)
                    },
                    processStreamingChunk = processStreamingChunk,
                    parameters = parameters,
                    headers = headers + buildMap {
                        put("Content-Type", "application/json")
                        put("Authorization", "Bearer ${auth.accessToken}")
                        put("ChatGPT-Account-Id", auth.accountId)
                        put("originator", "codex_cli_rs")
                        put("User-Agent", "codex_cli_rs/0.0.0 kroog")
                        original["prompt_cache_key"]?.jsonPrimitive?.contentOrNull?.let {
                            require(it.length <= 64) { "Codex session identity exceeds 64 characters" }
                            put("session_id", it)
                        }
                    },
                )
            )
        }.retryWhen { failure, attempt ->
            val http = generateSequence(failure as Throwable?) { it.cause }
                .filterIsInstance<KoogHttpClientException>().firstOrNull()
            if (http?.statusCode == 401 && attempt == 0L && !emitted) {
                val refreshed = credentials.load()
                if (refreshed.accessToken != auth.accessToken) {
                    auth = refreshed
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }.catch { failure ->
            if (failure is CancellationException || failure is CodexAuthenticationException) throw failure
            val http = generateSequence(failure as Throwable?) { it.cause }
                .filterIsInstance<KoogHttpClientException>().firstOrNull()
            if (http?.statusCode == 401 || http?.statusCode == 403) {
                throw CodexAuthenticationException("Codex authentication rejected. Refresh the configured account with the Codex CLI and retry.")
            }
            throw CodexTransportException(
                "Codex response failed" + (http?.statusCode?.let { " (HTTP $it)" } ?: "") + "; no complete response was returned"
            )
        }.collect {
            emitted = true
            emit(it)
        }
    }
}

internal fun codexRequest(original: JsonObject): JsonObject {
    val request = original.toMutableMap()
    val effort = original["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content
    require(effort == null || effort in setOf("low", "medium", "high", "xhigh", "max")) {
        "Codex supports reasoning efforts low, medium, high, xhigh and max"
    }
    require(original["tools"]?.jsonArray.orEmpty().all { it.jsonObject["type"]?.jsonPrimitive?.content == "function" }) {
        "Codex supports caller-managed function tools only"
    }
    setOf(
        "temperature", "top_p", "top_logprobs", "logprobs", "max_output_tokens", "max_tokens", "max_tool_calls",
        "prompt_cache_key", "background", "truncation", "safety_identifier", "context_management", "previous_response_id",
    ).forEach(request::remove)
    request["store"] = JsonPrimitive(false)
    request["stream"] = JsonPrimitive(true)
    val instructions = mutableListOf<String>()
    var leading = true
    request["input"] = JsonArray(
        original["input"]!!.jsonArray.mapNotNull { element ->
            val item = element.jsonObject
            if (leading && item["role"]?.jsonPrimitive?.content in setOf("system", "developer")) {
                val content = item["content"]
                when (content) {
                    is JsonPrimitive -> instructions.add(content.content)
                    is JsonArray -> content.forEach { part -> part.jsonObject["text"]?.jsonPrimitive?.content?.let(instructions::add) }
                    else -> Unit
                }
                null
            } else {
                leading = false
                JsonObject(item - "id")
            }
        }
    )
    request["instructions"] = JsonPrimitive(instructions.joinToString("\n\n"))
    original["text"]?.jsonObject?.let { text ->
        val config = text - "verbosity"
        if (config.isEmpty()) request.remove("text") else request["text"] = JsonObject(config)
    }
    return JsonObject(request)
}
