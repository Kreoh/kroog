package ai.koog.agents.features.tracing

import ai.koog.prompt.message.MessagePart
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class CodeExecutionMessageFormatTest {

    @Test
    fun testCodeExecutionTraceRetainsCodeOrderedOutputsAndFailure() {
        val formatted = MessagePart.CodeExecution(
            id = "ci_123",
            code = "print('trace')",
            containerId = "cntr_123",
            outputs = listOf(
                MessagePart.CodeExecution.Output.Logs("first output"),
                MessagePart.CodeExecution.Output.Image("https://example.test/second.png"),
                MessagePart.CodeExecution.Output.Logs("third output"),
            ),
            failure = MessagePart.CodeExecution.Failure.INCOMPLETE,
        ).traceString

        assertContains(formatted, "id: ci_123")
        assertContains(formatted, "containerId: cntr_123")
        assertContains(formatted, "code: print('trace')")
        assertContains(formatted, "failure: INCOMPLETE")
        assertTrue(formatted.indexOf("first output") < formatted.indexOf("https://example.test/second.png"))
        assertTrue(formatted.indexOf("https://example.test/second.png") < formatted.indexOf("third output"))
    }
}
