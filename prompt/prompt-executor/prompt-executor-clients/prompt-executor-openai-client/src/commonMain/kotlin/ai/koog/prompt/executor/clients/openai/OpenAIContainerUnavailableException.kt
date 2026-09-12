package ai.koog.prompt.executor.clients.openai

/** Why OpenAI rejected a Code Interpreter container. */
public enum class OpenAIContainerUnavailableReason {
    /** The container has expired. */
    Expired,

    /** The container no longer exists or cannot be found. */
    Missing,
}

/**
 * Typed OpenAI container failure, supplied to recovery callbacks and retained as the cause of
 * streaming client failures. Classification alone does not authorise an application-level retry.
 *
 * @property containerId The reused container rejected by this request, if configured.
 * @property reason The recognised provider failure.
 */
public class OpenAIContainerUnavailableException(
    public val containerId: String?,
    public val reason: OpenAIContainerUnavailableReason,
    cause: Throwable,
) : Exception("OpenAI Code Interpreter container is unavailable: $reason", cause)
