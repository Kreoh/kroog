package ai.koog.prompt.cache.files

import ai.koog.prompt.Prompt
import ai.koog.prompt.cache.model.PromptCache
import ai.koog.prompt.cache.model.PromptCacheSerialization
import ai.koog.prompt.message.CacheControl
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class FilePromptCacheSerializationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun testRegisteredRequestAndResponseDirectivesSurviveReopening() = runTest {
        val serialization = PromptCacheSerialization(
            SerializersModule {
                polymorphic(CacheControl::class) { subclass(CustomDirective::class) }
            }
        )
        val request = PromptCache.Request.create(
            Prompt.build("registered") {
                user("prefix", CustomDirective("request"))
            },
            emptyList()
        )
        val response = Message.Assistant(
            parts = listOf(MessagePart.Text("cached", cacheControl = CustomDirective("response"))),
            metaInfo = ResponseMetaInfo.Empty,
        )
        FilePromptCache(directory, 10, serialization).put(request, response)
        assertEquals(response, FilePromptCache(directory, 10, serialization).get(request))
    }

    @Serializable
    private data class CustomDirective(val marker: String) : CacheControl
}
