package ai.koog.http.client.ktor

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.post
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals

@Execution(ExecutionMode.SAME_THREAD)
class KtorKoogHttpClientFactoryTest : KtorKoogHttpClientTestBase() {
    @Test
    fun testRequestHeadersReplaceDefaultsWithoutChangingSubsequentRequests() = runTest {
        val engine = MockEngine { request ->
            val overridden = request.url.encodedPath == "/override"
            assertEquals(
                listOf(if (overridden) "Bearer ephemeral" else "Bearer project"),
                request.headers.getAll(HttpHeaders.Authorization),
            )
            assertEquals(
                if (overridden) "application/sdp" else "text/plain; charset=UTF-8",
                (request.body as TextContent).contentType.toString(),
            )
            assertEquals(listOf("retained"), request.headers.getAll("X-Default"))
            respond("ok")
        }
        val base = HttpClient(engine)
        val client = KtorKoogHttpClient.Factory(base).create(
            clientName = "headers-test",
            baseUrl = "https://example.test/",
            headers = mapOf(
                "authorization" to "Bearer project",
                "content-type" to "text/plain",
                "X-Default" to "retained",
            ),
        )
        try {
            client.post<String, String>(
                "override",
                "offer",
                headers = mapOf("Authorization" to "Bearer ephemeral", "Content-Type" to "application/sdp"),
            )
            client.post<String, String>("defaults", "body")
        } finally {
            client.close()
            base.close()
        }
    }

    override fun createClient(): KoogHttpClient {
        return KtorKoogHttpClient.Factory().create(clientName = "TestClient")
    }

    override fun ktorClient(
        baseClient: HttpClient,
        baseUrl: String,
        json: Json,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        withSse: Boolean
    ): KtorKoogHttpClient {
        return KtorKoogHttpClient.Factory(baseClient, withSse).create(
            clientName = "TestClient",
            baseUrl = baseUrl,
            json = json,
            headers = headers,
            queryParameters = queryParameters,
        )
    }
}
