package ai.koog.integration.tests.client

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.bedrock.BedrockAPIMethod
import ai.koog.prompt.executor.clients.bedrock.BedrockClientSettings
import ai.koog.prompt.executor.clients.bedrock.BedrockLLMClient
import ai.koog.prompt.executor.clients.bedrock.BedrockMantleLLMClient
import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import ai.koog.prompt.executor.clients.bedrock.converse.BedrockConverseParams
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekParams
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.toMessageResponse
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Live model checks. Missing credentials fail explicitly; no mocked transport is used. */
class NewProviderModelsIntegrationTest {
    @Test
    fun integration_testDeepSeekV41Flash() = runTest(timeout = 240.seconds) {
        DeepSeekLLMClient(credential("DEEPSEEK_API_TEST_KEY", "DEEPSEEK_API_KEY")).use { client ->
            val params = DeepSeekParams(maxTokens = 2048, additionalProperties = mapOf(
                "thinking" to buildJsonObject { put("type", "disabled") },
            ))
            checkGeneration(client, DeepSeekModels.DeepSeekV4_1Flash, params)
            val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().let { graphics ->
                try {
                    graphics.color = Color.RED
                    graphics.fillRect(0, 0, image.width, image.height)
                } finally {
                    graphics.dispose()
                }
            }
            val bytes = ByteArrayOutputStream().use { output ->
                check(ImageIO.write(image, "png", output))
                output.toByteArray()
            }
            // Check the stable ID and both provider-maintained legacy aliases with real image input.
            for (model in listOf(DeepSeekModels.DeepSeekV4_1Flash, DeepSeekModels.DeepSeekV4Flash,
                DeepSeekModels.DeepSeekV4FlashVisionExp)) {
                val response = client.execute(prompt("live-flash-image", params = params) { user {
                    text("What is the dominant colour of this image? Reply with one English colour word.")
                    image(AttachmentSource.Image(AttachmentContent.Binary.Bytes(bytes), "png"))
                } }, model, emptyList())
                assertTrue(response.parts.filterIsInstance<MessagePart.Text>().joinToString { it.text }
                    .contains("red", ignoreCase = true), "Image answer should identify red for ${model.id}")
            }
        }
    }

    @Test
    fun integration_testBedrockRuntimeOpus55() = runTest(timeout = 240.seconds) {
        checkRuntime(BedrockModels.AnthropicClaude55Opus)
    }

    @Test
    fun integration_testBedrockRuntimeSonnet55() = runTest(timeout = 240.seconds) {
        checkRuntime(BedrockModels.AnthropicClaude55Sonnet)
    }

    @Test
    fun integration_testBedrockRuntimeAstra() = runTest(timeout = 240.seconds) {
        checkRuntime(BedrockModels.OpenAIGpt6Astra)
    }

    @Test
    fun integration_testBedrockNovaEmbeddings() = runTest(timeout = 120.seconds) {
        runtimeClient("us-east-1").use { client ->
            val vector = client.embed("A red image is a visual input.", BedrockModels.Embeddings.AmazonNova2MultimodalEmbeddings)
            assertEquals(3072, vector.size)
            assertTrue(vector.all(Double::isFinite))
            assertTrue(vector.any { it != 0.0 })
        }
    }

    @Test
    fun integration_testBedrockMantleGemma31B() = runTest(timeout = 240.seconds) {
        checkMantle(BedrockModels.GoogleGemma4_31B)
    }

    @Test
    fun integration_testBedrockMantleGemma26BA4B() = runTest(timeout = 240.seconds) {
        checkMantle(BedrockModels.GoogleGemma4_26BA4B)
    }

    @Test
    fun integration_testBedrockMantleGemmaE2B() = runTest(timeout = 240.seconds) {
        checkMantle(BedrockModels.GoogleGemma4E2B)
    }

    @Test
    fun integration_testBedrockMantleAstra() = runTest(timeout = 240.seconds) {
        checkMantle(BedrockModels.OpenAIGpt6Astra)
    }

    private suspend fun checkRuntime(model: LLModel, params: LLMParams = BedrockConverseParams(maxTokens = 4096)) {
        runtimeClient().use { checkGeneration(it, model, params) }
    }

    private fun runtimeClient(region: String = System.getenv("AWS_REGION") ?: "us-west-2"): BedrockLLMClient = BedrockLLMClient(
        identityProvider = StaticCredentialsProvider {
            accessKeyId = credential("AWS_ACCESS_KEY_ID")
            secretAccessKey = credential("AWS_SECRET_ACCESS_KEY")
            sessionToken = System.getenv("AWS_SESSION_TOKEN")?.takeIf(String::isNotBlank)
        },
        settings = BedrockClientSettings(
            region = region,
            apiMethod = BedrockAPIMethod.Converse,
            maxRetries = 0,
        ),
    )

