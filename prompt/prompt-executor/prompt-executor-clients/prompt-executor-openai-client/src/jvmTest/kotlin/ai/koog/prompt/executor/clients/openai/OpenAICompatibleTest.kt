package ai.koog.prompt.executor.clients.openai

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.test.utils.CapturingKoogHttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenAICompatibleTest {
    @Test
    fun testUndeclaredCompatibleResponsesFailsBeforeInferenceForStatefulRequest() = runTest {
        assertUndeclaredCompatibleResponsesFailsBeforeInference(stateless = false)
    }

    @Test
    fun testUndeclaredCompatibleResponsesFailsBeforeInferenceForStatelessRequest() = runTest {
        assertUndeclaredCompatibleResponsesFailsBeforeInference(stateless = true)
    }

    private suspend fun assertUndeclaredCompatibleResponsesFailsBeforeInference(stateless: Boolean) {
        var inferenceRequests = 0
        val transport = CapturingKoogHttpClient("compatible-fixture") {
            inferenceRequests++
            error("No inference request expected")
        }
        val client = OpenAILLMClient(
            settings = OpenAIClientSettings(
                baseUrl = "https://compatible.example.test",
                responsesDialect = OpenAIResponsesDialect.Compatible,
            ),
            httpClient = transport,
        )
        val failure = assertFailsWith<OpenAIResponsesConfigurationException> {
            client.execute(
                Prompt(
                    messages = listOf(Message.User("hello", RequestMetaInfo.Empty)),
                    id = "compatible-responses",
                    params = OpenAIResponsesParams(stateless = stateless),
                ),
                OpenAIModels.Chat.GPT4o,
            )
        }

        assertEquals("OpenAI-compatible source must declare Responses support", failure.reason)
        assertEquals(0, inferenceRequests)
        assertEquals(null, transport.lastPath)
    }

    @Test
    fun testCompatibleResponsesCanBeDeclaredExplicitly() {
        val settings = OpenAIClientSettings(
            baseUrl = "https://compatible.example.test",
            responsesDialect = OpenAIResponsesDialect.Compatible,
            declaredResponsesCapability = OpenAIResponsesCapability.Supported,
        )
        assertEquals(OpenAIResponsesCapability.Supported, settings.responsesCapability)
    }
}
