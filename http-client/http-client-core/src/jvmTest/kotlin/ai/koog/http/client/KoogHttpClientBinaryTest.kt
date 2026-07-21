package ai.koog.http.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KoogHttpClientBinaryTest {
    @Test
    fun testMultipartFileRetainsBinaryBytesAndMetadata() {
        val bytes = byteArrayOf(0, 1, 0, -1)
        val part = KoogHttpMultipartPart.File("file", "data.bin", "application/octet-stream", bytes)

        assertEquals("file", part.name)
        assertEquals("data.bin", part.filename)
        assertEquals("application/octet-stream", part.mediaType)
        assertContentEquals(bytes, part.bytes)
        assertEquals(part, part.copy(bytes = bytes.copyOf()))
    }

    @Test
    fun testLegacyImplementationFailsExplicitlyForNewOperations(): Unit = runBlocking {
        val client = LegacyClient()

        assertFailsWith<UnsupportedOperationException> { client.getBytes("files/id/content") }
        assertFailsWith<UnsupportedOperationException> { client.postBytes("containers", "{}") }
        assertFailsWith<UnsupportedOperationException> { client.postMultipart("files", emptyList()) }
        assertFailsWith<UnsupportedOperationException> { client.delete("files/id") }
    }

    private class LegacyClient : KoogHttpClient {
        override val clientName: String = "legacy"
        override suspend fun <R : Any> get(
            path: String,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R = error("unused")

        override suspend fun <T : Any, R : Any> post(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R = error("unused")

        override fun <T : Any, R : Any, O : Any> sse(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            dataFilter: (String?) -> Boolean,
            decodeStreamingResponse: (String) -> R,
            processStreamingChunk: (R) -> O?,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<O> = emptyFlow()

        override fun <T : Any> lines(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<String> = emptyFlow()

        override fun close() = Unit
    }
}
