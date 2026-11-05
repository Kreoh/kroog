package ai.koog.integration.tests.client

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.opentest4j.TestAbortedException
import kotlin.test.assertEquals
import kotlin.test.assertIs

@EnabledIfEnvironmentVariable(named = "KROOG_REPLAY_LIVE", matches = "1")
class VertexBedrockParallelReplayIntegrationTest {
    @ParameterizedTest(name = "Vertex {0} to Bedrock {0}, streaming={1}")
    @CsvSource("sonnet,false", "opus,false", "sonnet,true")
    fun integration_testParallelResultBoundaries(model: String, streaming: Boolean) = runBlocking {
        val (vertex, bedrock) = claudeReplayRoutes(opus = model == "opus")
        vertex.use {
            bedrock.use {
                suspend fun replay(prompt: Prompt, tools: List<ToolDescriptor>) =
                    if (streaming) bedrock.executeStreaming(prompt, tools) else bedrock.execute(prompt, tools)

                val history = try {
                    parallelRepeatedToolHistory(vertex, streaming)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: ReplayFixtureUnavailable) {
                    throw TestAbortedException("Vertex $model did not produce the required parallel-call fixture")
                } catch (error: Exception) {
                    throw AssertionError("Vertex $model source request failed (${error.javaClass.simpleName})")
                }
                val grouped = history.prompt
                val last = assertIs<Message.User>(grouped.messages.last())
                val results = last.parts.map { assertIs<MessagePart.Tool.Result>(it) }
                assertEquals(2, results.size)
                val split = grouped.copy(messages = grouped.messages.dropLast(1) + results.map {
                    Message.User(part = it, metaInfo = last.metaInfo)
                })

                val failures = mutableListOf<String>()
                for ((shape, prompt) in listOf("split" to split, "grouped" to grouped)) {
                    val label = "Vertex → Bedrock $model $shape results (streaming=$streaming)"
                    val reply = try {
                        replay(prompt, history.tools)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // Provider exception messages can include authenticated request payloads.
                        val causes = generateSequence<Throwable>(error) { it.cause }.joinToString(" -> ") { it.javaClass.simpleName }
                        failures += "$label ($causes)"
                        println("FAIL ${failures.last()}")
                        continue
                    }
                    println("ACCEPTED $label")
                    history.reportAnswer(label, reply)
                }
                check(failures.isEmpty()) { failures.joinToString("\n") }
            }
        }
    }
}
