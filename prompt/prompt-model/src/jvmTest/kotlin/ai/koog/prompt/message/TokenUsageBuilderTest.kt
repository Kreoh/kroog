package ai.koog.prompt.message

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TokenUsageBuilderTest {
    @Test
    fun testJavaInstantFactoryRetainsPreviousJavaSignature() {
        val factory = Class.forName("ai.koog.prompt.message.ResponseMetaInfoBuilderKt").getMethod(
            "fromJavaInstant",
            ResponseMetaInfo.Companion::class.java,
            Instant::class.java,
            Int::class.javaObjectType,
            Int::class.javaObjectType,
            Int::class.javaObjectType,
            kotlinx.serialization.json.JsonObject::class.java,
        )
        val meta = factory.invoke(null, ResponseMetaInfo.Companion, Instant.EPOCH, 15, 10, 5, null) as ResponseMetaInfo
        assertEquals(15, meta.totalTokensCount)
        assertEquals(10, meta.inputTokensCount)
        assertEquals(5, meta.outputTokensCount)
        assertEquals(null, meta.cacheReadTokensCount)
        assertEquals(null, meta.cacheWriteTokensCount)
        assertEquals(null, meta.reasoningTokensCount)
    }

    @Test
    fun testBuilderAndJavaInstantFactoryPreserveBreakdowns() {
        val timestamp = Instant.parse("2026-01-01T00:00:00Z")
        val built = ResponseMetaInfo.builder().timestamp(timestamp)
            .cacheReadTokensCount(0).cacheWriteTokensCount(20).reasoningTokensCount(30).build()
        val created = ResponseMetaInfo.fromJavaInstant(
            timestamp,
            cacheReadTokensCount = 0,
            cacheWriteTokensCount = 20,
            reasoningTokensCount = 30
        )
        assertEquals(created, built)
        assertEquals(0, built.cacheReadTokensCount)
        assertEquals(20, built.cacheWriteTokensCount)
        assertEquals(30, built.reasoningTokensCount)
    }

    @Test
    fun testBuilderAndFactoryLeaveUnreportedBreakdownsUnknown() {
        val timestamp = Instant.parse("2026-01-01T00:00:00Z")
        val built = ResponseMetaInfo.builder().timestamp(timestamp).build()
        assertEquals(ResponseMetaInfo.fromJavaInstant(timestamp), built)
        assertEquals(null, built.cacheReadTokensCount)
        assertEquals(null, built.cacheWriteTokensCount)
        assertEquals(null, built.reasoningTokensCount)
    }
}
