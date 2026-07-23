package ai.koog.prompt.executor.clients.openai

import ai.koog.prompt.executor.clients.openai.models.Item
import ai.koog.test.utils.CapturingKoogHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class OpenAIPromptCacheKeyTest {
    private val identity = OpenAIPromptCacheIdentity(userId = "user-1", chatId = "chat-1")

    @Test
    fun testDigestUsesVersionedLengthPrefixedSha256Encoding() {
        assertEquals(
            "f578ae5fbf143dd49d8e4b111d72174c3569ab49e302737a99f3f46238f16952",
            OpenAIPromptCacheKey.derive(OpenAIResponsesDialect.OpenAI, "gpt-5", identity),
        )
    }

    @Test
    fun testDigestRenderingIsNonEmptyStableLowercaseHex() {
        val first = OpenAIPromptCacheKey.derive(OpenAIResponsesDialect.OpenAI, "gpt-5", identity)
        val second = OpenAIPromptCacheKey.derive(OpenAIResponsesDialect.OpenAI, "gpt-5", identity)

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun testDigestIsIsolatedByEveryTupleComponentAndUnambiguous() {
        val baseline = OpenAIPromptCacheKey.derive(OpenAIResponsesDialect.OpenAI, "ab", identity)
        val variants = listOf(
            OpenAIPromptCacheKey.derive(OpenAIResponsesDialect.Azure, "ab", identity),
            OpenAIPromptCacheKey.derive(OpenAIResponsesDialect.OpenAI, "a", identity),
            OpenAIPromptCacheKey.derive(
                OpenAIResponsesDialect.OpenAI,
                "ab",
                OpenAIPromptCacheIdentity("user-2", "chat-1"),
            ),
            OpenAIPromptCacheKey.derive(
                OpenAIResponsesDialect.OpenAI,
                "ab",
                OpenAIPromptCacheIdentity("user-1", "chat-2"),
            ),
            OpenAIPromptCacheKey.derive(
                OpenAIResponsesDialect.OpenAI,
                "a",
                OpenAIPromptCacheIdentity("buser-1", "chat-1"),
            ),
        )

        variants.forEach { assertNotEquals(baseline, it) }
        assertEquals(variants.size, variants.toSet().size)
    }

    @Test
    fun testRequestSendsOnlyDigest() {
        val client = OpenAILLMClient(
            settings = OpenAIClientSettings(responsesDialect = OpenAIResponsesDialect.OpenAI),
            httpClient = CapturingKoogHttpClient("unused") { error("No transport expected") },
        )
        val payload = client.serializeResponsesAPIRequest(
            messages = emptyList<Item>(),
            model = OpenAIModels.Chat.GPT5,
            tools = null,
            toolChoice = null,
            params = OpenAIResponsesParams().withPromptCacheIdentity(identity),
            stream = false,
        )
        val request = Json.parseToJsonElement(payload).jsonObject
        val sent = request.getValue("prompt_cache_key").jsonPrimitive.content

        assertEquals(64, sent.length)
        assertFalse(payload.contains(identity.userId))
        assertFalse(payload.contains(identity.chatId))
    }
}