    private fun mantleClient(): BedrockMantleLLMClient {
        val factory = HttpClientFactoryResolver.resolve()
        System.getenv("AWS_BEARER_TOKEN_BEDROCK")?.takeIf(String::isNotBlank)?.let {
            return BedrockMantleLLMClient(it, factory)
        }
        val delegate = factory.create(
            clientName = "MantleLiveSigV4",
            baseUrl = "https://bedrock-mantle.us-west-2.api.aws",
            json = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
                namingStrategy = JsonNamingStrategy.SnakeCase
            },
        )
        val signed = object : KoogHttpClient by delegate {
            override suspend fun <T : Any, R : Any> post(
                path: String, requestBody: T, requestBodyType: KClass<T>, responseType: KClass<R>,
                parameters: Map<String, String>, headers: Map<String, String>,
            ): R {
                check(parameters.isEmpty())
                return delegate.post(path, requestBody, requestBodyType, responseType, parameters,
                    headers + signedHeaders(path, requestBody as String))
            }

            override fun <T : Any, R : Any, O : Any> sse(
                path: String, requestBody: T, requestBodyType: KClass<T>, dataFilter: (String?) -> Boolean,
                decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?,
                parameters: Map<String, String>, headers: Map<String, String>,
            ): Flow<O> {
                check(parameters.isEmpty())
                return delegate.sse(path, requestBody, requestBodyType, dataFilter, decodeStreamingResponse,
                    processStreamingChunk, parameters, headers + signedHeaders(path, requestBody as String))
            }

            override fun <T : Any> lines(
                path: String, requestBody: T, requestBodyType: KClass<T>,
                parameters: Map<String, String>, headers: Map<String, String>,
            ): Flow<String> {
                check(parameters.isEmpty())
                return delegate.lines(path, requestBody, requestBodyType, parameters,
                    headers + signedHeaders(path, requestBody as String))
            }
        }
        return BedrockMantleLLMClient(signed)
    }

    // Test-only SigV4 authentication for the injected HTTP client. Credentials stay in memory.
    private fun signedHeaders(path: String, body: String): Map<String, String> {
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
        fun hash(value: String): String = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
        fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value.toByteArray())
        }
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC).format(Instant.now())
        val date = timestamp.take(8)
        val scope = "$date/us-west-2/bedrock-mantle/aws4_request"
        val headers = sortedMapOf("host" to "bedrock-mantle.us-west-2.api.aws", "x-amz-date" to timestamp)
        System.getenv("AWS_SESSION_TOKEN")?.takeIf(String::isNotBlank)?.let { headers["x-amz-security-token"] = it }
        val names = headers.keys.joinToString(";")
        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val canonical = "POST\n/${path.trimStart('/')}\n\n$canonicalHeaders\n$names\n${hash(body)}"
        val toSign = "AWS4-HMAC-SHA256\n$timestamp\n$scope\n${hash(canonical)}"
        val signingKey = hmac(hmac(hmac(hmac(("AWS4" + credential("AWS_SECRET_ACCESS_KEY")).toByteArray(), date),
            "us-west-2"), "bedrock-mantle"), "aws4_request")
        val signature = hex(hmac(signingKey, toSign))
        return headers.filterKeys { it != "host" } + ("Authorization" to
            "AWS4-HMAC-SHA256 Credential=${credential("AWS_ACCESS_KEY_ID")}/$scope, SignedHeaders=$names, Signature=$signature")
    }

    private suspend fun checkMantle(model: LLModel) {
        mantleClient().use { client ->
            checkGeneration(client, model, OpenAIResponsesParams(
                maxTokens = 2048,
                reasoning = ReasoningConfig(effort = ReasoningEffort.LOW),
                stateless = true,
            ))
            val reply = client.execute(prompt("live-mantle-chat", params = OpenAIChatParams(
                maxTokens = 2048, reasoningEffort = ReasoningEffort.LOW,
            )) { user("Reply with the single word PONG.") }, model, emptyList())
            assertTrue(reply.parts.filterIsInstance<MessagePart.Text>().joinToString { it.text }.contains("PONG"))
        }
    }

    private suspend fun checkGeneration(client: LLMClient, model: LLModel, params: LLMParams) {
        val streamed = client.executeStreaming(prompt("live-new-model-stream", params = params) {
            user("Reply with the single word PONG.")
        }, model).toList().toMessageResponse()
        assertTrue(streamed.parts.filterIsInstance<MessagePart.Text>().joinToString { it.text }.contains("PONG"))
        assertTrue((streamed.metaInfo.inputTokensCount ?: 0) > 0)
        val called = client.execute(prompt("live-new-model-tools", params = params) {
            user("Call read_probe, then reply with only the returned value.")
        }, model, listOf(probeTool))
        val call = called.parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals("read_probe", call.tool)
        val answer = client.execute(prompt("live-new-model-replay", params = params) {
            user("Call read_probe, then reply with only the returned value.")
            message(called)
            toolResult(tool = call.tool, output = "probe-739", id = call.id)
        }, model, listOf(probeTool))
        assertTrue(answer.parts.filterIsInstance<MessagePart.Text>().joinToString { it.text }.contains("probe-739"))
        assertTrue((answer.metaInfo.outputTokensCount ?: 0) > 0)
    }

    private fun credential(vararg names: String): String = names.firstNotNullOfOrNull {
        System.getenv(it)?.takeIf(String::isNotBlank)
    } ?: error("Set ${names.first()} to run this live test")

    private val probeTool = ToolDescriptor("read_probe", "Read the probe value. Call this to obtain the value; it cannot be inferred.")
}
