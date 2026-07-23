package ai.koog.prompt.executor.clients.bedrock.converse

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.bedrock.BedrockCacheControl
import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.PromptCacheControl
import ai.koog.prompt.message.PromptCacheTtl
import ai.koog.prompt.message.ResponseMetaInfo
import aws.sdk.kotlin.services.bedrockruntime.model.CachePointType
import aws.sdk.kotlin.services.bedrockruntime.model.CacheTtl
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.SystemContentBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import aws.sdk.kotlin.services.bedrockruntime.model.Tool as BedrockTool

class BedrockCacheControlTest {

    private val model = BedrockModels.AnthropicClaude4Sonnet

    private fun converseRequest(prompt: Prompt, tools: List<ToolDescriptor> = emptyList()) =
        BedrockConverseConverters.createConverseRequest(prompt, model, tools)

    // --- System ---

    @Test
    fun testSystemWithCacheControlDefault() {
        val prompt = Prompt.build("test") { system("You are helpful.", BedrockCacheControl.Default) }
        val system = converseRequest(prompt).system!!
        assertEquals(2, system.size)
        assertIs<SystemContentBlock.Text>(system[0])
        val cp = assertIs<SystemContentBlock.CachePoint>(system[1])
        assertEquals(CachePointType.Default, cp.value.type)
        assertNull(cp.value.ttl)
    }

    @Test
    fun testSystemWithoutCacheControl() {
        val prompt = Prompt.build("test") { system("Hello.") }
        val system = converseRequest(prompt).system!!
        assertEquals(1, system.size)
        assertIs<SystemContentBlock.Text>(system[0])
    }

    @Test
    fun testSystemCacheControlFiveMinutes() {
        val prompt = Prompt.build("test") { system("Cached.", BedrockCacheControl.FiveMinutes) }
        val cp = assertIs<SystemContentBlock.CachePoint>(converseRequest(prompt).system!![1])
        assertNotNull(cp.value.ttl)
        assertEquals(CacheTtl.FiveMinutes, cp.value.ttl)
    }

    @Test
    fun testSystemCacheControlOneHour() {
        val prompt = Prompt.build("test") { system("Cached.", BedrockCacheControl.OneHour) }
        val cp = assertIs<SystemContentBlock.CachePoint>(converseRequest(prompt).system!![1])
        assertNotNull(cp.value.ttl)
        assertEquals(CacheTtl.OneHour, cp.value.ttl)
    }

    // --- Tools ---

    @Test
    fun testToolWithCacheControl() {
        val tool = ToolDescriptor(
            name = "search",
            description = "Search",
            requiredParameters = listOf(ToolParameterDescriptor("q", "query", ToolParameterType.String)),
            cacheControl = BedrockCacheControl.Default
        )
        val prompt = Prompt.build("test") { user("go") }
        val tools = converseRequest(prompt, listOf(tool)).toolConfig!!.tools
        assertEquals(2, tools.size)
        assertIs<BedrockTool.ToolSpec>(tools[0])
        assertIs<BedrockTool.CachePoint>(tools[1])
    }

    @Test
    fun testToolWithoutCacheControl() {
        val tool = ToolDescriptor(
            name = "search",
            description = "Search",
            requiredParameters = listOf(ToolParameterDescriptor("q", "query", ToolParameterType.String))
        )
        val prompt = Prompt.build("test") { user("go") }
        val tools = converseRequest(prompt, listOf(tool)).toolConfig!!.tools
        assertEquals(1, tools.size)
        assertIs<BedrockTool.ToolSpec>(tools[0])
    }

    // --- User ---

    @Test
    fun testUserWithCacheControl() {
        val prompt = Prompt.build("test") {
            user("Hello", BedrockCacheControl.Default)
        }
        val content = converseRequest(prompt).messages!![0].content
        assertEquals(2, content.size)
        assertIs<ContentBlock.Text>(content[0])
        assertIs<ContentBlock.CachePoint>(content[1])
    }

