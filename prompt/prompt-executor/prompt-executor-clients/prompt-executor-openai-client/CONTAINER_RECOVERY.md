# OpenAI Code Interpreter recovery

`OpenAIResponsesParams.withContainerRecovery` lets an application reproject execution history before Kroog retries an unavailable container. It works through the normal `LLMClient`, prompt executor and agent APIs, for both streaming and non-streaming Responses calls. The callback belongs to the request parameters and survives their copy operations.

```kotlin
val params = OpenAIResponsesParams(
    stateless = true,
    codeInterpreter = OpenAICodeInterpreterConfig(
        containerId = currentContainerId,
        fileIds = uploadedFileIds,
    ),
).withContainerRecovery { retryPrompt, failure ->
    markContainerUnavailable(failure.containerId)
    retryPrompt.withMessages { messages ->
        projectUnavailableExecutionHistory(messages)
    }
}
```

The two application functions above are illustrative. The application owns conversation state and transcript wording. Kroog owns error classification, automatic-container parameters and the retry boundary. Use the callback's current prompt, which includes the latest tool results and responses, when projecting history. Preserve message order, keep the original rich records for the UI, and make repeated projection stable.

Kroog recognises HTTP 400 or 404 failures with the exact provider message `Container is expired.` and an absent or container parameter. It also recognises container parameters with `container_expired`, `container_not_found` or `invalid_container` error codes. Parameters must be `container` or `tools[n].container`; unrelated errors and malformed bodies retain normal failure handling. `OpenAIContainerUnavailableException` exposes `containerId` and an `Expired` or `Missing` reason. Unrecovered streaming failures retain this exception as the cause of `LLMClientException`; non-streaming calls throw it directly. A typed failure alone does not establish that retrying is safe.

Recovery requires a stateless request and an explicitly reused container. Kroog attempts it once, before any provider event, which is stricter than checking for visible text. No recovery occurs after text, tool, execution or metadata events, on cancellation, or after a failed retry. Errors reported inside an already-started provider event stream do not qualify for recovery.

Before invoking the callback, Kroog clears the reused ID and selects the existing automatic-container configuration, retaining explicit upload file IDs. The callback returns the retry history; Kroog retains the prepared parameters even if the callback returns different ones. A callback exception or cancellation aborts the retry. The callback runs before the local `stale_container_recovered` progress frame and before the replacement request. That existing progress frame announces the recovery attempt; it is not proof of successful generation.

The application should record the rejected ID as unavailable inside the callback, before returning. On later turns, consult that state before selecting an ID from saved history, even if the retry failed or produced no code execution. Update the active ID when a later execution supplies one. Kroog neither mutates the caller's original prompt nor persists the projected history or container state. Agent applications must apply the same projection on subsequent requests after invalidation, including tool continuations.

A replacement workspace does not restore old files. Explicit uploaded file IDs remain inputs; they are not a backup of the expired workspace. Project all execution references that must no longer be replayed natively, including hosted execution and generated-file references where applicable. Without a callback, Kroog retains the existing retry behaviour and original history, so applications with native execution history should install the callback.

For local JVM artefacts, see the repository's [publication guide](../../../../PUBLISHING.md). The changed module's coordinates are `com.kreoh.kroog:prompt-executor-openai-client-jvm`. Default development builds append `-SNAPSHOT` to the version in root `gradle.properties`. Keep all affected consumer Kroog versions aligned, including the corresponding beta version.
