package ai.koog.prompt.executor.clients

import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.StreamFrameMergeIdentity
import ai.koog.prompt.streaming.mergeIdentity
import ai.koog.prompt.streaming.toMessageResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

internal class StreamIdentityReplayTest {
    @Test
    fun testStreamReplayKeepsProviderAndFunctionIdentitiesSeparate() {
        val message = listOf(
            StreamFrame.ToolCallComplete(
                id = "function-call-id",
                name = "lookup",
                content = "{}",
                index = 3,
                providerItemId = "provider-item-id",
            ),
            StreamFrame.End(messageId = "ui-item-id"),
        ).toMessageResponse()
        val call = message.parts.single() as MessagePart.Tool.Call

        assertEquals("ui-item-id", message.id)
        assertEquals("function-call-id", call.callId)
        assertEquals("provider-item-id", call.providerItemId)
    }

    @Test
    fun testStreamReplayDoesNotPromoteInternalIndexToProviderIdentity() {
        val text = listOf(StreamFrame.TextComplete("complete", index = 9)).toMessageResponse().parts.single()

        assertNull((text as MessagePart.Text).providerItemId)
    }

    @Test
    fun testProviderItemIdentityWinsOverEqualFunctionCallIds() {
        val first = StreamFrame.ToolCallComplete(
            id = "equal-call",
            name = "one",
            content = "{}",
            providerItemId = "provider-one",
        )
        val second = first.copy(name = "two", providerItemId = "provider-two")

        assertEquals(StreamFrameMergeIdentity.ProviderItem("provider-one"), first.mergeIdentity())
        assertEquals(StreamFrameMergeIdentity.ProviderItem("provider-two"), second.mergeIdentity())
        assertNotEquals(first.mergeIdentity(), second.mergeIdentity())
    }
}
