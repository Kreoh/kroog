package ai.koog.prompt.streaming

import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class StreamFrameFlowBuilderTest {

    @Test
    fun testEmitTextDelta() = runTest {
        val frames = buildStreamFrameFlow {
            emitTextDelta("Hello", 0)
            emitTextDelta(" World", 0)
            emitEnd()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.TextDelta("Hello", 0),
                StreamFrame.TextDelta(" World", 0),
                StreamFrame.TextComplete("Hello World", 0),
                StreamFrame.End(null, ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    @Test
    fun testEmitReasoningDelta() = runTest {
        val frames = buildStreamFrameFlow {
            emitReasoningDelta(text = "Thinking...", index = 0)
            emitReasoningDelta(text = " step 2", index = 0)
            emitEnd()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ReasoningDelta(text = "Thinking...", index = 0),
                StreamFrame.ReasoningDelta(text = " step 2", index = 0),
                StreamFrame.ReasoningComplete(id = null, content = listOf("Thinking... step 2"), index = 0),
                StreamFrame.End(null, ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    @Test
    fun testAttachReasoningEncryptedAfterText() = runTest {
        val frames = buildStreamFrameFlow {
            emitReasoningDelta(text = "Thinking", index = 3)
            attachReasoningEncrypted(encrypted = "signature", index = 3)
            tryEmitPendingReasoning()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ReasoningDelta(text = "Thinking", index = 3),
                StreamFrame.ReasoningComplete(
                    id = null,
                    content = listOf("Thinking"),
                    encrypted = "signature",
                    index = 3,
                ),
            ),
            frames,
        )
    }

    @Test
    fun testAttachReasoningEncryptedBeforeText() = runTest {
        val frames = buildStreamFrameFlow {
            attachReasoningEncrypted(encrypted = "signature", index = 3)
            emitReasoningDelta(text = "Thinking", index = 3)
            tryEmitPendingReasoning()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ReasoningDelta(text = "Thinking", index = 3),
                StreamFrame.ReasoningComplete(
                    id = null,
                    content = listOf("Thinking"),
                    encrypted = "signature",
                    index = 3,
                ),
            ),
            frames,
        )
    }

    @Test
    fun testAttachReasoningEncryptedWithoutText() = runTest {
        val frames = buildStreamFrameFlow {
            attachReasoningEncrypted(encrypted = "signature", index = 3)
            tryEmitPendingReasoning()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ReasoningComplete(
                    id = null,
                    content = emptyList(),
                    encrypted = "signature",
                    index = 3,
                ),
            ),
            frames,
        )
    }

    @Test
    fun testNullReasoningIdsRemainSeparatedByIndex() = runTest {
        val frames = buildStreamFrameFlow {
            emitReasoningDelta(text = "first", index = 3)
            emitReasoningDelta(text = "second", index = 4)
            attachReasoningEncrypted(encrypted = "second-signature", index = 4)
            emitEnd()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ReasoningDelta(text = "first", index = 3),
                StreamFrame.ReasoningComplete(id = null, content = listOf("first"), index = 3),
                StreamFrame.ReasoningDelta(text = "second", index = 4),
                StreamFrame.ReasoningComplete(
                    id = null,
                    content = listOf("second"),
                    encrypted = "second-signature",
                    index = 4,
                ),
                StreamFrame.End(null, ResponseMetaInfo.Empty),
            ),
            frames,
        )
    }

    @Test
    fun testEmitReasoningSummaryDelta() = runTest {
        val frames = buildStreamFrameFlow {
            emitReasoningDelta(summary = "Summary part 1", index = 0)
            emitReasoningDelta(summary = " part 2", index = 0)
            emitEnd()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ReasoningDelta(summary = "Summary part 1", index = 0),
                StreamFrame.ReasoningDelta(summary = " part 2", index = 0),
                StreamFrame.ReasoningComplete(
                    id = null,
                    content = emptyList(),
                    summary = listOf("Summary part 1 part 2"),
                    index = 0
                ),
                StreamFrame.End(null, ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    @Test
    fun testEmitReasoningTextAndSummary() = runTest {
        val frames = buildStreamFrameFlow {
            emitReasoningDelta(text = "Thinking...", index = 0)
            emitReasoningDelta(text = " step 2", index = 0)
            emitReasoningDelta(summary = "Summary part 1", index = 0)
            emitReasoningDelta(summary = " part 2", index = 0)
            emitEnd()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ReasoningDelta(text = "Thinking...", index = 0),
                StreamFrame.ReasoningDelta(text = " step 2", index = 0),
                StreamFrame.ReasoningDelta(summary = "Summary part 1", index = 0),
                StreamFrame.ReasoningDelta(summary = " part 2", index = 0),
                StreamFrame.ReasoningComplete(
                    id = null,
                    content = listOf("Thinking... step 2"),
                    summary = listOf("Summary part 1 part 2"),
                    index = 0
                ),
                StreamFrame.End(null, ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    @Test
    fun testEmitReasoningTextAndSummaryWithIds() = runTest {
        val frames = buildStreamFrameFlow {
            emitReasoningDelta(id = "rs_123", text = "Thinking...", index = 0)
            emitReasoningDelta(id = "rs_123", text = " step 2", index = 0)
            emitReasoningDelta(id = "rs_123", summary = "Summary part 1", index = 0)
            emitReasoningDelta(id = "rs_123", summary = " part 2", index = 0)
            emitEnd()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ReasoningDelta(id = "rs_123", text = "Thinking...", index = 0),
                StreamFrame.ReasoningDelta(id = "rs_123", text = " step 2", index = 0),
                StreamFrame.ReasoningDelta(id = "rs_123", summary = "Summary part 1", index = 0),
                StreamFrame.ReasoningDelta(id = "rs_123", summary = " part 2", index = 0),
                StreamFrame.ReasoningComplete(
                    id = "rs_123",
                    content = listOf("Thinking... step 2"),
                    summary = listOf("Summary part 1 part 2"),
                    index = 0
                ),
                StreamFrame.End(null, ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    @Test
    fun testEmitToolCallDelta() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(id = "call_1", name = "calculator", args = "{\"a\":", 0)
            emitToolCallDelta(args = " 5}", index = 0)
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta("call_1", "calculator", "{\"a\":", 0),
                StreamFrame.ToolCallDelta(null, null, " 5}", 0),
            ),
            frames
        )
    }

    @Test
    fun testEmitEnd() = runTest {
        val frames = buildStreamFrameFlow {
            emitEnd("stop")
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.End("stop", ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    @Test
    fun testEmitToolCallDeltaWithoutIdAppendsToExisting() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(id = "call_1", name = "search", args = "{\"q")
            emitToolCallDelta(args = "uery\":")
            emitToolCallDelta(args = "\"test\"}")
            emitEnd()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta("call_1", "search", "{\"q"),
                StreamFrame.ToolCallDelta(null, null, "uery\":"),
                StreamFrame.ToolCallDelta(null, null, "\"test\"}"),
                StreamFrame.ToolCallComplete("call_1", "search", "{\"query\":\"test\"}"),
                StreamFrame.End(null, ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    @Test
    fun testEmitToolCallDeltaWithBlankIdAppendsToExisting() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(id = "call_abc123", name = "my_tool", args = "{\"param\":", index = 0)
            emitToolCallDelta(id = "", args = " \"value\"}", index = 0)
            emitEnd()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta("call_abc123", "my_tool", "{\"param\":", 0),
                StreamFrame.ToolCallDelta(null, null, " \"value\"}", 0),
                StreamFrame.ToolCallComplete("call_abc123", "my_tool", "{\"param\": \"value\"}", 0),
                StreamFrame.End(null, ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    @Test
    fun testEmitToolCallDeltaWithIdCreatesNewPendingToolCall() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(id = "call_1", name = "calculator", args = "{\"a\":", index = 0)
            emitToolCallDelta(args = " 5}", index = 0)
            emitToolCallDelta(id = "call_2", name = "calculator", args = "{\"b\":", index = 1)
            emitToolCallDelta(args = " 6}", index = 1)
            emitEnd()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta("call_1", "calculator", "{\"a\":", 0),
                StreamFrame.ToolCallDelta(null, null, " 5}", 0),
                StreamFrame.ToolCallDelta("call_2", "calculator", "{\"b\":", 1),
                StreamFrame.ToolCallDelta(null, null, " 6}", 1),
                StreamFrame.ToolCallComplete("call_1", "calculator", "{\"a\": 5}", 0),
                StreamFrame.ToolCallComplete("call_2", "calculator", "{\"b\": 6}", 1),
                StreamFrame.End(null, ResponseMetaInfo.Empty),
            ),
            frames
        )
    }

    /**
     * Regression test for #2002.
     *
     * Some OpenAI-compatible providers include the tool call `id` in every streaming chunk
     * instead of only the first one. Such repeated ids must be treated as a continuation of
     * the same tool call, not as the start of a new one.
     */
    @Test
    fun testEmitToolCallDeltaWithRepeatedIdAppendsToExisting() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(id = "call_1", name = "readFile", args = "", index = 0)
            emitToolCallDelta(id = "call_1", name = "readFile", args = "{\"path\":", index = 0)
            emitToolCallDelta(id = "call_1", name = "readFile", args = " \"/Users\"}", index = 0)
            emitEnd()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta("call_1", "readFile", "", 0),
                StreamFrame.ToolCallDelta("call_1", "readFile", "{\"path\":", 0),
                StreamFrame.ToolCallDelta("call_1", "readFile", " \"/Users\"}", 0),
                StreamFrame.ToolCallComplete("call_1", "readFile", "{\"path\": \"/Users\"}", 0),
                StreamFrame.End(null, ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    /**
     * Companion to [testEmitToolCallDeltaWithRepeatedIdAppendsToExisting] for #2002:
     * even when every chunk repeats an `id`, a change to a different `id` must still
     * start a separate tool call.
     */
    @Test
    fun testEmitToolCallDeltaWithRepeatedIdSeparatesDistinctToolCalls() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(id = "call_1", name = "search", args = "{\"q\":", index = 0)
            emitToolCallDelta(id = "call_1", name = null, args = " 1}", index = 0)
            emitToolCallDelta(id = "call_2", name = "calculator", args = "{\"a\":", index = 1)
            emitToolCallDelta(id = "call_2", name = null, args = " 2}", index = 1)
            emitEnd()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta("call_1", "search", "{\"q\":", 0),
                StreamFrame.ToolCallDelta("call_1", null, " 1}", 0),
                StreamFrame.ToolCallDelta("call_2", "calculator", "{\"a\":", 1),
                StreamFrame.ToolCallDelta("call_2", null, " 2}", 1),
                StreamFrame.ToolCallComplete("call_1", "search", "{\"q\": 1}", 0),
                StreamFrame.ToolCallComplete("call_2", "calculator", "{\"a\": 2}", 1),
                StreamFrame.End(null, ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    @Test
    fun testInterleavedToolCallsCompleteInFirstSeenOrder() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(id = "call_a", name = "search", args = "{\"a\":", index = 0)
            emitToolCallDelta(id = "call_b", name = "calculator", args = "{\"b\":", index = 1)
            emitToolCallDelta(args = " 1}", index = 0)
            emitToolCallDelta(id = "call_b", args = " 2}")
            emitEnd()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta("call_a", "search", "{\"a\":", 0),
                StreamFrame.ToolCallDelta("call_b", "calculator", "{\"b\":", 1),
                StreamFrame.ToolCallDelta(null, null, " 1}", 0),
                StreamFrame.ToolCallDelta("call_b", null, " 2}", null),
                StreamFrame.ToolCallComplete("call_a", "search", "{\"a\": 1}", 0),
                StreamFrame.ToolCallComplete("call_b", "calculator", "{\"b\": 2}", 1),
                StreamFrame.End(null, ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    @Test
    fun testToolCallIdentityAndNameCanArriveLate() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(args = "{\"a\":", index = 0)
            emitToolCallDelta(id = "call_a", name = "search", args = " 1}", index = 0)
            emitToolCallDelta(id = "call_b", args = "{\"b\":")
            emitToolCallDelta(id = "call_b", name = "calculator", args = " 2}", index = 1)
            emitEnd()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta(null, null, "{\"a\":", 0),
                StreamFrame.ToolCallDelta("call_a", "search", " 1}", 0),
                StreamFrame.ToolCallDelta("call_b", null, "{\"b\":", null),
                StreamFrame.ToolCallDelta("call_b", "calculator", " 2}", 1),
                StreamFrame.ToolCallComplete("call_a", "search", "{\"a\": 1}", 0),
                StreamFrame.ToolCallComplete("call_b", "calculator", "{\"b\": 2}", 1),
                StreamFrame.End(null, ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    @Test
    fun testToolCallCompletionUsesDefaultsForMissingNameAndArguments() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(id = "call_1", args = null, index = 0)
            emitEnd()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta("call_1", null, null, 0),
                StreamFrame.ToolCallComplete("call_1", "", "{}", 0),
                StreamFrame.End(null, ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    @Test
    fun testTryEmitPendingToolCallDrainsAllCalls() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(id = "call_a", name = "search", args = "{}", index = 0)
            emitToolCallDelta(id = "call_b", name = "calculator", args = "{}", index = 1)
            tryEmitPendingToolCall()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta("call_a", "search", "{}", 0),
                StreamFrame.ToolCallDelta("call_b", "calculator", "{}", 1),
                StreamFrame.ToolCallComplete("call_a", "search", "{}", 0),
                StreamFrame.ToolCallComplete("call_b", "calculator", "{}", 1)
            ),
            frames
        )
    }

    @Test
    fun testAnonymousToolCallDeltaIsAmbiguousWithMultiplePendingCalls() = runTest {
        assertFailsWith<IllegalStateException> {
            buildStreamFrameFlow {
                emitToolCallDelta(id = "call_a", name = "search", args = "{}", index = 0)
                emitToolCallDelta(id = "call_b", name = "calculator", args = "{}", index = 1)
                emitToolCallDelta(args = "ignored")
            }.collect()
        }
    }

    @Test
    fun testToolCallRejectsDifferentIdsForTheSameIndexBeforeMutation() = runTest {
        val frames = mutableListOf<StreamFrame>()
        val builder = StreamFrameFlowBuilder(FlowCollector { frame -> frames += frame })
        builder.emitToolCallDelta(id = "call_1", name = "search", args = "{}", index = 0)

        assertFailsWith<IllegalArgumentException> {
            builder.emitToolCallDelta(id = "call_2", name = "search", args = "ignored", index = 0)
        }
        builder.tryEmitPendingToolCall()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta("call_1", "search", "{}", 0),
                StreamFrame.ToolCallComplete("call_1", "search", "{}", 0)
            ),
            frames
        )
    }

    @Test
    fun testToolCallRejectsDifferentIndicesForTheSameIdBeforeMutation() = runTest {
        val frames = mutableListOf<StreamFrame>()
        val builder = StreamFrameFlowBuilder(FlowCollector { frame -> frames += frame })
        builder.emitToolCallDelta(id = "call_1", name = "search", args = "{}", index = 0)

        assertFailsWith<IllegalArgumentException> {
            builder.emitToolCallDelta(id = "call_1", name = "search", args = "ignored", index = 1)
        }
        builder.tryEmitPendingToolCall()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta("call_1", "search", "{}", 0),
                StreamFrame.ToolCallComplete("call_1", "search", "{}", 0)
            ),
            frames
        )
    }

    @Test
    fun testToolCallRejectsDifferentNamesBeforeMutation() = runTest {
        val frames = mutableListOf<StreamFrame>()
        val builder = StreamFrameFlowBuilder(FlowCollector { frame -> frames += frame })
        builder.emitToolCallDelta(id = "call_1", name = "search", args = "{}", index = 0)

        assertFailsWith<IllegalArgumentException> {
            builder.emitToolCallDelta(id = "call_1", name = "calculator", args = "ignored", index = 0)
        }
        builder.tryEmitPendingToolCall()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta("call_1", "search", "{}", 0),
                StreamFrame.ToolCallComplete("call_1", "search", "{}", 0)
            ),
            frames
        )
    }

    @Test
    fun testEmitToolCallDeltaWithoutPreviousCallThrowsError() = runTest {
        assertFailsWith<StreamFrameFlowBuilderError.NoPartialToolCallToComplete> {
            buildStreamFrameFlow {
                emitToolCallDelta(args = "{\"a\": 5}")
            }.collect()
        }
    }

    @Test
    fun testSwitchingFromToolCallToTextEmitsPendingToolCall() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(id = "call_1", name = "calculator", args = "{\"a\": 5}", 0)
            emitToolCallDelta(id = "call_2", name = "search", args = "{\"q\": 6}", 1)
            emitTextDelta("Result: ", 1)
            emitEnd()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta("call_1", "calculator", "{\"a\": 5}", 0),
                StreamFrame.ToolCallDelta("call_2", "search", "{\"q\": 6}", 1),
                StreamFrame.ToolCallComplete("call_1", "calculator", "{\"a\": 5}", 0),
                StreamFrame.ToolCallComplete("call_2", "search", "{\"q\": 6}", 1),
                StreamFrame.TextDelta("Result: ", 1),
                StreamFrame.TextComplete("Result: ", 1),
                StreamFrame.End(null, ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    @Test
    fun testSwitchingFromToolCallToReasoningEmitsPendingToolCall() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(id = "call_1", name = "search", args = "{}", 0)
            emitToolCallDelta(id = "call_2", name = "calculator", args = "{}", 1)
            emitReasoningDelta(id = "rs_123", text = "Now thinking...", index = 2)
            emitEnd()
        }.toList()

        val expectedFrames = listOf(
            StreamFrame.ToolCallDelta("call_1", "search", "{}", 0),
            StreamFrame.ToolCallDelta("call_2", "calculator", "{}", 1),
            StreamFrame.ToolCallComplete("call_1", "search", "{}", 0),
            StreamFrame.ToolCallComplete("call_2", "calculator", "{}", 1),
            StreamFrame.ReasoningDelta(id = "rs_123", text = "Now thinking...", index = 2),
            StreamFrame.ReasoningComplete(id = "rs_123", listOf("Now thinking..."), null, null, 2),
            StreamFrame.End(null, ResponseMetaInfo.Empty)
        )

        assertContentEquals(expectedFrames, frames)
    }

    @Test
    fun testSwitchBetweenReasoningWithDifferentIds() = runTest {
        val frames = buildStreamFrameFlow {
            emitReasoningDelta(id = "rs_12", summary = "Summary part 1", index = 0)
            emitReasoningDelta(id = "rs_123", summary = " part 2", index = 1)
            emitEnd()
        }.toList()

        val expectedFrames = listOf(
            StreamFrame.ReasoningDelta(id = "rs_12", summary = "Summary part 1", index = 0),
            StreamFrame.ReasoningComplete(
                id = "rs_12",
                content = emptyList(),
                summary = listOf("Summary part 1"),
                index = 0
            ),
            StreamFrame.ReasoningDelta(id = "rs_123", summary = " part 2", index = 1),
            StreamFrame.ReasoningComplete(id = "rs_123", content = emptyList(), summary = listOf(" part 2"), index = 1),
            StreamFrame.End(null, ResponseMetaInfo.Empty)
        )

        assertContentEquals(expectedFrames, frames)
    }

    @Test
    fun testSwitchingDifferentFramesEmitsPendingFrame() = runTest {
        val frames = buildStreamFrameFlow {
            emitTextDelta("Start with text", 0)
            emitToolCallDelta(id = "call_1", name = "calculator", args = "{\"a\": 5}", 1)
            emitTextDelta("Continue after tool with text", 2)
            emitReasoningDelta(id = "rs_12", text = "Now switch from text to thinking...", index = 3)
            emitReasoningDelta(id = "rs_12", summary = "Summary thinking", index = 3)
            emitToolCallDelta(id = "call_2", name = "search", args = "{}", 4)
            emitReasoningDelta(id = "rs_123", text = "Now switch from tool to thinking...", index = 5)
            emitReasoningDelta(id = "rs_123", summary = "Summary thinking", index = 5)
            emitTextDelta("Finally switch from reasoning to text ", 6)
            emitEnd()
        }.toList()

        val expectedFrames = listOf(
            StreamFrame.TextDelta("Start with text", 0),
            StreamFrame.TextComplete("Start with text", 0),
            StreamFrame.ToolCallDelta("call_1", "calculator", "{\"a\": 5}", 1),
            StreamFrame.ToolCallComplete("call_1", "calculator", "{\"a\": 5}", 1),
            StreamFrame.TextDelta("Continue after tool with text", 2),
            StreamFrame.TextComplete("Continue after tool with text", 2),
            StreamFrame.ReasoningDelta(id = "rs_12", text = "Now switch from text to thinking...", index = 3),
            StreamFrame.ReasoningDelta(id = "rs_12", summary = "Summary thinking", index = 3),
            StreamFrame.ReasoningComplete(
                id = "rs_12",
                listOf("Now switch from text to thinking..."),
                listOf("Summary thinking"),
                null,
                3
            ),
            StreamFrame.ToolCallDelta("call_2", "search", "{}", 4),
            StreamFrame.ToolCallComplete("call_2", "search", "{}", 4),
            StreamFrame.ReasoningDelta(id = "rs_123", text = "Now switch from tool to thinking...", index = 5),
            StreamFrame.ReasoningDelta(id = "rs_123", summary = "Summary thinking", index = 5),
            StreamFrame.ReasoningComplete(
                id = "rs_123",
                listOf("Now switch from tool to thinking..."),
                listOf("Summary thinking"),
                null,
                5
            ),
            StreamFrame.TextDelta("Finally switch from reasoning to text ", 6),
            StreamFrame.TextComplete("Finally switch from reasoning to text ", 6),
            StreamFrame.End(null, ResponseMetaInfo.Empty)
        )

        assertContentEquals(expectedFrames, frames)
    }

    @Test
    fun testEmitToolCallDeltaWithNullArgumentsDoesNotCorruptContent() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(id = "call_1", name = "search", args = "{\"query\":")
            emitToolCallDelta(args = null)
            emitToolCallDelta(args = "\"test\"}")
            emitEnd()
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta("call_1", "search", "{\"query\":"),
                StreamFrame.ToolCallDelta(null, null, null),
                StreamFrame.ToolCallDelta(null, null, "\"test\"}"),
                StreamFrame.ToolCallComplete("call_1", "search", "{\"query\":\"test\"}"),
                StreamFrame.End(null, ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    @Test
    fun testEmitEndFlushesAllPendingFrames() = runTest {
        val frames = buildStreamFrameFlow {
            emitToolCallDelta(id = "call_1", name = "tool", args = "{}")
            emitEnd("stop")
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta("call_1", "tool", "{}"),
                StreamFrame.ToolCallComplete("call_1", "tool", "{}"),
                StreamFrame.End("stop", ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    /**
     * Regression test for #1775.
     *
     * When LLM clients (e.g. OllamaClient) wrap Ktor's preparePost(...).execute { }
     * inside buildStreamFrameFlow, the emission happens from an undispatched Ktor
     * continuation context while collection happens from a different context. With
     * the previous flow { } builder this violated Flow's context preservation
     * invariant and threw IllegalStateException. The fix switches buildStreamFrameFlow
     * to channelFlow { }, which supports cross-context emission. This test reproduces
     * that scenario by emitting from a withContext(Dispatchers.Default) block.
     */
    @Test
    fun testBuildStreamFrameFlowSupportsCrossContextEmission() = runTest {
        val frames = buildStreamFrameFlow {
            withContext(Dispatchers.Default) {
                emitTextDelta("Hello", 0)
                emitTextDelta(" World", 0)
                emitEnd("stop")
            }
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.TextDelta("Hello", 0),
                StreamFrame.TextDelta(" World", 0),
                StreamFrame.TextComplete("Hello World", 0),
                StreamFrame.End("stop", ResponseMetaInfo.Empty)
            ),
            frames
        )
    }

    @Test
    fun testParallelToolCallsSupportCrossContextEmission() = runTest {
        val frames = buildStreamFrameFlow {
            withContext(Dispatchers.Default) {
                emitToolCallDelta(id = "call_a", name = "search", args = "{\"a\":", index = 0)
                emitToolCallDelta(id = "call_b", name = "calculator", args = "{\"b\":", index = 1)
                emitToolCallDelta(id = "call_a", args = " 1}")
                emitToolCallDelta(args = " 2}", index = 1)
                emitEnd("stop")
            }
        }.toList()

        assertContentEquals(
            listOf(
                StreamFrame.ToolCallDelta("call_a", "search", "{\"a\":", 0),
                StreamFrame.ToolCallDelta("call_b", "calculator", "{\"b\":", 1),
                StreamFrame.ToolCallDelta("call_a", null, " 1}", null),
                StreamFrame.ToolCallDelta(null, null, " 2}", 1),
                StreamFrame.ToolCallComplete("call_a", "search", "{\"a\": 1}", 0),
                StreamFrame.ToolCallComplete("call_b", "calculator", "{\"b\": 2}", 1),
                StreamFrame.End("stop", ResponseMetaInfo.Empty)
            ),
            frames
        )
    }
}
