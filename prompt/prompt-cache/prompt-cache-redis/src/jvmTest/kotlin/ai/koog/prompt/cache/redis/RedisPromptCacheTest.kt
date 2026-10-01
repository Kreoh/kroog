@file:OptIn(ExperimentalLettuceCoroutinesApi::class)

package ai.koog.prompt.cache.redis

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.cache.model.get
import ai.koog.prompt.cache.model.put
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Execution(ExecutionMode.SAME_THREAD)
class RedisPromptCacheTest {
    companion object {
        private val mockConnection: StatefulRedisConnection<String, String> = mockk(relaxed = true)
        private val mockCommands: RedisCoroutinesCommands<String, String> = mockk(relaxed = true)

        private fun createCache(ttl: Duration, currentTimeMillis: () -> Long): RedisPromptCache {
            val mockRedisClient = MockRedisClient(mockConnection, mockCommands, currentTimeMillis)

            // Create a RedisPromptCache with the mock client
            return RedisPromptCache(mockRedisClient, "test:", ttl)
        }

        private val testPrompt = Prompt(listOf(Message.User("Hello, world!", RequestMetaInfo.Empty)), "test-prompt-id")
        private val testTools = emptyList<ToolDescriptor>()
        private val testResponse = Message.Assistant("Hello, user!", ResponseMetaInfo.Empty)

        private val testClock = KoogClock { testResponse.metaInfo.timestamp }
    }

    private var nowMillis = 0L

    @BeforeTest
    fun setUp() {
        nowMillis = 0L
        // Replace the Kotlin extension function at runtime
        mockkStatic(StatefulRedisConnection<*, *>::coroutines)
        every { mockConnection.coroutines() } returns mockCommands
    }

    @AfterTest
    fun tearDown() {
        // Clean up so other tests aren't affected
        unmockkStatic(StatefulRedisConnection<*, *>::coroutines)
    }

    @Test
    fun testPutAndGet() = runTest {
        val cache = createCache(60.seconds) { nowMillis }
        cache.put(testPrompt, testTools, testResponse)

        val cachedResponse = cache.get(testPrompt, testTools, testClock)
        assertEquals(testResponse, cachedResponse)
    }

    @Test
    fun testCacheExpiration() = runTest {
        val cache = createCache(1.seconds) { nowMillis }
        cache.put(testPrompt, testTools, testResponse)

        nowMillis += 1500

        val cachedResponse = cache.get(testPrompt, testTools, testClock)
        assertNull(cachedResponse)
    }

    @Test
    fun testExpirationUpdateOnAccess() = runTest {
        val cache = createCache(2.seconds) { nowMillis }
        cache.put(testPrompt, testTools, testResponse)

        nowMillis += 1000

        val cachedResponse1 = cache.get(testPrompt, testTools, testClock)
        assertEquals(testResponse, cachedResponse1)

        nowMillis += 1500

        // Reading the cache refreshes its expiry.
        val cachedResponse2 = cache.get(testPrompt, testTools, testClock)
        assertEquals(testResponse, cachedResponse2)
    }

    @Test
    fun testAccessedEntryExpiresAtRefreshedTtl() = runTest {
        val cache = createCache(2.seconds) { nowMillis }
        cache.put(testPrompt, testTools, testResponse)

        nowMillis = 1000
        assertEquals(testResponse, cache.get(testPrompt, testTools, testClock))

        nowMillis = 3000
        assertNull(cache.get(testPrompt, testTools, testClock))
    }

    @Test
    fun testRedisGetDoesNotRefreshExpiry() = runTest {
        MockRedisClient(mockConnection, mockCommands) { nowMillis }
        mockCommands.setex("key", 2, "value")

        nowMillis = 1000
        assertEquals("value", mockCommands.get("key"))

        nowMillis = 2000
        assertNull(mockCommands.get("key"))
    }

    @Test
    fun testRedisSetClearsExpiry() = runTest {
        MockRedisClient(mockConnection, mockCommands) { nowMillis }
        mockCommands.setex("key", 2, "value")
        mockCommands.set("key", "updated")

        nowMillis = 2000
        assertEquals("updated", mockCommands.get("key"))
    }
}
