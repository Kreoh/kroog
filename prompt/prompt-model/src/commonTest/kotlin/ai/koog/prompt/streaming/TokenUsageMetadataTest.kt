package ai.koog.prompt.streaming

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TokenUsageMetadataTest {
    @Test
    fun testSuccessiveRequestsKeepTheirOwnUsageMeasurements() {
        val first = Message.Assistant(
            "tool request",
            ResponseMetaInfo.Empty.copy(
                inputTokensCount = 100,
                outputTokensCount = 20,
                totalTokensCount = 120,
                cacheReadTokensCount = 40
            )
        )
        val last = Message.Assistant(
            "answer",
            ResponseMetaInfo.Empty.copy(
                inputTokensCount = 150,
                outputTokensCount = 10,
                totalTokensCount = 160,
                cacheReadTokensCount = 80
            )
        )
        val roundTripped = listOf(first, last).map { it.toStreamFrames().toMessageResponse() }
        assertEquals(150, roundTripped.last().metaInfo.inputTokensCount)
        assertEquals(80, roundTripped.last().metaInfo.cacheReadTokensCount)
        assertEquals(250, roundTripped.sumOf { it.metaInfo.inputTokensCount!! })
    }

    @Test
    fun testOptionalBreakdownsSurviveSerialisationCopyAndStreamFrames() {
        listOf(null, 0, 12).forEach { count ->
            val meta = ResponseMetaInfo.create(
                clock = KoogClock.System,
                inputTokensCount = 100,
                outputTokensCount = 40,
                totalTokensCount = 140,
                cacheReadTokensCount = count,
                cacheWriteTokensCount = count,
                reasoningTokensCount = count,
            )
            assertEquals(meta, Json.decodeFromString<ResponseMetaInfo>(Json.encodeToString(meta)))
            val message = Message.Assistant(content = "answer", metaInfo = meta.copy(modelId = "model"))
            assertEquals(message, message.toStreamFrames().toMessageResponse())
            assertEquals(count, message.metaInfo.cacheReadTokensCount)
            assertEquals(count, message.metaInfo.cacheWriteTokensCount)
            assertEquals(count, message.metaInfo.reasoningTokensCount)
        }
    }

    @Test
    fun testOldSerialisedMetadataKeepsMissingBreakdownsUnknown() {
        val meta = Json.decodeFromString<ResponseMetaInfo>("""{"timestamp":"2026-01-01T00:00:00Z","inputTokensCount":10}""")
        assertEquals(10, meta.inputTokensCount)
        assertNull(meta.cacheReadTokensCount)
        assertNull(meta.cacheWriteTokensCount)
        assertNull(meta.reasoningTokensCount)
    }
}
