package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.http.client.test.MockWebServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class OpenAIImagesClientTest {
    @Test
    fun testGenerationEncodesOptionsAndDecodesMultipleImagesAndUsage() = runTest {
        val transport = ImageTransport(response = RESULT)
        val result = OpenAIImagesClient(transport).generate(
            OpenAIImageGenerationRequest(
                MODEL,
                "A green garden",
                OpenAIImageOptions(
                    n = 2,
                    size = "1536x864",
                    quality = OpenAIImageQuality.XHIGH,
                    background = OpenAIImageBackground.TRANSPARENT,
                    outputFormat = OpenAIImageOutputFormat.WEBP,
                    outputCompression = 75,
                    moderation = OpenAIImageModeration.LOW,
                    user = "test-user",
                ),
            )
        )
        assertEquals("v1/images/generations", transport.path)
        assertEquals(
            Json.parseToJsonElement(
                """{"model":"$MODEL","prompt":"A green garden","n":2,"size":"1536x864","quality":"xhigh","background":"transparent","output_format":"webp","output_compression":75,"moderation":"low","user":"test-user"}"""
            ),
            Json.parseToJsonElement(transport.body),
        )
        assertEquals(mapOf("Content-Type" to "application/json"), transport.headers)
        assertEquals(2, result.data.size)
        assertContentEquals(byteArrayOf(0, -1, 4), result.data.first().bytes())
        assertEquals(1, result.created)
        assertEquals(OpenAIImageOutputFormat.PNG, result.outputFormat)
        assertEquals(100, result.usage?.inputTokensCount)
        assertEquals(40, result.usage?.outputTokensCount)
        assertEquals(80, result.usage?.inputTokensDetails?.imageTokensCount)
        assertEquals(0, result.usage?.outputTokensDetails?.textTokensCount)
        val meta = result.usage!!.toResponseMetaInfo(Instant.fromEpochSeconds(1), MODEL)
        assertEquals(140, meta.totalTokensCount)
        assertEquals(100, meta.inputTokensCount)
        assertEquals(40, meta.outputTokensCount)
        assertEquals(MODEL, meta.modelId)
        assertNull(meta.cacheReadTokensCount)
    }

    @Test
    fun testMinimalRequestOmitsUnsetOptionsAndKeepsUnknownUsageUnknown() = runTest {
        val transport = ImageTransport()
        val result = OpenAIImagesClient(transport).generate(generation())
        assertEquals(setOf("model", "prompt"), Json.parseToJsonElement(transport.body).jsonObject.keys)
        assertNull(result.usage)
        val empty = OpenAIImageUsage().toResponseMetaInfo(Instant.fromEpochSeconds(1), MODEL)
        assertNull(empty.inputTokensCount)
        assertNull(empty.totalTokensCount)
        val zero = Json.decodeFromString<OpenAIImageUsage>("""{"input_tokens":0,"total_tokens":0,"input_tokens_details":{}}""")
        assertEquals(0, zero.inputTokensCount)
        assertNull(zero.inputTokensDetails?.imageTokensCount)
        assertEquals(0, zero.toResponseMetaInfo(Instant.fromEpochSeconds(1), MODEL).totalTokensCount)
        assertNull(zero.outputTokensCount)
    }

    @Test
    fun testEditEncodesBytesUrlFileIdMaskAndFidelityAsJsonReferences() = runTest {
        val transport = ImageTransport()
        val input = OpenAIImageInput.fromBytes(byteArrayOf(0, -1, 4), "image/png")
        val result = OpenAIImagesClient(transport).edit(
            OpenAIImageEditRequest(
                MODEL,
                "Make it green",
                listOf(input, OpenAIImageInput(fileId = "file_1"), OpenAIImageInput(imageUrl = "https://example.com/a.png")),
                mask = input,
                inputFidelity = OpenAIImageInputFidelity.HIGH,
            )
        )
        assertEquals("v1/images/edits", transport.path)
        val body = Json.parseToJsonElement(transport.body).jsonObject
        val images = body.getValue("images").jsonArray
        assertEquals("data:image/png;base64,AP8E", images[0].jsonObject.getValue("image_url").jsonPrimitive.content)
        assertEquals(setOf("file_id"), images[1].jsonObject.keys)
        assertEquals("https://example.com/a.png", images[2].jsonObject.getValue("image_url").jsonPrimitive.content)
        assertEquals(images[0], body["mask"])
        assertEquals("high", body.getValue("input_fidelity").jsonPrimitive.content)
        assertEquals(1, result.data.size)
    }

    @Test
    fun testGenerationAndEditStreamsEmitPreviewsThenCompletionWithFewerPreviewsThanRequested() = runTest {
        for (prefix in listOf("image_generation", "image_edit")) {
            val transport = ImageTransport(events = listOf(event(prefix, false), event(prefix, true, USAGE)))
            val client = OpenAIImagesClient(transport)
            val stream = if (prefix == "image_generation") {
                client.generateStreaming(generation(), 3)
            } else {
                client.editStreaming(edit(), 3)
            }
            assertEquals(0, transport.collections)
            repeat(2) {
                val events = stream.toList()
                assertEquals(2, events.size)
                assertEquals(0, assertIs<OpenAIImageEvent.PartialImage>(events[0]).index)
                val final = assertIs<OpenAIImageEvent.Completed>(events[1])
                assertEquals(140, final.usage?.totalTokensCount)
                assertEquals(1, final.metadata.createdAt)
                assertContentEquals(byteArrayOf(0, -1, 4), final.image.bytes())
            }
            assertEquals(2, transport.collections)
            assertTrue(transport.closed)
            val body = Json.parseToJsonElement(transport.body).jsonObject
            assertEquals("true", body.getValue("stream").jsonPrimitive.content)
            assertEquals("3", body.getValue("partial_images").jsonPrimitive.content)
            assertEquals(if (prefix == "image_edit") "v1/images/edits" else "v1/images/generations", transport.path)
            if (prefix == "image_edit") assertTrue("images" in body)
        }
    }

    @Test
    fun testCompletionWithoutPreviewsStopsWithoutWaitingForConnectionClose() = runTest {
        val transport = ImageTransport(events = listOf(event("image_generation", true)), hang = true)
        val result = OpenAIImagesClient(transport).generateStreaming(generation(), 0).toList()
        assertIs<OpenAIImageEvent.Completed>(result.single())
        assertNull((result.single() as OpenAIImageEvent.Completed).usage)
        assertTrue(transport.closed)
    }

    @Test
    fun testEmptyPartialOnlyAndDoneOnlyStreamsFail() = runTest {
        for (events in listOf(emptyList(), listOf(event("image_generation", false)), listOf("[DONE]"))) {
            val transport = ImageTransport(events = events)
            val failure = assertFailsWith<IllegalStateException> {
                OpenAIImagesClient(transport).generateStreaming(generation()).toList()
            }
            assertTrue(failure.message.orEmpty().contains("before a completed image"))
            assertTrue(transport.closed)
        }
    }

    @Test
    fun testUnknownEventsAndHeartbeatDataAreIgnored() = runTest {
        val transport = ImageTransport(events = listOf("", " ", """{"type":"image_generation.progress","future":true}""", event("image_generation", true)))
        assertEquals(1, OpenAIImagesClient(transport).generateStreaming(generation()).toList().size)
    }

    @Test
    fun testMalformedEventsAndMissingImageMetadataFail() = runTest {
        for (raw in listOf("{broken", """{"type":"image_generation.completed","b64_json":"AP8E"}""")) {
            val transport = ImageTransport(events = listOf(raw))
            assertFailsWith<SerializationException> {
                OpenAIImagesClient(transport).generateStreaming(generation()).toList()
            }
            assertTrue(transport.closed)
        }
    }

    @Test
    fun testMalformedEmptyAndMissingCompletedImagesFail() = runTest {
        for (raw in listOf("{broken", """{"created":1,"data":[{}]}""")) {
            assertFailsWith<SerializationException> { OpenAIImagesClient(ImageTransport(response = raw)).generate(generation()) }
        }
        for (raw in listOf("""{"created":1,"data":[]}""", """{"created":1,"data":[{"b64_json":""}]}""")) {
            assertFailsWith<IllegalStateException> { OpenAIImagesClient(ImageTransport(response = raw)).edit(edit()) }
        }
    }

    @Test
    fun testInBandProviderErrorsFailForBothEndpoints() = runTest {
        for (raw in listOf("""{"error":{"message":"rejected","code":"moderation_blocked"}}""", """{"type":"error","message":"rejected","code":"moderation_blocked"}""")) {
            val transport = ImageTransport(response = raw, events = listOf(raw))
            val client = OpenAIImagesClient(transport)
            val failures = listOf(
                assertFailsWith<OpenAIImagesException> { client.generate(generation()) },
                assertFailsWith<OpenAIImagesException> { client.edit(edit()) },
                assertFailsWith<OpenAIImagesException> { client.generateStreaming(generation()).toList() },
                assertFailsWith<OpenAIImagesException> { client.editStreaming(edit()).toList() },
            )
            failures.forEach { assertEquals("moderation_blocked", it.error.code) }
        }
    }

    @Test
    fun testHttpErrorsRetainStructuredDetailsAndTransportDiagnostics() = runTest {
        for (raw in listOf("""{"error":{"message":"quota","type":"rate_limit","param":"model","code":"quota"}}""", "<html>upstream unavailable</html>", "[]")) {
            val cause = KoogHttpClientException("images", 429, raw, mapOf("x-request-id" to listOf("req_1")), "req_1")
            val client = OpenAIImagesClient(ImageTransport(failure = cause))
            val failures = listOf(
                assertFailsWith<OpenAIImagesException> { client.generate(generation()) },
                assertFailsWith<OpenAIImagesException> { client.edit(edit()) },
                assertFailsWith<OpenAIImagesException> { client.generateStreaming(generation()).toList() },
                assertFailsWith<OpenAIImagesException> { client.editStreaming(edit()).toList() },
            )
            failures.forEach {
                assertSame(cause, it.cause)
                assertEquals("req_1", (it.cause as KoogHttpClientException).requestId)
                if (raw.startsWith("{")) assertEquals("quota", it.error.code)
            }
        }
    }

    @Test
    fun testCancellationPropagatesAndClosesStream() = runTest {
        val transport = ImageTransport(events = listOf(event("image_edit", false)), hang = true)
        val received = CompletableDeferred<Unit>()
        val job = launch {
            OpenAIImagesClient(transport).editStreaming(edit()).collect { received.complete(Unit) }
        }
        received.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(transport.closed)
        val cancelled = CancellationException("cancel request")
        val client = OpenAIImagesClient(ImageTransport(failure = cancelled))
        assertSame(cancelled, assertFailsWith<CancellationException> { client.generate(generation()) })
        assertSame(cancelled, assertFailsWith<CancellationException> { client.edit(edit()) })
        // Coroutine stack-trace recovery can copy cancellation exceptions across a channel boundary.
        val streamingCancellation = assertFailsWith<CancellationException> { client.generateStreaming(generation()).toList() }
        assertEquals(cancelled.message, streamingCancellation.message)
    }

    @Test
    fun testTakingFirstPreviewClosesUpstream() = runTest {
        val transport = ImageTransport(events = listOf(event("image_generation", false)), hang = true)
        assertIs<OpenAIImageEvent.PartialImage>(OpenAIImagesClient(transport).generateStreaming(generation()).first())
        assertTrue(transport.closed)
    }

    @Test
    fun testInvalidRequestsFailBeforeTransport() = runTest {
        val transport = ImageTransport()
        val client = OpenAIImagesClient(transport)
        assertFailsWith<IllegalArgumentException> { client.generate(generation().copy(model = " ")) }
        assertFailsWith<IllegalArgumentException> { client.edit(edit().copy(prompt = "")) }
        assertFailsWith<IllegalArgumentException> { client.edit(edit().copy(images = emptyList())) }
        assertFailsWith<IllegalArgumentException> { client.edit(edit().copy(images = List(17) { OpenAIImageInput(fileId = "file") })) }
        for (count in listOf(-1, 4)) {
            assertFailsWith<IllegalArgumentException> { client.generateStreaming(generation(), count) }
            assertFailsWith<IllegalArgumentException> { client.editStreaming(edit(), count) }
        }
        assertFailsWith<IllegalArgumentException> { client.generateStreaming(generation().copy(options = OpenAIImageOptions(n = 2))) }
        assertFailsWith<IllegalArgumentException> { client.editStreaming(edit().copy(options = OpenAIImageOptions(n = 2))) }
        assertEquals("", transport.path)
    }

    @Test
    fun testInvalidInputsAndOptionsAreRejected() {
        assertFailsWith<IllegalArgumentException> { OpenAIImageInput() }
        assertFailsWith<IllegalArgumentException> { OpenAIImageInput(imageUrl = "url", fileId = "file") }
        assertFailsWith<IllegalArgumentException> { OpenAIImageInput(fileId = " ") }
        assertFailsWith<IllegalArgumentException> { OpenAIImageInput(imageUrl = " ") }
        assertFailsWith<IllegalArgumentException> { OpenAIImageInput.fromBytes(byteArrayOf(), "image/png") }
        assertFailsWith<IllegalArgumentException> { OpenAIImageInput.fromBytes(byteArrayOf(1), "text/plain") }
        for (n in listOf(0, 11)) assertFailsWith<IllegalArgumentException> { OpenAIImageOptions(n = n) }
        for (compression in listOf(-1, 101)) assertFailsWith<IllegalArgumentException> { OpenAIImageOptions(outputCompression = compression) }
        assertFailsWith<IllegalArgumentException> {
            OpenAIImageOptions(background = OpenAIImageBackground.TRANSPARENT, outputFormat = OpenAIImageOutputFormat.JPEG)
        }
        assertFailsWith<IllegalArgumentException> { OpenAIGeneratedImage("!").bytes() }
    }

    @Test
    fun testExistingKtorTransportSendsJsonAndDecodesActualSseFrames() = runTest {
        for (streaming in listOf(false, true)) {
            val server = MockWebServer()
            var observed: MockWebServer.RawRequest? = null
            val response = if (streaming) {
                ": heartbeat\n\nevent: image_edit.partial_image\ndata: ${event("image_edit", false)}\n\nevent: image_edit.completed\ndata: ${event("image_edit", true)}\n\n"
            } else {
                RESULT
            }
            server.start(
                rawEndpoints = listOf(
                    MockWebServer.RawEndpointConfig(
                        path = "/v1/images/edits",
                        method = HttpMethod.Post,
                        responseBody = response.encodeToByteArray(),
                        contentType = if (streaming) ContentType.Text.EventStream else ContentType.Application.Json,
                        onRequest = { observed = it },
                    )
                )
            )
            val base = HttpClient(CIO)
            val transport = KtorKoogHttpClient.Factory(baseClient = base).create(
                clientName = "images",
                baseUrl = server.url(""),
                headers = mapOf("Authorization" to "Bearer test-key"),
            )
            try {
                val client = OpenAIImagesClient(transport)
                if (streaming) {
                    assertEquals(2, client.editStreaming(edit()).toList().size)
                } else {
                    assertEquals(2, client.edit(edit()).data.size)
                }
                assertEquals("Bearer test-key", observed?.headers?.get(HttpHeaders.Authorization))
                assertTrue(observed!!.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
                assertTrue(observed.body.decodeToString().contains("\"images\""))
            } finally {
                transport.close()
                base.close()
                server.stop()
            }
        }
    }

    @Test
    fun testRealSsePreservesInBandErrorsAndRejectsPrematureTermination() = runTest {
        for (response in listOf(
            "data: {\"type\":\"error\",\"message\":\"rejected\",\"code\":\"moderation_blocked\"}\n\n",
            "data: ${event("image_edit", false)}\n\n",
        )) {
            val server = MockWebServer()
            server.start(
                rawEndpoints = listOf(
                    MockWebServer.RawEndpointConfig(
                        path = "/v1/images/edits",
                        method = HttpMethod.Post,
                        responseBody = response.encodeToByteArray(),
                        contentType = ContentType.Text.EventStream,
                    )
                )
            )
            val base = HttpClient(CIO)
            val transport = KtorKoogHttpClient.Factory(baseClient = base).create(clientName = "images", baseUrl = server.url(""))
            try {
                val client = OpenAIImagesClient(transport)
                if (response.contains("moderation_blocked")) {
                    val error = assertFailsWith<OpenAIImagesException> { client.editStreaming(edit()).toList() }
                    assertEquals("moderation_blocked", error.error.code)
                } else {
                    assertFailsWith<IllegalStateException> { client.editStreaming(edit()).toList() }
                }
            } finally {
                transport.close()
                base.close()
                server.stop()
            }
        }
    }

    @Test
    fun testExistingKtorTransportPreservesHttpFailure() = runTest {
        val base = HttpClient(
            MockEngine {
                respond(
                    """{"error":{"message":"quota","code":"quota"}}""",
                    HttpStatusCode.TooManyRequests,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"), "x-request-id" to listOf("req_2")),
                )
            }
        )
        val transport = KtorKoogHttpClient.Factory(baseClient = base).create(clientName = "images", baseUrl = "https://example.com")
        try {
            val client = OpenAIImagesClient(transport)
            val error = assertFailsWith<OpenAIImagesException> { client.generate(generation()) }
            assertEquals("quota", error.error.code)
            assertEquals("req_2", assertIs<KoogHttpClientException>(error.cause).requestId)
        } finally {
            transport.close()
            base.close()
        }
    }
}

private const val MODEL = "gpt-image-2.5-sunburst"
private const val USAGE = """{"input_tokens":100,"output_tokens":40,"total_tokens":140,"input_tokens_details":{"image_tokens":80,"text_tokens":20},"output_tokens_details":{"image_tokens":40,"text_tokens":0}}"""
private const val RESULT = """{"created":1,"data":[{"b64_json":"AP8E"},{"b64_json":"AQ=="}],"output_format":"png","usage":$USAGE,"future_field":true}"""
private fun generation() = OpenAIImageGenerationRequest(MODEL, "A green garden")
private fun edit() = OpenAIImageEditRequest(MODEL, "Make it green", listOf(OpenAIImageInput(fileId = "file_1")))
private fun event(prefix: String, completed: Boolean, usage: String? = null): String {
    val suffix = if (completed) "completed" else "partial_image"
    val details = if (completed) usage?.let { "\"usage\":$it," }.orEmpty() else "\"partial_image_index\":0,"
    return """{"type":"$prefix.$suffix",$details"b64_json":"AP8E","created_at":1,"output_format":"png","quality":"high","background":"opaque","size":"1024x1024"}"""
}

private class ImageTransport(
    private val response: String = """{"created":1,"data":[{"b64_json":"AP8E"}]}""",
    private val events: List<String> = emptyList(),
    private val failure: Throwable? = null,
    private val hang: Boolean = false,
) : KoogHttpClient {
    override val clientName = "images-test"
    var path = ""
    var body = ""
    var headers: Map<String, String> = emptyMap()
    var collections = 0
    var closed = false

    override suspend fun <R : Any> get(path: String, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R =
        error("Unexpected GET")

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R {
        this.path = path
        this.body = requestBody.toString()
        this.headers = headers
        failure?.let { throw it }
        return responseType.java.cast(response)
    }

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
        this@ImageTransport.path = path
        body = requestBody.toString()
        this@ImageTransport.headers = headers
        collections++
        closed = false
        try {
            failure?.let { throw it }
            events.filter(dataFilter).forEach { raw -> processStreamingChunk(decodeStreamingResponse(raw))?.let { emit(it) } }
            if (hang) awaitCancellation()
        } finally {
            closed = true
        }
    }

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> = error("Unexpected line stream")

    override fun close() = Unit
}
