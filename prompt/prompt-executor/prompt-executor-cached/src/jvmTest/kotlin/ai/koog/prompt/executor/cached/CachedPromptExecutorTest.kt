package ai.koog.prompt.executor.cached

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.cache.model.PromptCache
import ai.koog.prompt.cache.model.put
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.openai.OpenAIHostedShellConfig
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.PromptExecutorOperation
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.streamFrameFlowOf
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CachedPromptExecutorTest {
    companion object {
        private val testPrompt = Prompt(listOf(Message.User("Hello, world!", RequestMetaInfo.Empty)), "test-prompt-id")
        private val testTools = emptyList<ToolDescriptor>()
        private val testResponse = Message.Assistant("Hello, user!", ResponseMetaInfo.Empty)
        private val testClock = KoogClock { testResponse.metaInfo.timestamp }
        private val testModel = LLModel(
            provider = object : LLMProvider("", "") {},
            id = "",
            capabilities = emptyList(),
            contextLength = 1_000L,
        )
    }

    // Mock implementation of PromptCache
    private class MockPromptCache : PromptCache {
        private val cache = mutableMapOf<String, Message.Assistant>()
        var getCalled = false
        var putCalled = false

        override suspend fun get(request: PromptCache.Request): Message.Assistant? {
            getCalled = true
            return cache[request.asCacheKey]
        }

        override suspend fun put(request: PromptCache.Request, response: Message.Assistant) {
            putCalled = true
            cache[request.asCacheKey] = response
        }
    }

    // Mock implementation of PromptExecutor
    private open class MockPromptExecutor : PromptExecutor() {
        var executeCalled = false
        var executeStreamingCalled = false

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Message.Assistant {
            executeCalled = true
            return testResponse
        }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> {
            executeStreamingCalled = true
            return streamFrameFlowOf("Streaming response from executor")
        }

        override suspend fun moderate(
            prompt: Prompt,
            model: LLModel
        ): ModerationResult {
            throw UnsupportedOperationException("Moderation is not needed for TestLLMExecutor")
        }

        override fun close() {}
    }

    @Test
    fun testResolvedFallbackIsReusedOnCacheMiss() = runTest {
        val fallback = LLModel(LLMProvider.Ollama, "llama3.2")
        val nested = object : MockPromptExecutor() {
            var resolutions = 0

            override suspend fun resolveModel(model: LLModel, promptExecutorOperation: PromptExecutorOperation): ResolvedModel {
                assertEquals(testModel, model)
                resolutions++
                return ResolvedModel(fallback)
            }

            override suspend fun execute(prompt: Prompt, model: ResolvedModel, tools: List<ToolDescriptor>): Message.Assistant {
                assertEquals(fallback, model.effectiveModel)
                return testResponse
            }
        }
        val executor = CachedPromptExecutor(MockPromptCache(), nested, testClock)
        val resolved = executor.resolveModel(testModel, PromptExecutorOperation.Execute)
        assertEquals(testResponse, executor.execute(testPrompt, resolved, testTools))
        assertEquals(1, nested.resolutions)
    }

    @Test
    fun testHostedShellResponsesBypassCacheAcrossWorkspacesAndRepeatedCalls() = runTest {
        val cache = MockPromptCache()
        val nested = object : MockPromptExecutor() {
            val configs = mutableListOf<OpenAIHostedShellConfig>()

            override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
                val shell = assertNotNull((prompt.params as OpenAIResponsesParams).hostedShell)
                configs += shell
                val containerId = shell.containerId ?: "cntr_auto_${configs.size}"
                val output = MessagePart.Text(
                    "output ${configs.size}",
                    generatedFileCitations = listOf(MessagePart.GeneratedFileCitation("file_${configs.size}", containerId)),
                )
                return testResponse.copy(parts = listOf(output))
            }
        }
        val executor = CachedPromptExecutor(cache, nested, testClock)
        val first = OpenAIHostedShellConfig(containerId = "cntr_first")
        val second = OpenAIHostedShellConfig(containerId = "cntr_second")
        val requests = listOf(
            first,
            second,
            second,
            OpenAIHostedShellConfig(fileIds = listOf("file_a")),
            OpenAIHostedShellConfig(fileIds = listOf("file_b")),
        )
        val responses = requests.map { config ->
            val response = executor.execute(shellPrompt(config), testModel)
            assertIs<MessagePart.Text>(response.parts.single())
        }

        assertEquals(requests, nested.configs)
        assertEquals(listOf("output 1", "output 2", "output 3", "output 4", "output 5"), responses.map { it.text })
        assertEquals(
            listOf("cntr_first", "cntr_second", "cntr_second", "cntr_auto_4", "cntr_auto_5"),
            responses.map { it.generatedFileCitations.single().containerId },
        )
        assertFalse(cache.getCalled)
        assertFalse(cache.putCalled)
    }

    @Test
    fun testHostedShellStreamsFromNestedExecutorEvenWithCachedResponse() = runTest {
        val cache = MockPromptCache()
        cache.put(testPrompt, testTools, testResponse)
        val resolvedModel = ResolvedModel(testModel)
        val frames = listOf(
            StreamFrame.HostedExecutionUpdate(MessagePart.HostedExecution.Progress(executionId = "execution")),
            StreamFrame.End(),
        )
        val nested = object : MockPromptExecutor() {
            override fun executeStreaming(prompt: Prompt, resolvedModel: ResolvedModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
                assertEquals(testModel, resolvedModel.effectiveModel)
                return flowOf(*frames.toTypedArray())
            }
        }
        val executor = CachedPromptExecutor(cache, nested, testClock)
        val prompt = shellPrompt(OpenAIHostedShellConfig(containerId = "cntr_stream"))

        assertEquals(frames, executor.executeStreaming(prompt, testModel).toList())
        assertEquals(frames, executor.executeStreaming(prompt, resolvedModel).toList())
        assertFalse(cache.getCalled)
        assertFalse(nested.executeCalled)
    }

    @Test
    fun `test executor uses cache when cached result is available`() = runTest {
        // Arrange
        val cache = MockPromptCache()
        val executor = MockPromptExecutor()
        val cachedExecutor = CachedPromptExecutor(cache, executor, testClock)

        cache.put(testPrompt, testTools, testResponse)
        val response = cachedExecutor.execute(testPrompt, testModel, testTools)

        assertTrue(cache.getCalled, "Cache get should be called")
        assertEquals(false, executor.executeCalled, "Executor should not be called when cache hit")
        assertEquals(testResponse, response, "Should return cached response")
    }

    @Test
    fun `test executor delegates to nested executor when no cached result is available`() = runTest {
        val cache = MockPromptCache()
        val executor = MockPromptExecutor()
        val cachedExecutor = CachedPromptExecutor(cache, executor, testClock)

        val response = cachedExecutor.execute(testPrompt, testModel, testTools)

        assertTrue(cache.getCalled, "Cache get should be called")
        assertTrue(executor.executeCalled, "Executor should be called when cache miss")
        assertTrue(cache.putCalled, "Cache put should be called to store the result")
        assertEquals(testResponse, response, "Should return response from executor")
    }

    private fun shellPrompt(config: OpenAIHostedShellConfig): Prompt =
        testPrompt.copy(params = OpenAIResponsesParams().withHostedShell(config))
}
