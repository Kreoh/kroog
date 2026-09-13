package ai.koog.prompt.executor.clients.openai

import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/** Output encoding for GPT Image models. */
@Serializable
public enum class OpenAIImageOutputFormat {
    /** The `png` provider value. */
    @SerialName("png")
    PNG,

    /** The `jpeg` provider value. */
    @SerialName("jpeg")
    JPEG,

    /** The `webp` provider value. */
    @SerialName("webp")
    WEBP,
}

/** Requested quality; supported values depend on the explicitly selected model. */
@Serializable
public enum class OpenAIImageQuality {
    /** The `auto` provider value. */
    @SerialName("auto")
    AUTO,

    /** The `low` provider value. */
    @SerialName("low")
    LOW,

    /** The `medium` provider value. */
    @SerialName("medium")
    MEDIUM,

    /** The `high` provider value. */
    @SerialName("high")
    HIGH,

    /** The `xhigh` provider value. */
    @SerialName("xhigh")
    XHIGH,

    /** The `max` provider value. */
    @SerialName("max")
    MAX,
}

/** Background setting for the generated image. */
@Serializable
public enum class OpenAIImageBackground {
    /** The `auto` provider value. */
    @SerialName("auto")
    AUTO,

    /** The `opaque` provider value. */
    @SerialName("opaque")
    OPAQUE,

    /** The `transparent` provider value. */
    @SerialName("transparent")
    TRANSPARENT,
}

/** Provider moderation level. */
@Serializable
public enum class OpenAIImageModeration {
    /** The `auto` provider value. */
    @SerialName("auto")
    AUTO,

    /** The `low` provider value. */
    @SerialName("low")
    LOW,
}

/** Edit input fidelity. Omit for models that always use high fidelity. */
@Serializable
public enum class OpenAIImageInputFidelity {
    /** The `high` provider value. */
    @SerialName("high")
    HIGH,

    /** The `low` provider value. */
    @SerialName("low")
    LOW,
}

/**
 * Options shared by GPT Image generation and editing. Null values retain provider defaults.
 * [size] accepts `auto` or `WIDTHxHEIGHT`; model-specific limits are enforced by OpenAI.
 * [n] is the requested image count. Streaming operations currently require one image.
 * [outputCompression] applies to JPEG and WebP and ranges from 0 to 100.
 */
@Serializable
public data class OpenAIImageOptions(
    public val n: Int = 1,
    public val size: String? = null,
    public val quality: OpenAIImageQuality? = null,
    public val background: OpenAIImageBackground? = null,
    @SerialName("output_format") public val outputFormat: OpenAIImageOutputFormat? = null,
    @SerialName("output_compression") public val outputCompression: Int? = null,
    public val moderation: OpenAIImageModeration? = null,
    public val user: String? = null,
) {
    init {
        require(n in 1..10) { "Image count must be between 1 and 10" }
        require(outputCompression == null || outputCompression in 0..100) { "Compression must be between 0 and 100" }
        require(background != OpenAIImageBackground.TRANSPARENT || outputFormat != OpenAIImageOutputFormat.JPEG) {
            "Transparent images require PNG or WebP output"
        }
    }
}

/** A generation request with an explicit GPT Image model, independent of the conversation model. */
public data class OpenAIImageGenerationRequest(
    public val model: String,
    public val prompt: String,
    public val options: OpenAIImageOptions = OpenAIImageOptions(),
)

/**
 * An image edit using one to sixteen references. A mask must be a PNG with an alpha channel
 * and the same dimensions as the first input image. OpenAI validates image content and model limits.
 */
public data class OpenAIImageEditRequest(
    public val model: String,
    public val prompt: String,
    public val images: List<OpenAIImageInput>,
    public val mask: OpenAIImageInput? = null,
    public val inputFidelity: OpenAIImageInputFidelity? = null,
    public val options: OpenAIImageOptions = OpenAIImageOptions(),
)

