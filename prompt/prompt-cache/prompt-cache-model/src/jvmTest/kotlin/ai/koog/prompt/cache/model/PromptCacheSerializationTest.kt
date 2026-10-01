package ai.koog.prompt.cache.model

import ai.koog.prompt.Prompt
import ai.koog.prompt.cache.memory.InMemoryPromptCache
import ai.koog.prompt.message.CacheControl
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.PromptCacheControl
import ai.koog.prompt.message.PromptCacheTtl
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PromptCacheSerializationTest {
    @Test
    fun testProviderNeutralDirectivesHaveDistinctStableKeys() {
        fun key(ttl: PromptCacheTtl): String = PromptCache.Request.create(
            Prompt.build("directive") { user("prefix", PromptCacheControl(true, ttl)) },
            emptyList(),
        ).asCacheKey
        assertNotEquals(key(PromptCacheTtl.FiveMinutes), key(PromptCacheTtl.OneHour))
    }

    @Test
    fun testCustomRegistrationsRoundTripAndPreservePlainKeys() = runTest {
        val serialization = PromptCacheSerialization(
            SerializersModule {
                polymorphic(CacheControl::class) { subclass(CustomDirective::class) }
            }
        )
        val plain = PromptCache.Request.create(Prompt.build("plain") { user("prefix") }, emptyList())
        assertEquals(plain.asCacheKey, plain.asCacheKey(serialization))
        val request = PromptCache.Request.create(
            Prompt.build("custom") {
                user("prefix", CustomDirective("retention"))
            },
            emptyList()
        )
        assertFailsWith<SerializationException> { request.asCacheKey }
        val encoded = serialization.json.encodeToString(PromptCache.Request.serializer(), request)
        val decoded = serialization.json.decodeFromString(PromptCache.Request.serializer(), encoded)
        assertEquals(request.asCacheKey(serialization), decoded.asCacheKey(serialization))
        assertEquals(CustomDirective("retention"), decoded.prompt.messages.single().parts.single().cacheControl)
        val cache = InMemoryPromptCache(null, serialization)
        val response = Message.Assistant("cached", ResponseMetaInfo.Empty)
        cache.put(request, response)
        assertEquals(response, cache.get(decoded))
    }

    @Test
    fun testDefaultMemoryCacheAcceptsProviderNeutralDirectives() = runTest {
        val cache = InMemoryPromptCache(null)
        val request = PromptCache.Request.create(
            Prompt.build("neutral") {
                user("prefix", PromptCacheControl(true, PromptCacheTtl.OneHour))
            },
            emptyList()
        )
        val response = Message.Assistant("cached", ResponseMetaInfo.Empty)
        cache.put(request, response)
        assertEquals(response, cache.get(request))
    }

    @Serializable
    private data class CustomDirective(val marker: String) : CacheControl
}
