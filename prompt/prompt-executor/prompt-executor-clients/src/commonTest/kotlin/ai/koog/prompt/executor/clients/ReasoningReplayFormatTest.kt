package ai.koog.prompt.executor.clients

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class ReasoningReplayFormatTest {
    private val glm = LLModel(LLMProvider.OpenAI, "z-ai/glm-5.3-flash", listOf(LLMCapability.Thinking))
    private val claude = glm.copy(id = "claude-sonnet-5-5")
    private val bedrock = claude.copy(id = "global.anthropic.claude-sonnet-5-5")
    private val text = MessagePart.Text("answer")

    @Test
    fun testGlmClaudeGlmPreservesStoredReasoning() {
        val thought = MessagePart.Reasoning("private")
        val original = Message.Assistant(listOf(thought, text), ResponseMetaInfo.Empty)
        val stored = ReasoningReplayFormat.CHAT_COMPLETIONS.record(original, glm)
        assertEquals(listOf(text), ReasoningReplayFormat.ANTHROPIC.requestView(stored, claude)?.parts)
        assertEquals(listOf(thought, text), stored.parts)
        assertEquals(stored, ReasoningReplayFormat.CHAT_COMPLETIONS.requestView(stored, glm))
        assertEquals(listOf(text), ReasoningReplayFormat.CHAT_COMPLETIONS.requestView(stored, glm.copy(id = "kimi"))?.parts)
    }

    @Test
    fun testObservedNativeBedrockReasoningOnExactOrigin() = runTest {
        val kimi = LLModel(LLMProvider.Bedrock, "moonshot.kimi-k2-thinking", emptyList())
        val blocks = listOf(MessagePart.ReasoningReplay.Signed("observed", "signature"))
        val message = Message.Assistant(MessagePart.Reasoning("observed", replay = blocks), ResponseMetaInfo.Empty)
        val stored = ReasoningReplayFormat.BEDROCK.record(message, kimi)
        val streamed = ReasoningReplayFormat.BEDROCK.record(
            flowOf(StreamFrame.ReasoningComplete(null, listOf("observed"), replay = blocks), StreamFrame.End()), kimi,
        ).toList().last() as StreamFrame.End
        assertEquals(stored.metaInfo, streamed.metaInfo)
        assertEquals(stored, ReasoningReplayFormat.BEDROCK.requestView(stored, kimi))
        assertNull(ReasoningReplayFormat.BEDROCK.requestView(stored, kimi.copy(id = "another-model")))
        assertNull(ReasoningReplayFormat.CHAT_COMPLETIONS.requestView(stored, kimi))
        assertNull(ReasoningReplayFormat.BEDROCK.requestView(message.copy(metaInfo = message.metaInfo.copy(modelId = kimi.id)), kimi))
    }

    @Test
    fun testSonnetSignatureTransfersOnlyOnCompatibleModels() {
        val thought = MessagePart.Reasoning("thought", replay = listOf(MessagePart.ReasoningReplay.Signed("thought", "sig")))
        val original = Message.Assistant(thought, ResponseMetaInfo.Empty)
        val vertexTurn = ReasoningReplayFormat.ANTHROPIC.record(original, claude)
        val bedrockTurn = ReasoningReplayFormat.BEDROCK.record(original, bedrock)
        assertEquals(vertexTurn, ReasoningReplayFormat.BEDROCK.requestView(vertexTurn, bedrock))
        assertEquals(bedrockTurn, ReasoningReplayFormat.ANTHROPIC.requestView(bedrockTurn, claude))
        assertNull(ReasoningReplayFormat.BEDROCK.requestView(vertexTurn, bedrock.copy(id = "anthropic.claude-opus-5-5")))
    }

    @Test
    fun testLegacyNativeItemsAndBedrockProfileArns() {
        val signature = MessagePart.Reasoning("thought", replay = listOf(MessagePart.ReasoningReplay.Signed("thought", "sig")))
        val legacy = Message.Assistant(signature, ResponseMetaInfo.Empty)
        assertNull(ReasoningReplayFormat.GEMINI.requestView(legacy, glm))
        assertEquals(legacy, ReasoningReplayFormat.ANTHROPIC.requestView(legacy, claude))
        val response = Message.Assistant(MessagePart.Reasoning(emptyList(), providerItemId = "rs_1"), ResponseMetaInfo.Empty)
        assertNull(ReasoningReplayFormat.ANTHROPIC.requestView(response, claude))
        assertEquals(response, ReasoningReplayFormat.RESPONSES.requestView(response, glm))
        val legacyModel = legacy.copy(metaInfo = ResponseMetaInfo.Empty.copy(modelId = claude.id))
        val profile = bedrock.copy(id = "arn:aws:bedrock:us-west-2:123456789012:inference-profile/global.anthropic.claude-sonnet-5-5")
        assertEquals(legacyModel, ReasoningReplayFormat.BEDROCK.requestView(legacyModel, profile))
        assertNull(ReasoningReplayFormat.BEDROCK.requestView(legacyModel, profile.copy(id = "opaque-profile-id")))
    }

    @Test
    fun testSummaryOnlyItemRetainsResponsesIdentity() = runTest {
        val summary = MessagePart.Reasoning(emptyList(), summary = listOf("summary"), providerItemId = "rs_1")
        val message = Message.Assistant(summary, ResponseMetaInfo.Empty)
        val source = ReasoningReplayFormat.RESPONSES.record(message, glm)
        assertEquals(source, ReasoningReplayFormat.RESPONSES.requestView(source, glm))
        assertNull(ReasoningReplayFormat.CHAT_COMPLETIONS.requestView(source, glm))
        assertNull(ReasoningReplayFormat.RESPONSES.requestView(source, glm.copy(capabilities = emptyList())))
        val noIdentity = message.copy(parts = listOf(summary.copy(providerItemId = null)))
        assertNull(ReasoningReplayFormat.RESPONSES.requestView(noIdentity, glm))
        assertNull(ReasoningReplayFormat.GEMINI.requestView(message, glm))
        val frames = flowOf(StreamFrame.ReasoningComplete(null, emptyList(), summary.summary), StreamFrame.End())
        val end = ReasoningReplayFormat.RESPONSES.record(frames, glm).toList().last() as StreamFrame.End
        assertEquals(source.metaInfo, end.metaInfo)
        val textOnly = Message.Assistant(text, ResponseMetaInfo.Empty)
        assertEquals(textOnly, ReasoningReplayFormat.RESPONSES.record(textOnly, glm))
        assertEquals(listOf(StreamFrame.End()), ReasoningReplayFormat.RESPONSES.record(flowOf(StreamFrame.End()), glm).toList())
    }
}
