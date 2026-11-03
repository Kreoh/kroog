package ai.koog.integration.tests.client

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.bedrock.BedrockModelFamilies
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CrossProviderReplayIntegrationTest {
    private fun assertionDetail(error: AssertionError): String =
        if (error is ReplayAnswerShapeError) error.message.orEmpty() else "Replay assertion mismatch"

    @Test
    fun testAssertionDiagnosticsOmitProviderValues() {
        val mismatch = assertFailsWith<AssertionError> { assertEquals("expected receipt", "private provider output") }
        assertEquals("Replay assertion mismatch", assertionDetail(mismatch))
        assertEquals(
            "Expected a JSON object, received no answer text (finish=refusal, tool calls=0)",
            assertionDetail(ReplayAnswerShapeError("no answer text", "refusal", 0)),
        )
    }

    @Test
    fun testAnswerMismatchStillSubmitsInvertedControl(): Unit = runBlocking {
        val prompt = prompt("control") { user("test") }
        val reply = Message.Assistant("test", ai.koog.prompt.message.ResponseMetaInfo.Empty)
        var checkedControl = false
        val control = ReplayHistory(prompt) { checkedControl = true }
        val history = ReplayHistory(prompt, control = control) { assertEquals("expected", "different") }
        val attempts = mutableListOf<String>()
        history.runTransfers("route") { label, _ -> attempts.add(label); reply }
        assertEquals(listOf("route [primary]", "route [inverted status]"), attempts)
        assertTrue(checkedControl)
        assertFailsWith<IllegalStateException> {
            history.runTransfers("route") { _, _ -> error("provider rejected the request") }
        }
    }

    @Test
    fun testReplayRouteConfiguration() {
        val routes = replayRoutes()
        assertEquals(BedrockModelFamilies.AnthropicClaude, claudeBedrockReplaySettings().fallbackModelFamily)
        assertEquals(8, routes.distinctBy { it.name }.size)
        val tensorX = routes.filter { it.name.startsWith("tensorx-") }
        assertEquals(4, tensorX.distinctBy { it.model.id }.size)
        val toolImages = replayCases.single { it.name == "tool-result-image" }
        assertTrue(tensorX.none(toolImages.destinationSupports))
        assertTrue(toolImages.destinationSupports(routes.single { it.name == "vertex-sonnet" }))
        for (route in tensorX) {
            assertTrue(route.params is OpenAIChatParams)
            assertTrue(route.model.supports(LLMCapability.OpenAIEndpoint.Completions), route.name)
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KROOG_REPLAY_LIVE", matches = "1")
    fun integration_testReplayMatrix() = runBlocking {
        val routes = replayRoutes()
        try {
            val sources = selected("KROOG_REPLAY_SOURCES", routes, ReplayRoute::name)
            val destinations = selected("KROOG_REPLAY_DESTINATIONS", routes, ReplayRoute::name)
            val mixed = "mixed-routes"
            val cases = selected("KROOG_REPLAY_CASES", replayCases.map { it.name } + mixed) { it }
            val failures = mutableListOf<String>()
            var accepted = 0
            suspend fun <T : Any> run(label: String, sourceFixture: Boolean = false, body: suspend () -> T): T? {
                try {
                    val result = body()
                    if (sourceFixture) println("SOURCE READY $label") else {
                        accepted++
                        println("ACCEPTED $label")
                    }
                    return result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ReplayFixtureUnavailable) {
                    if (!sourceFixture) throw e
                    println("SOURCE UNAVAILABLE $label (${e.message})")
                    return null
                } catch (e: Exception) {
                    failures += "$label (${failureType(e)})"
                    println("FAIL ${failures.last()}")
                } catch (e: AssertionError) {
                    failures += "$label (${assertionDetail(e)})"
                    println("FAIL ${failures.last()}")
                }
                return null
            }
            coroutineScope {
                sources.map { source ->
                    async {
                        for (case in replayCases.filter { it.name in cases }) {
                            if (!case.sourceSupports(source)) {
                                println("SKIP ${case.name} $source source (unsupported or unavailable fixture)")
                                continue
                            }
                            val (targets, unsupported) = destinations.partition(case.destinationSupports)
                            for (destination in unsupported) println("SKIP ${case.name} $source -> $destination (unsupported)")
                            if (targets.isEmpty()) continue
                            val label = "${case.name} $source"
                            val history = run("$label source", sourceFixture = true) { case.generate(source) } ?: continue
                            targets.map { destination ->
                                async {
                                    history.runTransfers("$label -> $destination") { attempt, fixture ->
                                        run(attempt) { destination.execute(fixture.prompt, fixture.tools) }
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                }.awaitAll()
            }
            if (mixed in cases) {
                val first = sources.first()
                val selectedRoutes = (sources + destinations).distinct()
                val second = selectedRoutes.firstOrNull { it.name != first.name }
                if (second != null) {
                    val last = destinations.filter { it.name != second.name }
                        .partition { it.name == first.name }.toList().mapNotNull { it.firstOrNull() }
                    for (destination in last) {
                        mixedHistory(first, second, destination) { stage, sourceFixture, submit ->
                            run(stage, sourceFixture, submit)
                        }
                    }
                }
            }
            check(failures.isEmpty()) { "${failures.size} source or transfer failures:\n${failures.joinToString("\n")}" }
            assumeTrue(accepted > 0, "No live transfer was exercised; compatibility is unverified")
        } finally {
            routes.forEach { it.close() }
        }
    }

    private fun failureType(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.toList()
        val status = causes.filterIsInstance<KoogHttpClientException>().firstNotNullOfOrNull { it.statusCode }
        return causes.last().javaClass.simpleName + (status?.let { " HTTP $it" } ?: "")
    }

    @Test
    fun testFailureTypeOmitsProviderMessages() {
        val http = KoogHttpClientException(statusCode = 400, errorBody = "private response", message = "signed request")
        assertEquals("KoogHttpClientException HTTP 400", failureType(IllegalStateException("request data", http)))
    }

    private suspend fun mixedHistory(
        first: ReplayRoute,
        second: ReplayRoute,
        final: ReplayRoute,
        request: suspend (String, Boolean, suspend () -> Message.Assistant) -> Message.Assistant?,
    ) {
        fun words(reply: Message.Assistant): List<String> {
            val text = reply.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
            return Regex("""[\p{L}\p{N}]+(?:[-'’][\p{L}\p{N}]+)*""").findAll(text.lowercase()).map { it.value }.toList()
        }
        val initial = prompt("mixed-route") { user("Choose one short word. Reply with just that word.") }
        val a = request("mixed-routes $first source", true) { first.execute(initial) } ?: return
        val firstWord = words(a).singleOrNull()
        val next = Prompt.build(initial) {
            message(a)
            user("Choose a different short word. Reply with just the new word.")
        }
        val b = request("mixed-routes $first -> $second", false) { second.execute(next) } ?: return
        val secondWord = words(b).singleOrNull()
        val lastPrompt = Prompt.build(next) {
            message(b)
            user("Give both previously supplied words in order, joined by a slash. No other text.")
        }
        val path = "mixed-routes $first -> $second -> $final"
        val reply = request(path, false) { final.execute(lastPrompt) } ?: return
        if (firstWord == null || secondWord == null || secondWord == firstWord) {
            println("ANSWER UNAVAILABLE $path (intermediate replies did not supply two distinct words)")
        } else {
            reportReplayAnswer(path) { assertEquals(listOf(firstWord, secondWord), words(reply)) }
        }
    }

    private fun <T> selected(variable: String, all: List<T>, name: (T) -> String): List<T> {
        val requested = System.getenv(variable)?.split(',')?.map(String::trim)?.toSet() ?: return all
        val unknown = requested - all.map(name).toSet()
        require(unknown.isEmpty()) { "Unknown $variable selection: $unknown" }
        return all.filter { name(it) in requested }
    }
}
