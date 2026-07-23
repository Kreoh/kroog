package ai.koog.prompt.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmOverloads

/**
 * Requires this [CacheControl] to be of the specified type [T], or throws an [IllegalStateException].
 */
public inline fun <reified T : CacheControl> CacheControl.require(): T =
    this as? T ?: error("Expected ${T::class.simpleName}, got: $this")

/**
 * Cache control configuration for prompt caching.
 * Indicates that the LLM provider should cache content up to and including the element this is attached to.
 *
 * Each LLM provider defines its own supported cache control options as nested sealed interfaces.
 */
public interface CacheControl

/** Closed prompt-cache retention values shared by provider-neutral callers. */
@Serializable
public enum class PromptCacheTtl {
    /** Retain the cached prefix for five minutes. */
    @SerialName("5m")
    FiveMinutes,

    /** Retain the cached prefix for one hour. */
    @SerialName("1h")
    OneHour,
}

/**
 * Provider-neutral prompt-cache metadata.
 *
 * A value with [cacheable] set to false records caller intent without emitting a provider breakpoint. Providers map a
 * cacheable value to their native envelope immediately before transport.
 */
@Serializable
public data class PromptCacheControl @JvmOverloads constructor(
    public val cacheable: Boolean = false,
    public val ttl: PromptCacheTtl = PromptCacheTtl.FiveMinutes,
) : CacheControl