/** An image reference accepted by the JSON edit endpoint. Exactly one reference must be supplied. */
@Serializable
public data class OpenAIImageInput(
    @SerialName("image_url") public val imageUrl: String? = null,
    @SerialName("file_id") public val fileId: String? = null,
) {
    init {
        require((imageUrl != null) xor (fileId != null)) { "Supply exactly one image URL or file ID" }
        require(imageUrl == null || imageUrl.isNotBlank()) { "Image URL must not be blank" }
        require(fileId == null || fileId.isNotBlank()) { "File ID must not be blank" }
    }

    public companion object {
        /** Encodes supplied image bytes as a data URL without creating a provider file. */
        public fun fromBytes(bytes: ByteArray, mediaType: String): OpenAIImageInput {
            require(bytes.isNotEmpty()) { "Image bytes must not be empty" }
            require(mediaType in setOf("image/png", "image/jpeg", "image/webp")) { "Unsupported image media type" }
            return OpenAIImageInput(imageUrl = "data:$mediaType;base64,${Base64.encode(bytes)}")
        }
    }
}

/** Image and text token subsets. Missing counts remain unknown; zero remains zero. */
@Serializable
public data class OpenAIImageTokenDetails(
    @SerialName("image_tokens") public val imageTokensCount: Int? = null,
    @SerialName("text_tokens") public val textTokensCount: Int? = null,
)

/** Inclusive provider totals and image-specific subsets. Never add subsets to the totals. */
@Serializable
public data class OpenAIImageUsage(
    @SerialName("input_tokens") public val inputTokensCount: Int? = null,
    @SerialName("output_tokens") public val outputTokensCount: Int? = null,
    @SerialName("total_tokens") public val totalTokensCount: Int? = null,
    @SerialName("input_tokens_details") public val inputTokensDetails: OpenAIImageTokenDetails? = null,
    @SerialName("output_tokens_details") public val outputTokensDetails: OpenAIImageTokenDetails? = null,
) {
    /** Adapts inclusive totals to Kroog metadata using the caller's timestamp and explicit image model. */
    public fun toResponseMetaInfo(timestamp: Instant, modelId: String): ResponseMetaInfo = ResponseMetaInfo(
        timestamp = timestamp,
        modelId = modelId,
        inputTokensCount = inputTokensCount,
        outputTokensCount = outputTokensCount,
        totalTokensCount = inputTokensCount?.let { input -> outputTokensCount?.let { input + it } } ?: totalTokensCount,
    )
}

/** Base64-encoded output from a GPT Image model. Storage and access authorisation belong to the caller. */
@Serializable
public data class OpenAIGeneratedImage(
    @SerialName("b64_json") public val base64: String,
) {
    /** Decodes the exact image bytes. Invalid base64 raises [IllegalArgumentException]. */
    public fun bytes(): ByteArray = Base64.decode(base64)
}

/** Completed non-streaming images and provider metadata; [created] is Unix time in seconds. */
@Serializable
public data class OpenAIImageResult(
    public val created: Long,
    public val data: List<OpenAIGeneratedImage>,
    public val usage: OpenAIImageUsage? = null,
    @SerialName("output_format") public val outputFormat: OpenAIImageOutputFormat? = null,
    public val size: String? = null,
    public val quality: OpenAIImageQuality? = null,
    public val background: OpenAIImageBackground? = null,
)

/** Metadata attached to each streamed preview or completed image. Timestamp is Unix time in seconds. */
@Serializable
public data class OpenAIImageEventMetadata(
    @SerialName("created_at") public val createdAt: Long,
    @SerialName("output_format") public val outputFormat: OpenAIImageOutputFormat,
    public val size: String,
    public val quality: OpenAIImageQuality,
    public val background: OpenAIImageBackground,
)

/** Events from either generation or editing. Only [Completed] represents a durable output. */
public sealed interface OpenAIImageEvent {
    public val image: OpenAIGeneratedImage
    public val metadata: OpenAIImageEventMetadata

    /** Temporary preview with the provider's zero-based index. The requested preview count is an upper bound. */
    public data class PartialImage(
        public val index: Int,
        override val image: OpenAIGeneratedImage,
        override val metadata: OpenAIImageEventMetadata,
    ) : OpenAIImageEvent

    /** Final image and available inclusive usage information. */
    public data class Completed(
        override val image: OpenAIGeneratedImage,
        override val metadata: OpenAIImageEventMetadata,
        public val usage: OpenAIImageUsage? = null,
    ) : OpenAIImageEvent
}

/** Structured error reported by the Images API, including in-band SSE errors. */
@Serializable
public data class OpenAIImageError(
    public val message: String,
    public val type: String? = null,
    public val code: String? = null,
    public val param: String? = null,
)

/** Provider failure. HTTP errors retain their transport exception, status, headers and request ID as [cause]. */
public class OpenAIImagesException(
    public val error: OpenAIImageError,
    cause: Throwable? = null,
) : Exception(error.message, cause)
