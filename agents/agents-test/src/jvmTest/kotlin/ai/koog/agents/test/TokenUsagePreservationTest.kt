package ai.koog.agents.test

import ai.koog.agents.testing.tools.MockPromptExecutor
import ai.koog.agents.testing.tools.ResponseMatcher
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import ai.koog.prompt.tokenizer.Tokenizer
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TokenUsagePreservationTest {
    @Test
    fun testMockTokenEstimationPreservesBreakdownsAcrossIndividualRequests() = runTest {
        listOf(null, 0, 2).forEach { count ->
            val meta = ResponseMetaInfo.Empty.copy(
                cacheReadTokensCount = count,
                cacheWriteTokensCount = count,
                reasoningTokensCount = count,
                modelId = "fixture-model",
            )
            val executor = MockPromptExecutor(
                handleLastAssistantMessage = false,
                responseMatcher = ResponseMatcher(defaultResponse = Message.Assistant("answer", meta)),
                moderationResponseMatcher = ResponseMatcher(defaultResponse = ModerationResult(false, emptyMap())),
                streamResponseMatcher = ResponseMatcher(defaultResponse = emptyFlow<StreamFrame>()),
                tokenizer = object : Tokenizer {
                    override fun countTokens(text: String): Int = text.length
                },
            )
            val first = executor.execute(Prompt.build("first") { user("first") }, OpenAIModels.Chat.GPT4o)
            val last = executor.executeStreaming(Prompt.build("last") { user("longer input") }, OpenAIModels.Chat.GPT4o)
                .toList().toMessageResponse()
            assertEquals(5, first.metaInfo.inputTokensCount)
            assertEquals(12, last.metaInfo.inputTokensCount)
            assertEquals(18, last.metaInfo.totalTokensCount)
            listOf(first, last).forEach { response ->
                assertEquals(count, response.metaInfo.cacheReadTokensCount)
                assertEquals(count, response.metaInfo.cacheWriteTokensCount)
                assertEquals(count, response.metaInfo.reasoningTokensCount)
                assertEquals("fixture-model", response.metaInfo.modelId)
            }
        }
    }
}
