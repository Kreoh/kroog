package ai.koog.prompt.executor.clients

/**
 * Exception for Koog LLM clients
 */
public class LLMClientException(
    clientName: String,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(
    buildString {
        appendLine("Error from client: $clientName")
        message?.let { appendLine(it) }
    },
    cause
)

/** The destination cannot accept the images in this request. Its message is safe to display to callers. */
public class ImageInputException(message: String) : IllegalArgumentException(message)
