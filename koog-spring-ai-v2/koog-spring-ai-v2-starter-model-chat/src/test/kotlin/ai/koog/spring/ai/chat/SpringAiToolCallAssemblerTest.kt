package ai.koog.spring.ai.chat

import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.buildStreamFrameFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage

class SpringAiToolCallAssemblerTest {

    @Test
    fun testAnthropicEmitsCompleteToolCallsImmediately() = runBlocking {
        val assembler = SpringAiToolCallAssembler.forProvider(LLMProvider.Anthropic)
        val toolCall = AssistantMessage.ToolCall("call-1", "function", "search", """{"q":"test"}""")

        val frames = buildStreamFrameFlow {
            assembler.accept(listOf(toolCall), generationIndex = 0, out = this)
            assembler.flush(this)
            emitEnd()
        }.toList()

        assertEquals(
            listOf(
                StreamFrame.ToolCallDelta("call-1", "search", """{"q":"test"}""", 0),
                StreamFrame.ToolCallComplete("call-1", "search", """{"q":"test"}""", 0),
            ),
            frames.dropLast(1)
        )
        assertTrue(frames.last() is StreamFrame.End)
    }

    @Test
    fun testOpenAIBuffersPartialArgumentChunksUntilFlush() = runBlocking {
        val assembler = SpringAiToolCallAssembler.forProvider(LLMProvider.OpenAI)
        val firstChunk = AssistantMessage.ToolCall("call-1", "function", "search", """{"q":""")
        val secondChunk = AssistantMessage.ToolCall("", "function", "", """"test"}""")

        val frames = buildStreamFrameFlow {
            assembler.accept(listOf(firstChunk), generationIndex = 0, out = this)
            assembler.accept(listOf(secondChunk), generationIndex = 0, out = this)
            assembler.flush(this)
            emitEnd()
        }.toList()

        assertEquals(
            listOf(
                StreamFrame.ToolCallDelta("call-1", "search", """{"q":"test"}""", 0),
                StreamFrame.ToolCallComplete("call-1", "search", """{"q":"test"}""", 0),
            ),
            frames.dropLast(1)
        )
        assertTrue(frames.last() is StreamFrame.End)
    }

    @Test
    fun testUnverifiedProvidersBufferArgumentChunksUntilFlush() = runBlocking {
        val assembler = SpringAiToolCallAssembler.forProvider(LLMProvider.Ollama)
        val firstChunk = AssistantMessage.ToolCall("call-1", "function", "search", """{"q":""")
        val secondChunk = AssistantMessage.ToolCall("call-1", "function", "search", """"test"}""")

        val frames = buildStreamFrameFlow {
            assembler.accept(listOf(firstChunk), generationIndex = 0, out = this)
            assembler.accept(listOf(secondChunk), generationIndex = 0, out = this)
            assembler.flush(this)
            emitEnd()
        }.toList()

        assertEquals(
            listOf(
                StreamFrame.ToolCallDelta("call-1", "search", """{"q":"test"}""", 0),
                StreamFrame.ToolCallComplete("call-1", "search", """{"q":"test"}""", 0),
            ),
            frames.dropLast(1)
        )
        assertTrue(frames.last() is StreamFrame.End)
    }

    @Test
    fun testBufferUntilEndHandlesMultipleConcurrentToolCallsCorrectly() = runBlocking {
        val assembler = SpringAiToolCallAssembler.forProvider(LLMProvider.OpenAI)

        // OpenAI streams two tool calls: each chunk list has both tool calls at their respective positions
        val tool0chunk1 = AssistantMessage.ToolCall("call-1", "function", "search", """{"q":""")
        val tool1chunk1 = AssistantMessage.ToolCall("call-2", "function", "fetch", """{"url":""")
        val tool0chunk2 = AssistantMessage.ToolCall("", "function", "", """"test"}""")
        val tool1chunk2 = AssistantMessage.ToolCall("", "function", "", """"http://x"}""")

        val frames = buildStreamFrameFlow {
            assembler.accept(listOf(tool0chunk1, tool1chunk1), generationIndex = 0, out = this)
            assembler.accept(listOf(tool0chunk2, tool1chunk2), generationIndex = 0, out = this)
            assembler.flush(this)
            emitEnd()
        }.toList()

        assertEquals(
            listOf(
                StreamFrame.ToolCallDelta("call-1", "search", """{"q":"test"}""", 0),
                StreamFrame.ToolCallDelta("call-2", "fetch", """{"url":"http://x"}""", 1),
                StreamFrame.ToolCallComplete("call-1", "search", """{"q":"test"}""", 0),
                StreamFrame.ToolCallComplete("call-2", "fetch", """{"url":"http://x"}""", 1),
            ),
            frames.dropLast(1),
        )
        assertTrue(frames.last() is StreamFrame.End)
    }
}