    @Test
    fun testUserWithoutCacheControlGetsAutomaticFiveMinuteBreakpoint() {
        val prompt = Prompt.build("test") { user("Hello") }
        val content = converseRequest(prompt).messages!![0].content
        assertEquals(2, content.size)
        assertIs<ContentBlock.Text>(content[0])
        val cachePoint = assertIs<ContentBlock.CachePoint>(content[1])
        assertEquals(CacheTtl.FiveMinutes, cachePoint.value.ttl)
    }

    // --- Assistant ---

    @Test
    fun testAssistantWithoutCacheControlGetsAutomaticFiveMinuteBreakpoint() {
        val prompt = Prompt.build("test") {
            user("Hi")
            assistant("Hello!")
        }
        val content = converseRequest(prompt).messages!![1].content
        assertEquals(2, content.size)
        assertIs<ContentBlock.Text>(content[0])
        assertIs<ContentBlock.CachePoint>(content[1])
    }

    @Test
    fun testToolCallWithProviderNeutralCacheControlEmitsCachePointAfterToolUse() {
        val prompt = toolCallPrompt(
            PromptCacheControl(cacheable = true, ttl = PromptCacheTtl.OneHour),
        )

        val content = converseRequest(prompt).messages!![0].content
        assertEquals(2, content.size)
        assertIs<ContentBlock.ToolUse>(content[0])
        val cachePoint = assertIs<ContentBlock.CachePoint>(content[1])
        assertEquals(CacheTtl.OneHour, cachePoint.value.ttl)
    }

    @Test
    fun testLatestToolCallGetsAutomaticFiveMinuteBreakpointAfterToolUse() {
        val content = converseRequest(toolCallPrompt()).messages!![0].content

        assertEquals(2, content.size)
        assertIs<ContentBlock.ToolUse>(content[0])
        val cachePoint = assertIs<ContentBlock.CachePoint>(content[1])
        assertEquals(CacheTtl.FiveMinutes, cachePoint.value.ttl)
    }

    @Test
    fun testProviderNeutralOneHourCacheControlMapsToConverseEnvelope() {
        val prompt = Prompt.build("test") {
            user(
                "Cached",
                PromptCacheControl(cacheable = true, ttl = PromptCacheTtl.OneHour),
            )
        }
        val cachePoint = assertIs<ContentBlock.CachePoint>(converseRequest(prompt).messages!![0].content[1])

        assertEquals(CacheTtl.OneHour, cachePoint.value.ttl)
    }

    @Test
    fun testInvalidProviderNeutralTtlOrderFailsDuringConverseConstruction() {
        val prompt = Prompt.build("test") {
            user("short", PromptCacheControl(cacheable = true))
            user(
                "long",
                PromptCacheControl(cacheable = true, ttl = PromptCacheTtl.OneHour),
            )
        }

        assertFailsWith<IllegalArgumentException> { converseRequest(prompt) }
    }

    @Test
    fun testCodeExecutionReplayFailsDuringConverseConversion() {
        val prompt = Prompt(
            messages = listOf(
                Message.Assistant(
                    parts = listOf(codeExecution()),
                    metaInfo = ResponseMetaInfo.Empty,
                )
            ),
            id = "code-replay",
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            converseRequest(prompt)
        }

        assertEquals(
            "Bedrock Converse cannot replay provider-hosted code execution items",
            failure.message,
        )
    }

    private fun codeExecution(): MessagePart.CodeExecution =
        MessagePart.CodeExecution(
            id = "ci_failed",
            code = "raise RuntimeError()",
            containerId = "cntr_123",
            outputs = listOf(
                MessagePart.CodeExecution.Output.Logs("before failure"),
                MessagePart.CodeExecution.Output.Image("https://example.test/failure.png"),
            ),
            failure = MessagePart.CodeExecution.Failure.FAILED,
        )

    private fun toolCallPrompt(cacheControl: PromptCacheControl? = null): Prompt = Prompt(
        messages = listOf(
            Message.Assistant(
                part = MessagePart.Tool.Call(
                    id = "call-1",
                    tool = "lookup",
                    args = "{}",
                    cacheControl = cacheControl,
                ),
                metaInfo = ResponseMetaInfo.Empty,
            )
        ),
        id = "tool-call-cache",
    )
}
