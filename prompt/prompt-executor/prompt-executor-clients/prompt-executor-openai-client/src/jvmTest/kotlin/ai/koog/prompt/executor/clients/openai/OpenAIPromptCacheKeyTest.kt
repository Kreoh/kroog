package ai.koog.prompt.executor.clients.openai

import ai.koog.prompt.executor.clients.openai.models.Item
import ai.koog.test.utils.CapturingKoogHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun testLegacyKeyIsStableAndIsolatedAcrossEveryResponsesDialect() {
        val legacy = "legacy-secret"
        OpenAIResponsesDialect.entries.forEach { dialect ->
            val first = OpenAIPromptCacheKey.deriveLegacy(dialect, "deployment-1", legacy)
            val second = OpenAIPromptCacheKey.deriveLegacy(dialect, "deployment-1", legacy)

            assertEquals(first, second)
            assertEquals(64, first.length)
            assertFalse(first.contains(legacy))
        }
        assertEquals(
            OpenAIResponsesDialect.entries.size,
            OpenAIResponsesDialect.entries
                .map { OpenAIPromptCacheKey.deriveLegacy(it, "deployment-1", legacy) }
                .toSet()
                .size,
        )
        assertNotEquals(
            OpenAIPromptCacheKey.deriveLegacy(OpenAIResponsesDialect.OpenAI, "deployment-1", "ab"),
            OpenAIPromptCacheKey.deriveLegacy(OpenAIResponsesDialect.OpenAI, "deployment-1a", "b"),
        )
    }

    @Test
    fun testLegacyRequestKeyIsHashedAndTypedIdentityWinsWithoutRawLeakage() {
        val legacy = "legacy-provider-sentinel"
        val client = OpenAILLMClient(
            settings = OpenAIClientSettings(responsesDialect = OpenAIResponsesDialect.Compatible),
            httpClient = CapturingKoogHttpClient("unused") { error("No transport expected") },
        )
        val legacyParams = OpenAIResponsesParams(promptCacheKey = legacy)
        val legacyPayload = client.serializeResponsesAPIRequest(
            messages = emptyList<Item>(),
            model = OpenAIModels.Chat.GPT5,
            tools = null,
            toolChoice = null,
            params = legacyParams,
            stream = false,
        )
        val typedPayload = client.serializeResponsesAPIRequest(
            messages = emptyList<Item>(),
            model = OpenAIModels.Chat.GPT5,
            tools = null,
            toolChoice = null,
            params = legacyParams.withPromptCacheIdentity(identity),
            stream = false,
        )
        val legacySent = Json.parseToJsonElement(legacyPayload).jsonObject
            .getValue("prompt_cache_key").jsonPrimitive.content
        val typedSent = Json.parseToJsonElement(typedPayload).jsonObject
            .getValue("prompt_cache_key").jsonPrimitive.content

        assertEquals(64, legacySent.length)
        assertNotEquals(legacySent, typedSent)
        assertFalse(legacyPayload.contains(legacy))
        assertFalse(typedPayload.contains(legacy))
        assertFalse(typedPayload.contains(identity.userId))
        assertFalse(typedPayload.contains(identity.chatId))
        assertFalse(legacyParams.toString().contains(legacy))
        assertFalse(OpenAIChatParams(promptCacheKey = legacy).toString().contains(legacy))
    }

    @Test
    fun testResponsesReservedAdditionalPropertyCannotInjectOrOverridePromptCacheKey() {
        val injected = "raw-additional-secret"
        val client = OpenAILLMClient(
            settings = OpenAIClientSettings(responsesDialect = OpenAIResponsesDialect.OpenAI),
            httpClient = CapturingKoogHttpClient("unused") { error("No transport expected") },
        )
        val additional = buildJsonObject {
            put("prompt_cache_key", JsonPrimitive(injected))
            put("ordinary", JsonPrimitive("retained"))
        }
        val absentPayload = client.serializeResponsesAPIRequest(
            emptyList<Item>(),
            OpenAIModels.Chat.GPT5,
            null,
            null,
            OpenAIResponsesParams(additionalProperties = additional),
            false,
        )
        val typedPayload = client.serializeResponsesAPIRequest(
            emptyList<Item>(),
            OpenAIModels.Chat.GPT5,
            null,
            null,
            OpenAIResponsesParams(additionalProperties = additional).withPromptCacheIdentity(identity),
            false,
        )
        val absent = Json.parseToJsonElement(absentPayload).jsonObject
        val typed = Json.parseToJsonElement(typedPayload).jsonObject

        assertFalse("prompt_cache_key" in absent)
        assertEquals("retained", absent.getValue("ordinary").jsonPrimitive.content)
        assertEquals(64, typed.getValue("prompt_cache_key").jsonPrimitive.content.length)
        assertFalse(absentPayload.contains(injected))
        assertFalse(typedPayload.contains(injected))
    }

    @Test
    fun testResponsesParamsRedactAdditionalValuesAndValidationExceptionDoesNotLeakThem() {
        val secret = "additional-value-secret"
        val params = OpenAIResponsesParams(
            additionalProperties = mapOf("prompt_cache_key" to JsonPrimitive(secret)),
        )

        assertFalse(params.toString().contains(secret))
        val failure = assertFailsWith<IllegalArgumentException> {
            OpenAIResponsesParams(
                promptCacheKey = " ",
                additionalProperties = mapOf("prompt_cache_key" to JsonPrimitive(secret)),
            )
        }
        assertFalse(failure.message.orEmpty().contains(secret))
    }

    @Test
    fun testChatReservedAdditionalPropertyCannotInjectPromptCacheKey() {
        val injected = "chat-additional-secret"
        val additional = buildJsonObject {
            put("prompt_cache_key", JsonPrimitive(injected))
            put("ordinary", JsonPrimitive("retained"))
        }
        val params = OpenAIChatParams(additionalProperties = additional)

        val payload = ChatRequestSerializingClient().serialize(params)
        val request = Json.parseToJsonElement(payload).jsonObject

        assertFalse("prompt_cache_key" in request)
        assertEquals("retained", request.getValue("ordinary").jsonPrimitive.content)
        assertFalse(payload.contains(injected))
        assertFalse(params.toString().contains(injected))

        val failure = assertFailsWith<IllegalArgumentException> {
            OpenAIChatParams(
                promptCacheKey = " ",
                additionalProperties = additional,
            )
        }
        assertFalse(failure.message.orEmpty().contains(injected))
    }

    @Test
    fun testChatTypedPromptCacheKeyTakesPrecedenceOverReservedAdditionalProperty() {
        val typed = "chat-typed-secret"
        val injected = "chat-injected-secret"
        val params = OpenAIChatParams(
            promptCacheKey = typed,
            additionalProperties = buildJsonObject {
                put("prompt_cache_key", JsonPrimitive(injected))
                put("ordinary", JsonPrimitive("retained"))
            },
        )

        val payload = ChatRequestSerializingClient().serialize(params)
        val request = Json.parseToJsonElement(payload).jsonObject
        val sent = request.getValue("prompt_cache_key").jsonPrimitive.content

        assertEquals(64, sent.length)
        assertEquals("retained", request.getValue("ordinary").jsonPrimitive.content)
        assertFalse(payload.contains(typed))
        assertFalse(payload.contains(injected))
        assertFalse(params.toString().contains(typed))
        assertFalse(params.toString().contains(injected))
    }

    private class ChatRequestSerializingClient :
        OpenAILLMClient(
            settings = OpenAIClientSettings(responsesDialect = OpenAIResponsesDialect.OpenAI),
            httpClient = CapturingKoogHttpClient("unused") { error("No transport expected") },
        ) {
        fun serialize(params: OpenAIChatParams): String = serializeProviderChatRequest(
            messages = emptyList(),
            model = OpenAIModels.Chat.GPT5,
            tools = null,
            toolChoice = null,
            params = params,
            stream = false,
        )
    }
}
