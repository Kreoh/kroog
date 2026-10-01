package ai.koog.prompt.executor.clients.anthropic

import ai.koog.prompt.Prompt
import ai.koog.prompt.cache.files.FilePromptCache
import ai.koog.prompt.cache.model.PromptCache
import ai.koog.prompt.cache.model.PromptCacheSerialization
import ai.koog.prompt.message.CacheControl
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class AnthropicCacheControlSerializersTest {
    @Test
    fun testBothProviderDirectivesRoundTripWithoutChangingConcreteTypes() {
        val json = Json { serializersModule = AnthropicCacheControlSerializersModule }
        val serializer = PolymorphicSerializer(CacheControl::class)
        for (directive in listOf(AnthropicCacheControl.Default, AnthropicCacheControl.OneHour)) {
            assertFailsWith<SerializationException> { Json.encodeToString(serializer, directive) }
            assertEquals(directive, json.decodeFromString(serializer, json.encodeToString(serializer, directive)))
        }
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun testProviderDirectivesHaveDistinctKeysAndPersistTheirConcreteTypes() = runTest {
        val serialization = PromptCacheSerialization(AnthropicCacheControlSerializersModule)
        val requests = listOf(AnthropicCacheControl.Default, AnthropicCacheControl.OneHour).map { directive ->
            PromptCache.Request.create(Prompt.build("anthropic") { user("prefix", directive) }, emptyList())
        }
        assertNotEquals(requests[0].asCacheKey(serialization), requests[1].asCacheKey(serialization))
        val responses = listOf(AnthropicCacheControl.Default, AnthropicCacheControl.OneHour).map { directive ->
            Message.Assistant(listOf(MessagePart.Text("cached", cacheControl = directive)), ResponseMetaInfo.Empty)
        }
        val writer = FilePromptCache(directory, null, serialization)
        requests.zip(responses).forEach { (request, response) -> writer.put(request, response) }
        val reader = FilePromptCache(directory, null, serialization)
        requests.zip(responses).forEach { (request, response) -> assertEquals(response, reader.get(request)) }
    }
}
