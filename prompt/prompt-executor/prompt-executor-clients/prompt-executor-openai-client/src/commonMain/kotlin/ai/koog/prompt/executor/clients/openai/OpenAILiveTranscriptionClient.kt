package ai.koog.prompt.executor.clients.openai

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.KoogHttpMultipartPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Server-side setup for OpenAI transcription-only WebRTC sessions.
 *
 * The caller owns the authenticated [httpClient], its base URL, timeouts and lifecycle.
 * Endpoint paths are relative to that base URL. No automatic retries are added.
 * The browser owns media, data-channel transcript events, audio commits and connection cleanup.
 * This client does not stream audio, finalise transcripts or create conversational voice sessions.
 */
public class OpenAILiveTranscriptionClient(
    private val httpClient: KoogHttpClient,
    private val callsPath: String = "v1/realtime/calls",
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Exchanges [sdpOffer] and transcription settings for an SDP answer in one request.
     * Project authentication remains on the caller-owned server transport.
     * [options] apply to input transcription; no language hint or delay is imposed by default.
     *
     * Pass the returned answer to the browser's `RTCPeerConnection.setRemoteDescription`.
     * A successful handshake does not mean microphone audio has been received or transcribed.
     * The browser must wait for session readiness and handle transcript completion separately.
     *
     * [KoogHttpClientException] and coroutine cancellation propagate unchanged. Malformed provider
     * responses fail explicitly. Cancellation cannot undo a request already accepted by OpenAI;
     * the caller must also close any browser connection abandoned during setup.
     */
    public suspend fun createSession(
        sdpOffer: String,
        options: OpenAILiveTranscriptionOptions = OpenAILiveTranscriptionOptions(),
    ): String {
        require(sdpOffer.isNotBlank()) { "A WebRTC SDP offer is required" }
        require(options.model.isNotBlank()) { "A live transcription model is required" }
        val session = buildJsonObject {
            put("type", "transcription")
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("transcription") {
                        put("model", options.model)
                        options.prompt?.let { put("prompt", it) }
                        options.keywords?.let { put("keywords", json.encodeToJsonElement(it)) }
                        options.languages?.let { put("languages", json.encodeToJsonElement(it)) }
                        options.delay?.let { put("delay", json.encodeToJsonElement(it)) }
                    }
                    put("turn_detection", JsonNull)
                }
            }
        }
        val answer = httpClient.postMultipart(
            callsPath,
            listOf(
                KoogHttpMultipartPart.Text("sdp", sdpOffer),
                KoogHttpMultipartPart.Text("session", session.toString()),
            ),
            headers = mapOf("Accept" to "application/sdp"),
        ).body.decodeToString()
        check(answer.lineSequence().firstOrNull()?.trimEnd() == "v=0") {
            "OpenAI returned an invalid SDP answer"
        }
        return answer
    }
}

/**
 * Input transcription settings for `gpt-live-transcribe` and compatible model snapshots.
 * WebRTC negotiates its audio format. Turn detection is disabled and audio commits are browser-owned.
 *
 * @property model Explicit live transcription model identifier. Committed-turn and file models are not supported.
 * @property prompt Optional context describing the recording or its setting.
 * @property keywords Optional literal vocabulary hints, validated by the provider.
 * @property languages Expected input languages. Null leaves language selection to the provider.
 * @property delay Optional latency versus accuracy setting. Null preserves the provider default.
 */
public data class OpenAILiveTranscriptionOptions(
    public val model: String = "gpt-live-transcribe",
    public val prompt: String? = null,
    public val keywords: List<String>? = null,
    public val languages: List<String>? = null,
    public val delay: OpenAITranscriptionDelay? = null,
)

/** Higher delay gives the transcription model more audio context before emitting text. */
@Serializable
public enum class OpenAITranscriptionDelay {
    /** Earliest partial text. */
    @kotlinx.serialization.SerialName("minimal")
    MINIMAL,

    /** Low-latency partial text. */
    @kotlinx.serialization.SerialName("low")
    LOW,

    /** Balanced latency and context. */
    @kotlinx.serialization.SerialName("medium")
    MEDIUM,

    /** More audio context before emitting text. */
    @kotlinx.serialization.SerialName("high")
    HIGH,

    /** Most audio context of the available settings. */
    @kotlinx.serialization.SerialName("xhigh")
    XHIGH,
}
