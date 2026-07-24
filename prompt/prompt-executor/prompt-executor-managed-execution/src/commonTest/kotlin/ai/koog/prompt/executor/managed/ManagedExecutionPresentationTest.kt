package ai.koog.prompt.executor.managed

import ai.koog.prompt.message.ExecutionOrigin
import ai.koog.prompt.message.ManagedExecutionOutputStream
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ManagedExecutionPresentationTest {
    private val session = ManagedExecutionSessionReference.BedrockAgentCore(
        region = "eu-west-1",
        codeInterpreterIdentifier = "aws.codeinterpreter.v1",
        sessionId = "session-1",
        createdAtEpochMilliseconds = 1_700_000_000_000,
        timeoutSeconds = 900,
    )
    private val fileReference = ManagedExecutionFileReference.BedrockAgentCore(
        region = "eu-west-1",
        codeInterpreterIdentifier = "aws.codeinterpreter.v1",
        sessionId = "session-1",
        path = "/tmp/result.bin",
        providerFileId = "provider-file-1",
    )
    private val file = ManagedExecutionGeneratedFile(
        fileId = "ui-file-1",
        reference = fileReference,
        filename = "result.bin",
        mediaType = "application/octet-stream",
        sizeBytes = 3,
    )
    private val context = ManagedExecutionPresentationContext(
        toolCallId = "tool-call-1",
        providerItemId = "provider-item-1",
        index = 7,
        observerEventIndex = Long.MAX_VALUE - 2,
    )

    @Test
    fun testEveryManagedEventConvertsWithoutLosingIdentityOrMetadata() {
        val events = listOf<ManagedExecutionEvent>(
            ManagedExecutionEvent.Request(0, "execution-1", session, "print('secret')"),
            ManagedExecutionEvent.Stdout(1, "execution-1", session, "out"),
            ManagedExecutionEvent.Stderr(2, "execution-1", session, "err"),
            ManagedExecutionEvent.CumulativeOutput(3, "execution-1", session, "outerr"),
            ManagedExecutionEvent.GeneratedFileChunk(
                Int.MAX_VALUE.toLong() + 4,
                "execution-1",
                session,
                "ui-file-1",
                fileReference,
                0,
                byteArrayOf(1, 2, 3),
            ),
            ManagedExecutionEvent.GeneratedFileComplete(Int.MAX_VALUE.toLong() + 5, "execution-1", session, file),
            ManagedExecutionEvent.Result(
                sequence = 6,
                executionId = "execution-1",
                session = session,
                output = "done",
                exitCode = 0,
                generatedFiles = listOf(file),
                executionTimeSeconds = 1.5,
                taskId = "task-1",
                taskStatus = "complete",
            ),
            ManagedExecutionEvent.Error(
                7,
                "execution-1",
                session,
                ManagedExecutionErrorKind.EXECUTION_FAILED,
                "failed",
                "provider-code",
            ),
        )

        val frames = events.map { it.toStreamFrame(context) }

        frames.forEach { frame ->
            when (frame) {
                is StreamFrame.HostedExecutionUpdate ->
                    assertEquals(ExecutionOrigin.CLIENT_MANAGED, frame.update.origin)
                is StreamFrame.ManagedGeneratedFileBytes ->
                    assertEquals(ExecutionOrigin.CLIENT_MANAGED, frame.origin)
                is StreamFrame.GeneratedFileComplete ->
                    assertEquals(ExecutionOrigin.CLIENT_MANAGED, frame.file.origin)
                else -> error("Unexpected frame $frame")
            }
        }
        assertEquals(
            ManagedExecutionOutputStream.STDOUT,
            assertIs<MessagePart.HostedExecution.Progress>(
                assertIs<StreamFrame.HostedExecutionUpdate>(frames[1]).update
            ).outputStream,
        )
        assertEquals(
            ManagedExecutionOutputStream.STDERR,
            assertIs<MessagePart.HostedExecution.Progress>(
                assertIs<StreamFrame.HostedExecutionUpdate>(frames[2]).update
            ).outputStream,
        )
        val chunk = assertIs<StreamFrame.ManagedGeneratedFileBytes>(frames[4])
        assertEquals("ui-file-1", chunk.fileId)
        assertEquals("provider-file-1", chunk.providerFileId)
        assertEquals("tool-call-1", chunk.toolCallId)
        assertEquals(Int.MAX_VALUE.toLong() + 4, chunk.managedSequence)
        assertEquals(Long.MAX_VALUE - 2, chunk.observerEventIndex)
        assertEquals(7, chunk.index)
        assertContentEquals(byteArrayOf(1, 2, 3), chunk.bytes)
        val complete = assertIs<StreamFrame.GeneratedFileComplete>(frames[5])
        assertEquals(Int.MAX_VALUE.toLong() + 5, complete.file.managedSequence)
        assertEquals(7, complete.index)

        val result = assertIs<MessagePart.HostedExecution.Result>(
            assertIs<StreamFrame.HostedExecutionUpdate>(frames[6]).update
        )
        assertEquals(1.5, result.executionTimeSeconds)
        assertEquals("task-1", result.taskId)
        assertEquals("complete", result.taskStatus)
        assertEquals("ui-file-1", result.generatedFiles.single().fileId)
        assertEquals("provider-item-1", result.providerItemId)
    }

    @Test
    fun testSerialisedPresentationRestoresCompleteBorrowableSessionReference() {
        val frame = ManagedExecutionEvent.Result(
            sequence = 1,
            executionId = "execution-1",
            session = session,
            generatedFiles = listOf(file),
        ).toStreamFrame(context)

        val encoded = Json.encodeToString<StreamFrame>(frame)
        val decoded = Json.decodeFromString<StreamFrame>(encoded)
        val result = assertIs<MessagePart.HostedExecution.Result>(
            assertIs<StreamFrame.HostedExecutionUpdate>(decoded).update
        )

        assertEquals(session, requireNotNull(result.managedSession).toManagedExecutionReference())
        assertEquals(fileReference.providerFileId, result.generatedFiles.single().managedReference?.providerFileId)
        assertEquals("provider-item-1", result.providerItemId)
        assertEquals("ui-file-1", result.generatedFiles.single().fileId)
        assertEquals("tool-call-1", result.toolCallId)
    }

    @Test
    fun testFileChunkAndCompletionRoundTripSequenceAndPresentationIndexIndependently() {
        val frames = listOf(
            ManagedExecutionEvent.GeneratedFileChunk(
                sequence = Long.MAX_VALUE,
                executionId = "execution-1",
                session = session,
                fileId = "ui-file-1",
                reference = fileReference,
                offset = 9,
                bytes = byteArrayOf(4, 5, 6),
            ).toStreamFrame(context),
            ManagedExecutionEvent.GeneratedFileComplete(
                sequence = Long.MAX_VALUE - 1,
                executionId = "execution-1",
                session = session,
                file = file,
            ).toStreamFrame(context),
        )

        val decoded = frames.map { frame ->
            Json.decodeFromString<StreamFrame>(Json.encodeToString<StreamFrame>(frame))
        }
        val chunk = assertIs<StreamFrame.ManagedGeneratedFileBytes>(decoded[0])
        assertEquals(Long.MAX_VALUE, chunk.managedSequence)
        assertEquals(Long.MAX_VALUE - 2, chunk.observerEventIndex)
        assertEquals(7, chunk.index)
        assertEquals("execution-1", chunk.executionId)
        assertEquals("ui-file-1", chunk.fileId)
        assertEquals("provider-file-1", chunk.providerFileId)
        assertEquals(session, requireNotNull(chunk.managedSession).toManagedExecutionReference())
        assertEquals(fileReference.providerFileId, chunk.managedReference.providerFileId)
        assertContentEquals(byteArrayOf(4, 5, 6), chunk.bytes)

        val complete = assertIs<StreamFrame.GeneratedFileComplete>(decoded[1])
        assertEquals(Long.MAX_VALUE - 1, complete.file.managedSequence)
        assertEquals(7, complete.index)
        assertEquals("execution-1", complete.file.producingExecutionId)
        assertEquals("ui-file-1", complete.file.fileId)
        assertEquals("provider-item-1", complete.file.providerItemId)
        assertEquals(session, requireNotNull(complete.file.managedSession).toManagedExecutionReference())
        assertEquals(fileReference.providerFileId, complete.file.managedReference?.providerFileId)
    }
}
