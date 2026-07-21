package ai.koog.http.client

/**
 * Base exception class for HTTP clients in koog
 */
public class KoogHttpClientException(
    public val clientName: String? = null,
    public val statusCode: Int? = null,
    public val errorBody: String? = null,
    message: String? = null,
    cause: Throwable? = null
) : Exception(
    buildString {
        appendLine("Error from client: ${clientName ?: "unknown client"}")
        message?.let { appendLine("Message: $it") }
        statusCode?.let { appendLine("Status code: $it") }
        errorBody?.let {
            appendLine("Error body:")
            appendLine(it)
        }
    },
    cause
) {
    /** Response headers retained for structured HTTP failures. */
    public var responseHeaders: Map<String, List<String>> = emptyMap()
        private set

    /** Provider request identifier retained for diagnostics. */
    public var requestId: String? = null
        private set

    /** Creates a failure that retains response headers and a provider request identifier. */
    public constructor(
        clientName: String?,
        statusCode: Int?,
        errorBody: String?,
        responseHeaders: Map<String, List<String>>,
        requestId: String?,
        message: String? = null,
        cause: Throwable? = null,
    ) : this(clientName, statusCode, errorBody, message, cause) {
        this.responseHeaders = responseHeaders
        this.requestId = requestId
    }
}
