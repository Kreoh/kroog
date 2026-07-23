package ai.koog.prompt

import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.StreamFrameMergeIdentity
import ai.koog.prompt.streaming.mergeIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

internal class ProviderItemIdTest {
    @Test
    fun testEqualCallIdsDoNotCollapseDistinctProviderItems() {
        val first = StreamFrame.ToolCallDelta(
            id = "shared-call",
            name = "first",
            content = "{}",
            providerItemId = "provider-one",
        )
        val second = first.copy(name = "second", providerItemId = "provider-two")

        assertEquals(StreamFrameMergeIdentity.ProviderItem("provider-one"), first.mergeIdentity())
        assertEquals(StreamFrameMergeIdentity.ProviderItem("provider-two"), second.mergeIdentity())
    }
}
