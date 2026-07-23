package ai.koog.agents.features.eventHandler

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostedExecutionMessageFormatTest {

    @Test
    fun testFormatsEveryHostedExecutionAndGeneratedFileVariantInMessageOrder() {
        val message = Message.Assistant(
            parts = listOf(
                MessagePart.GeneratedFile(
                    providerFileId = "file-id",
                    sizeBytes = 512,
                ),
                MessagePart.HostedExecution.Request(code = "sensitive code"),
                MessagePart.HostedExecution.Progress(message = "sensitive progress", sequence = 1),
                MessagePart.HostedExecution.CumulativeOutput(output = "sensitive output", sequence = 2),
                MessagePart.HostedExecution.Result(
                    output = "sensitive result",
                    exitCode = 0,
                    generatedFiles = listOf(MessagePart.GeneratedFile(providerFileId = "nested-file-id")),
                ),
                MessagePart.HostedExecution.Error(message = "sensitive error", code = "error-code"),
            ),
            metaInfo = ResponseMetaInfo.Empty,
        )

        val formatted = message.traceString
        val expectedParts = listOf(
            "type: GeneratedFile, status: generated, sizeBytes: 512",
            "type: HostedExecution.Request, lifecycle: request, status: requested",
            "type: HostedExecution.Progress, lifecycle: progress, status: running, sequence: 1",
            "type: HostedExecution.CumulativeOutput, lifecycle: output, status: running, sequence: 2",
            "type: HostedExecution.Result, lifecycle: result, status: completed, exitCode: 0, generatedFileCount: 1",
            "type: HostedExecution.Error, lifecycle: error, status: failed",
        )

        assertEquals(
            "role: Assistant, parts: [${expectedParts.joinToString { "{$it}" }}]",
            formatted,
        )
        expectedParts.zipWithNext().forEach { (first, second) ->
            assertTrue(formatted.indexOf(first) < formatted.indexOf(second))
        }
    }

    @Test
    fun testEmptyOptionalFieldsHaveStableMinimalFormatting() {
        assertEquals(
            "type: GeneratedFile, status: generated",
            MessagePart.GeneratedFile(providerFileId = "file-id").traceString,
        )
        assertEquals(
            "type: HostedExecution.Progress, lifecycle: progress, status: running",
            MessagePart.HostedExecution.Progress().traceString,
        )
        assertEquals(
            "type: HostedExecution.CumulativeOutput, lifecycle: output, status: running",
            MessagePart.HostedExecution.CumulativeOutput(output = "").traceString,
        )
        assertEquals(
            "type: HostedExecution.Result, lifecycle: result, status: completed, generatedFileCount: 0",
            MessagePart.HostedExecution.Result().traceString,
        )
        assertEquals(
            "type: HostedExecution.Error, lifecycle: error, status: failed",
            MessagePart.HostedExecution.Error(message = "").traceString,
        )
    }

    @Test
    fun testSensitiveBodiesPathsAndIdentifiersAreRedacted() {
        val sensitiveValues = listOf(
            "print-secret-credential",
            "language-secret-credential",
            "progress-secret-credential",
            "output-secret-credential",
            "result-secret-credential",
            "error-secret-credential",
            "error-code-secret-credential",
            "provider-file-secret",
            "container-secret",
            "execution-secret",
            "provider-item-secret",
            "/private/secret/report.csv",
            "media-secret",
        )
        val formatted = listOf(
            MessagePart.GeneratedFile(
                providerFileId = sensitiveValues[7],
                containerId = sensitiveValues[8],
                filename = sensitiveValues[11],
                mediaType = sensitiveValues[12],
                producingExecutionId = sensitiveValues[9],
                providerItemId = sensitiveValues[10],
            ),
            MessagePart.HostedExecution.Request(
                code = sensitiveValues[0],
                language = sensitiveValues[1],
                executionId = sensitiveValues[9],
                containerId = sensitiveValues[8],
                providerItemId = sensitiveValues[10],
            ),
            MessagePart.HostedExecution.Progress(
                message = sensitiveValues[2],
                executionId = sensitiveValues[9],
                containerId = sensitiveValues[8],
                providerItemId = sensitiveValues[10],
            ),
            MessagePart.HostedExecution.CumulativeOutput(
                output = sensitiveValues[3],
                executionId = sensitiveValues[9],
                containerId = sensitiveValues[8],
                providerItemId = sensitiveValues[10],
            ),
            MessagePart.HostedExecution.Result(
                output = sensitiveValues[4],
                generatedFiles = listOf(
                    MessagePart.GeneratedFile(
                        providerFileId = sensitiveValues[7],
                        filename = sensitiveValues[11],
                    )
                ),
                executionId = sensitiveValues[9],
                containerId = sensitiveValues[8],
                providerItemId = sensitiveValues[10],
            ),
            MessagePart.HostedExecution.Error(
                message = sensitiveValues[5],
                code = sensitiveValues[6],
                executionId = sensitiveValues[9],
                containerId = sensitiveValues[8],
                providerItemId = sensitiveValues[10],
            ),
        ).joinToString { it.traceString }

        sensitiveValues.forEach { sensitiveValue ->
            assertFalse(formatted.contains(sensitiveValue), "Leaked sensitive value: $sensitiveValue")
        }
    }
}
