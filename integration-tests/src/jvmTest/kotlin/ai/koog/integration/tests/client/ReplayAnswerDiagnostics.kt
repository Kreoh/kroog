package ai.koog.integration.tests.client

import ai.koog.prompt.message.Message

internal fun ReplayHistory.reportAnswer(label: String, response: Message.Assistant) =
    reportReplayAnswer(label) { verify(response) }

// Keep the provider call and wire assertions outside this diagnostic boundary.
internal fun reportReplayAnswer(label: String, verify: () -> Unit) {
    val mismatch = try {
        verify()
        null
    } catch (error: AssertionError) {
        if (error is ReplayAnswerShapeError) error.message else "Replay assertion mismatch"
    } catch (_: NoSuchElementException) {
        "Missing expected answer field"
    }
    if (mismatch == null) println("ANSWER MATCH $label")
    else println("ANSWER MISMATCH $label ($mismatch)")
}

internal suspend fun ReplayHistory.runTransfers(
    label: String,
    submit: suspend (String, ReplayHistory) -> Message.Assistant?,
) {
    suspend fun attempt(variant: String, fixture: ReplayHistory) {
        val path = "$label [$variant]"
        val response = submit(path, fixture) ?: return
        fixture.reportAnswer(path, response)
    }
    attempt("primary", this)
    control?.let { attempt("inverted status", it) }
}
