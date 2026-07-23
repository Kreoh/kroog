package ai.koog.prompt.streaming

import ai.koog.prompt.message.MessagePart
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagedGeneratedFileFrameTest {
    @Test
    fun testGeneratedFileBytesRoundTripWithoutProviderFileIdentity() {
        val frame = StreamFrame.GeneratedFileBytes(
            fileId = "managed-file-1",
            bytes = byteArrayOf(0, 1, 127, -1),
            offset = 8,
            executionId = "execution-1",
        )

        val encoded = Json.encodeToString<StreamFrame>(frame)
        val decoded = Json.decodeFromString<StreamFrame>(encoded) as StreamFrame.GeneratedFileBytes

        assertEquals(frame.fileId, decoded.fileId)
        assertEquals(frame.offset, decoded.offset)
        assertEquals(frame.executionId, decoded.executionId)
        assertNull(decoded.providerFileId)
        assertTrue(frame.bytes.contentEquals(decoded.bytes))
    }

    @Test
    fun testGeneratedFileBytesUsesContentEqualityHashingAndRedactedToString() {
        val first = StreamFrame.GeneratedFileBytes(
            fileId = "managed-file-1",
            bytes = byteArrayOf(11, 22, 33),
            offset = 128,
            providerFileId = "provider-file-1",
            index = 4,
        )
        val equal = first.copy(bytes = byteArrayOf(11, 22, 33))
        val unequal = first.copy(bytes = byteArrayOf(11, 22, 34))

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotEquals(first, unequal)

        val rendered = first.toString()
        assertEquals(
            "GeneratedFileBytes(fileId=managed-file-1, offset=128, providerFileId=provider-file-1, " +
                "index=4, executionId=null, byteCount=3)",
            rendered,
        )
        assertFalse(rendered.contains("bytes="))
        assertFalse(rendered.contains("[11, 22, 33]"))
        assertFalse(rendered.contains("[B@"))
    }

    @Test
    fun testGeneratedFileProviderIdentityIsDistinctFromEqualProviderItemIdentity() {
        val providerItem = StreamFrame.TextDelta(
            text = "text",
            providerItemId = "shared-provider-id",
        ).mergeIdentity()
        val generatedFile = StreamFrame.GeneratedFileBytes(
            fileId = "local-file-1",
            bytes = byteArrayOf(1),
            offset = 0,
            providerFileId = "shared-provider-id",
            executionId = "execution-1",
        ).mergeIdentity()

        assertEquals(StreamFrameMergeIdentity.ProviderItem("shared-provider-id"), providerItem)
        assertEquals(
            StreamFrameMergeIdentity.GeneratedFile("execution-1", "shared-provider-id"),
            generatedFile,
        )
        assertNotEquals(providerItem, generatedFile)
    }

    @Test
    fun testGeneratedFileChunksCorrelateWithinTheSameExecutionAndFile() {
        val first = generatedFileFrame(offset = 0, executionId = "execution-1").mergeIdentity()
        val second = generatedFileFrame(offset = 3, executionId = "execution-1").mergeIdentity()

        assertIs<StreamFrameMergeIdentity.GeneratedFile>(first)
        assertEquals(first, second)
    }

    @Test
    fun testRepeatedProviderFileIdRemainsDistinctAcrossExecutions() {
        val first = generatedFileFrame(offset = 0, executionId = "execution-1").mergeIdentity()
        val second = generatedFileFrame(offset = 0, executionId = "execution-2").mergeIdentity()

        assertNotEquals(first, second)
    }

    @Test
    fun testChunkAndCompletionUseEqualFileIdentityWhenCompletionHasProviderItemId() {
        val chunk = generatedFileFrame(offset = 0, executionId = "execution-1").mergeIdentity()
        val completion = generatedFileComplete(
            executionId = "execution-1",
            providerItemId = "provider-item-1",
        ).mergeIdentity()

        assertEquals(
            StreamFrameMergeIdentity.GeneratedFile("execution-1", "provider-file-1"),
            completion,
        )
        assertEquals(chunk, completion)
    }

    @Test
    fun testGeneratedFileCompletionIdentityRemainsDistinctAcrossExecutions() {
        val first = generatedFileComplete(
            executionId = "execution-1",
            providerItemId = "shared-provider-item",
        ).mergeIdentity()
        val second = generatedFileComplete(
            executionId = "execution-2",
            providerItemId = "shared-provider-item",
        ).mergeIdentity()

        assertIs<StreamFrameMergeIdentity.GeneratedFile>(first)
        assertIs<StreamFrameMergeIdentity.GeneratedFile>(second)
        assertNotEquals(first, second)
    }

    @Test
    fun testGeneratedFileCompletionWithoutExecutionCorrelationUsesProviderItemIdentity() {
        val completion = generatedFileComplete(
            executionId = null,
            providerItemId = "provider-item-1",
        ).mergeIdentity()

        assertEquals(StreamFrameMergeIdentity.ProviderItem("provider-item-1"), completion)
    }

    @Test
    fun testIdentityFreeGeneratedFileUsesSafeLocalKeyAndBlankKeyDoesNotMerge() {
        val first = StreamFrame.GeneratedFileBytes(
            fileId = "local-file-1",
            bytes = byteArrayOf(1),
            offset = 0,
        ).mergeIdentity()
        val repeated = StreamFrame.GeneratedFileBytes(
            fileId = "local-file-1",
            bytes = byteArrayOf(2),
            offset = 1,
        ).mergeIdentity()
        val different = StreamFrame.GeneratedFileBytes(
            fileId = "local-file-2",
            bytes = byteArrayOf(1),
            offset = 0,
        ).mergeIdentity()
        val blank = StreamFrame.GeneratedFileBytes(
            fileId = "",
            bytes = byteArrayOf(1),
            offset = 0,
        ).mergeIdentity()

        assertEquals(
            StreamFrameMergeIdentity.GeneratedFile("stream-file:local-file-1", "local-file-1"),
            first,
        )
        assertEquals(first, repeated)
        assertNotEquals(first, different)
        assertNull(blank)
    }

    private fun generatedFileFrame(offset: Long, executionId: String): StreamFrame.GeneratedFileBytes =
        StreamFrame.GeneratedFileBytes(
            fileId = "local-file-1",
            bytes = byteArrayOf(1),
            offset = offset,
            providerFileId = "provider-file-1",
            executionId = executionId,
        )

    private fun generatedFileComplete(
        executionId: String?,
        providerItemId: String?,
    ): StreamFrame.GeneratedFileComplete = StreamFrame.GeneratedFileComplete(
        MessagePart.GeneratedFile(
            providerFileId = "provider-file-1",
            producingExecutionId = executionId,
            providerItemId = providerItemId,
        )
    )
}
