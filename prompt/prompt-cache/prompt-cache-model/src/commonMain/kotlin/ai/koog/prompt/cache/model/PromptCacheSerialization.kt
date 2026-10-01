package ai.koog.prompt.cache.model

import ai.koog.prompt.message.CacheControl
import ai.koog.prompt.message.PromptCacheControl
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.jvm.JvmOverloads

/**
 * Shared serialisation configuration for cache keys and persisted cache entries.
 * Provider-neutral directives are registered by default. Supply provider or custom registrations through
 * [serializersModule]; all caches sharing entries must use the same registrations.
 */
public class PromptCacheSerialization @JvmOverloads constructor(
    public val serializersModule: SerializersModule = EmptySerializersModule(),
) {
    /** JSON configuration used consistently for cache keys and stored entries. */
    public val json: Json = Json {
        ignoreUnknownKeys = true
        allowStructuredMapKeys = true
        serializersModule = SerializersModule {
            polymorphic(CacheControl::class) { subclass(PromptCacheControl::class) }
            include(this@PromptCacheSerialization.serializersModule)
        }
    }

    public companion object {
        /** Default configuration for provider-neutral cache directives. */
        public val Default: PromptCacheSerialization = PromptCacheSerialization()
    }
}
