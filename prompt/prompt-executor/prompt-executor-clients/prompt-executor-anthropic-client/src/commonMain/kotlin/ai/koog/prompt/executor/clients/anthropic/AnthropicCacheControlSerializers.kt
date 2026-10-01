package ai.koog.prompt.executor.clients.anthropic

import ai.koog.prompt.message.CacheControl
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Registers Anthropic's concrete cache directives under the provider-neutral [CacheControl] interface.
 * Supply this module to the cache serialisation configuration to preserve directive types on round trips.
 */
public val AnthropicCacheControlSerializersModule: SerializersModule = SerializersModule {
    polymorphic(CacheControl::class) {
        subclass(AnthropicCacheControl.Default::class)
        subclass(AnthropicCacheControl.OneHour::class)
    }
}
