# OpenAI Images

`OpenAIImagesClient` generates and edits images independently of chat and Responses requests. It reuses an authenticated `KoogHttpClient`, just like `OpenAIFilesClient`. The caller owns that transport, including its base URL, credentials, timeouts and closure.

```kotlin
val images = OpenAIImagesClient(httpClient)
val request = OpenAIImageGenerationRequest(
    model = "gpt-image-2.5-sunburst",
    prompt = "A watercolour of a green garden",
    options = OpenAIImageOptions(
        outputFormat = OpenAIImageOutputFormat.PNG,
        quality = OpenAIImageQuality.HIGH,
    ),
)
val result = images.generate(request)
val bytes = result.data.single().bytes()
```

The initial model selected for the ChatUI handoff is `gpt-image-2.5-sunburst`. The client requires an explicit model string and does not infer it from the conversation model or hard-code a default. Availability and model-specific option combinations remain provider-validated. This API targets GPT Image models, with base64 outputs; legacy DALL-E URL outputs and variations are outside its contract.

## Editing

Edits use the supported JSON request format. Each input contains exactly one `image_url` or `file_id`. `fromBytes` makes a base64 data URL, so supplied images need no preliminary upload or multipart request. Callers can also supply HTTPS URLs or previously uploaded provider file IDs.

```kotlin
val edit = OpenAIImageEditRequest(
    model = request.model,
    prompt = "Add a red bench",
    images = listOf(OpenAIImageInput.fromBytes(bytes, "image/png")),
    options = request.options,
)
val edited = images.edit(edit)
```

One to sixteen inputs are accepted. An optional mask applies to the first input and must be a PNG with an alpha channel and matching dimensions. OpenAI validates the actual media, dimensions, reference accessibility and model limits. Do not send private application URLs that the provider cannot access; resolve authorised saved images into bytes in the consuming application. Large inputs can use an uploaded file reference to avoid base64 expansion. The caller owns uploaded-file lifecycle.

## Streaming

Use `KtorKoogHttpClient` or `OkHttpKoogHttpClient` for supported, tested streaming. The current `JavaKoogHttpClient` SSE parser forwards `event:` lines as payloads, causing image JSON decoding to fail on valid provider frames. It is therefore incompatible with image streaming until its shared SSE parser is repaired. Custom transports must deliver decoded SSE data and release the connection when collection is cancelled.

Both `generateStreaming` and `editStreaming` return a cold `Flow<OpenAIImageEvent>`. Every collection starts a separate provider request. Streaming currently requires `n = 1`; non-streaming requests accept one to ten images.

```kotlin
images.editStreaming(edit, partialImages = 2).collect { event ->
    when (event) {
        is OpenAIImageEvent.PartialImage -> showTemporaryPreview(event.image.bytes())
        is OpenAIImageEvent.Completed -> saveCompletedImage(event.image.bytes(), event.metadata, event.usage)
    }
}
```

The preview count ranges from zero to three and is an upper bound. Completion can arrive with fewer previews, including none. Each event retains its timestamp, format, size, quality and background. Only `Completed` is a durable output. Receiving it terminates collection and releases the stream without waiting for the remote connection to close. Cancelling collection also cancels the underlying transport. There are no client-added retries, reconnections or provider-file uploads.

Unknown event types and heartbeat data are ignored. An empty or prematurely terminated stream fails instead of returning a preview as a final image. Malformed recognised events fail. `OpenAIImagesException` contains a structured provider error; for HTTP failures its transport cause retains status, response headers, request ID and raw error body where the transport supplies them. Avoid logging image request bodies or base64 output.

## Usage

`OpenAIImageUsage` preserves available inclusive input, output and total counts plus image and text subsets on input and output. Missing fields stay null and reported zero stays zero. The subsets must never be added to the inclusive totals. `toResponseMetaInfo(timestamp, modelId)` adapts totals to Kroog's existing metadata, deriving the total when both inclusive components are known. Keep the image usage object when image-specific accounting is needed.

Storage, access authorisation, preview delivery, conversation history and tool settings belong to the consuming application. This client does not modify chat messages or the Responses pipeline.

## API references

Verified on 13 September 2026:

- [Image generation guide](https://developers.openai.com/api/docs/guides/image-generation)
- [Generation request and response](https://developers.openai.com/api/reference/resources/images/methods/generate)
- [JSON edit request and response](https://developers.openai.com/api/reference/resources/images/methods/edit)
- [Generation streaming events](https://developers.openai.com/api/reference/resources/images/generation-streaming-events)
