package ai.koog.prompt.streaming

import ai.koog.prompt.message.MessagePart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StreamFrameFlowBuilderEmptyArgumentsTest {
    @Test
    fun testAbsentAndEmptyArgumentsCompleteAsEmptyObject() = runTest {
        listOf(emptyList(), listOf(""), listOf("", "")).forEach { fragments ->
            val frames = buildStreamFrameFlow {
                emitToolCallDelta(id = "call_1", name = "list_files", index = 0)
                fragments.forEach { emitToolCallDelta(args = it, index = 0) }
                tryEmitPendingToolCall()
                emitEnd()
            }.toList()

            val call = frames.toMessageResponse().parts.filterIsInstance<MessagePart.Tool.Call>().single()
            assertEquals("call_1", call.id)
            assertEquals("list_files", call.tool)
            assertEquals("{}", call.args)
            assertEquals("{}", frames.filterIsInstance<StreamFrame.ToolCallComplete>().single().content)
        }
    }

    @Test
    fun testEmptyFragmentsPreserveArgumentsAndCallIdentity() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(id = "empty", name = "list_files", index = 0)
            emitToolCallDelta(id = "populated", name = "lookup", index = 1)
            emitToolCallDelta(args = "", index = 0)
            emitToolCallDelta(args = "", index = 1)
            emitToolCallDelta(args = "{\"value\":", index = 1)
            emitToolCallDelta(args = "", index = 0)
            emitToolCallDelta(args = "", index = 1)
            emitToolCallDelta(args = "42}", index = 1)
            emitToolCallDelta(args = "", index = 1)
            emitEnd()
        }.toList()

        val calls = frames.toMessageResponse().parts.filterIsInstance<MessagePart.Tool.Call>()
        assertEquals(listOf("empty", "populated"), calls.map { it.id })
        assertEquals(listOf("list_files", "lookup"), calls.map { it.tool })
        assertEquals(listOf("{}", "{\"value\":42}"), calls.map { it.args })
        assertEquals(listOf(0, 1), frames.filterIsInstance<StreamFrame.ToolCallComplete>().map { it.index })
    }

    @Test
    fun testNonEmptyInvalidArgumentsRemainInvalid() = runTest {
        listOf(" ", "{\"value\":", "invalid").forEach { arguments ->
            val frames = buildStreamFrameFlow {
                emitToolCallDelta(id = "call_1", name = "lookup", index = 0)
                emitToolCallDelta(args = "", index = 0)
                emitToolCallDelta(args = arguments, index = 0)
                emitToolCallDelta(args = "", index = 0)
                emitEnd()
            }.toList()

            assertEquals(arguments, frames.filterIsInstance<StreamFrame.ToolCallComplete>().single().content)
            assertFailsWith<IllegalArgumentException> { frames.toMessageResponse() }
        }
    }
}
