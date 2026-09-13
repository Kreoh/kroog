package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.post
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Standalone GPT Image generation and editing over an existing authenticated [KoogHttpClient].
 * The caller owns the transport and its base URL, authentication, timeouts and lifecycle.
 * Paths are relative to that base URL, following [OpenAIFilesClient]. No automatic retries are added.
 *
 * Streaming is supported and tested with Ktor and OkHttp transports. The current `JavaKoogHttpClient`
 * SSE parser is incompatible: it forwards `event:` lines as payloads. Custom transports must deliver
 * decoded SSE data and release the connection on cancellation.
 *
 * Edits use JSON references, including data URLs, so streaming needs no multipart transport extension.
 * HTTP provider errors are exposed as [OpenAIImagesException] with the original transport cause.
 * Malformed responses and premature stream termination fail explicitly. Cancellation propagates unchanged.
 */
public class OpenAIImagesClient(
    private val httpClient: KoogHttpClient,
    private val generationsPath: String = "v1/images/generations",
    private val editsPath: String = "v1/images/edits",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val headers = mapOf("Content-Type" to "application/json")

    /** Generates completed images. The request must explicitly select a GPT Image model. */
    public suspend fun generate(request: OpenAIImageGenerationRequest): OpenAIImageResult =
        execute(generationsPath, generationBody(request))

    /** Edits supplied images, with optional mask and input fidelity. */
    public suspend fun edit(request: OpenAIImageEditRequest): OpenAIImageResult =
        execute(editsPath, editBody(request))

    /**
     * Streams zero to [partialImages] previews, then one completed image. Requires `n = 1`.
     * Each collection starts a new request. Cancelling collection closes the underlying stream.
     */
    public fun generateStreaming(
        request: OpenAIImageGenerationRequest,
        partialImages: Int = 1,
    ): Flow<OpenAIImageEvent> = stream(
        generationsPath,
        generationBody(request),
        "image_generation",
        request.options.n,
        partialImages,
    )

    /** Streams an edit with the same completion and cancellation contract as [generateStreaming]. */
    public fun editStreaming(
        request: OpenAIImageEditRequest,
        partialImages: Int = 1,
    ): Flow<OpenAIImageEvent> = stream(
        editsPath,
        editBody(request),
        "image_edit",
        request.options.n,
        partialImages,
    )

    private fun generationBody(request: OpenAIImageGenerationRequest): JsonObject =
        body(request.model, request.prompt, request.options)

    private fun editBody(request: OpenAIImageEditRequest): JsonObject {
        require(request.images.size in 1..16) { "Editing requires between 1 and 16 source images" }
        return buildJsonObject {
            body(request.model, request.prompt, request.options).forEach { (key, value) -> put(key, value) }
            put("images", json.encodeToJsonElement(request.images))
            request.mask?.let { put("mask", json.encodeToJsonElement(it)) }
            request.inputFidelity?.let { put("input_fidelity", json.encodeToJsonElement(it)) }
        }
    }

    private fun body(model: String, prompt: String, options: OpenAIImageOptions): JsonObject {
        require(model.isNotBlank()) { "An explicit image model is required" }
        require(prompt.isNotBlank()) { "An image prompt is required" }
        return buildJsonObject {
            json.encodeToJsonElement(options).jsonObject.forEach { (key, value) -> put(key, value) }
            put("model", model)
            put("prompt", prompt)
        }
    }

    private suspend fun execute(path: String, body: JsonObject): OpenAIImageResult {
        val response = try {
            httpClient.post<String, String>(path, body.toString(), headers = headers)
        } catch (exception: KoogHttpClientException) {
            throw providerFailure(exception)
        }
        val payload = json.parseToJsonElement(response).jsonObject
        checkProviderError(payload)
        return json.decodeFromJsonElement<OpenAIImageResult>(payload).also { result ->
            check(result.data.isNotEmpty()) { "OpenAI Images returned no completed images" }
            check(result.data.all { it.base64.isNotBlank() }) { "OpenAI Images returned an empty image" }
        }
    }

    private fun stream(
        path: String,
        body: JsonObject,
        eventPrefix: String,
        count: Int,
        partialImages: Int,
    ): Flow<OpenAIImageEvent> {
        require(count == 1) { "Image streaming requires n = 1" }
        require(partialImages in 0..3) { "Partial image count must be between 0 and 3" }
        val streamingBody = buildJsonObject {
            body.forEach { (key, value) -> put(key, value) }
            put("stream", true)
            put("partial_images", partialImages)
        }.toString()
        return flow {
            var completed = false
            try {
                // Retain callback transports' normal buffering while separating decoding from callbacks.
                httpClient.sse(
                    path = path,
                    requestBody = streamingBody,
                    requestBodyType = String::class,
                    dataFilter = { !it.isNullOrBlank() && it.trim() != "[DONE]" },
                    decodeStreamingResponse = { it },
                    processStreamingChunk = { it },
                    headers = headers,
                ).buffer().mapNotNull { raw ->
                    // Decode outside transport callbacks so provider errors retain their typed identity.
                    decodeEvent(json.parseToJsonElement(raw).jsonObject, eventPrefix)
                }.transformWhile { event ->
                    emit(event)
                    event !is OpenAIImageEvent.Completed
                }.collect { event ->
                    if (event is OpenAIImageEvent.Completed) completed = true
                    emit(event)
                }
            } catch (exception: KoogHttpClientException) {
                throw providerFailure(exception)
            }
            check(completed) { "OpenAI Images stream ended before a completed image arrived" }
        }
    }

    private fun decodeEvent(payload: JsonObject, prefix: String): OpenAIImageEvent? {
        checkProviderError(payload)
        val type = payload["type"]?.jsonPrimitive?.content
        if (type != "$prefix.partial_image" && type != "$prefix.completed") return null
        val image = json.decodeFromJsonElement<OpenAIGeneratedImage>(payload)
        check(image.base64.isNotBlank()) { "OpenAI Images streamed an empty image" }
        val metadata = json.decodeFromJsonElement<OpenAIImageEventMetadata>(payload)
        return if (type == "$prefix.partial_image") {
            val index = checkNotNull(payload["partial_image_index"]) { "Image preview omitted its index" }.jsonPrimitive.int
            check(index >= 0) { "Image preview index must be non-negative" }
            OpenAIImageEvent.PartialImage(index, image, metadata)
        } else {
            OpenAIImageEvent.Completed(
                image,
                metadata,
                payload["usage"]?.let { json.decodeFromJsonElement<OpenAIImageUsage?>(it) },
            )
        }
    }

    private fun checkProviderError(payload: JsonObject) {
        val error = payload["error"] as? JsonObject
            ?: payload.takeIf { it["type"]?.jsonPrimitive?.content == "error" }
        if (error != null) throw OpenAIImagesException(json.decodeFromJsonElement<OpenAIImageError>(error))
    }

    private fun providerFailure(cause: KoogHttpClientException): OpenAIImagesException {
        val error = try {
            cause.errorBody?.let { body ->
                val payload = json.parseToJsonElement(body).jsonObject
                (payload["error"] as? JsonObject)?.let { json.decodeFromJsonElement<OpenAIImageError>(it) }
            }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return OpenAIImagesException(error ?: OpenAIImageError("OpenAI Images HTTP request failed"), cause)
    }
}
