package ai.koog.prompt.executor.clients.google

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.google.models.GoogleCandidate
import ai.koog.prompt.executor.clients.google.models.GoogleCodeExecutionLanguage
import ai.koog.prompt.executor.clients.google.models.GoogleCodeExecutionOutcome
import ai.koog.prompt.executor.clients.google.models.GoogleCodeExecutionTool
import ai.koog.prompt.executor.clients.google.models.GoogleContent
import ai.koog.prompt.executor.clients.google.models.GoogleData
import ai.koog.prompt.executor.clients.google.models.GooglePart
import ai.koog.prompt.executor.clients.google.models.GoogleResponse
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GeminiCodeExecutionTest {
    private val client = GoogleLLMClient(
        apiKey = "test",
        httpClientFactory = KtorKoogHttpClient.Factory(),
    )
    private val model = GoogleModels.Gemini2_5Pro
    private val function = ToolDescriptor(name = "lookup", description = "Look up a value")

    @Test
    fun testMultipleChoicesRejectsHostedExecutionWithoutToolCapability() = runTest {
        val modelWithoutTools = LLModel(
            provider = LLMProvider.Google,
            id = "multiple-choice-without-tools",
            capabilities = listOf(LLMCapability.Completion, LLMCapability.MultipleChoices),
        )
        val prompt = Prompt(
            emptyList(),
            "multiple-choice-hosted-execution",
            GoogleParams(hostedExecution = GoogleHostedExecutionConfig()),
        )

        assertFailsWith<IllegalArgumentException> {
            client.executeMultipleChoices(prompt, modelWithoutTools, emptyList())
        }.message shouldBe "Model multiple-choice-without-tools does not support tools"
    }

    @Test
    fun testMultipleChoicesChecksChoiceCapabilityAfterHostedToolCapability() = runTest {
        val modelWithoutMultipleChoices = LLModel(
            provider = LLMProvider.Google,
            id = "hosted-tools-without-multiple-choice",
            capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
        )
        val prompt = Prompt(
            emptyList(),
            "hosted-execution-without-multiple-choice",
            GoogleParams(hostedExecution = GoogleHostedExecutionConfig()),
        )

        assertFailsWith<IllegalArgumentException> {
            client.executeMultipleChoices(prompt, modelWithoutMultipleChoices, emptyList())
        }.message shouldBe "Model hosted-tools-without-multiple-choice does not support multiple choices"
    }

    @Test
    fun testCodeExecutionAloneIsIncluded() {
        val params = GoogleParams(hostedExecution = GoogleHostedExecutionConfig())
        val request = client.createGoogleRequest(Prompt(emptyList(), "code", params), model, emptyList())

        client.hostedExecutionDecision(params, hasCustomTools = false) shouldBe
            GoogleHostedExecutionDecision.Included(combinedWithCustomTools = false)
        request.tools!!.single().codeExecution.shouldBeInstanceOf<GoogleCodeExecutionTool>()
        request.tools.single().functionDeclarations shouldBe null
        Json.encodeToJsonElement(request).jsonObject["tools"]!!
            .jsonArray.single().jsonObject["codeExecution"] shouldBe kotlinx.serialization.json.JsonObject(emptyMap())
    }

    @Test
    fun testCustomFunctionsAloneRemainUnchanged() {
        val params = GoogleParams()
        val request = client.createGoogleRequest(Prompt(emptyList(), "functions", params), model, listOf(function))

        client.hostedExecutionDecision(params, hasCustomTools = true) shouldBe
            GoogleHostedExecutionDecision.NotRequested
        request.tools!!.single().functionDeclarations!!.single().name shouldBe "lookup"
        request.tools.single().codeExecution shouldBe null
    }

    @Test
    fun testSupportedCombinationIncludesBothToolKinds() {
        val params = GoogleParams(
            hostedExecution = GoogleHostedExecutionConfig(GoogleHostedExecutionToolCombination.SUPPORTED)
        )
        val request = client.createGoogleRequest(Prompt(emptyList(), "combined", params), model, listOf(function))

        client.hostedExecutionDecision(params, hasCustomTools = true) shouldBe
            GoogleHostedExecutionDecision.Included(combinedWithCustomTools = true)
        request.tools!!.map { (it.functionDeclarations != null) to (it.codeExecution != null) } shouldBe
            listOf(true to false, false to true)
    }

    @Test
    fun testUnsupportedCombinationKeepsCustomFunctions() {
        val params = GoogleParams(
            hostedExecution = GoogleHostedExecutionConfig(
                GoogleHostedExecutionToolCombination.CUSTOM_TOOLS_TAKE_PRECEDENCE
            )
        )
        val request = client.createGoogleRequest(Prompt(emptyList(), "precedence", params), model, listOf(function))

        client.hostedExecutionDecision(params, hasCustomTools = true) shouldBe
            GoogleHostedExecutionDecision.CustomToolsTakePrecedence
        request.tools!!.single().functionDeclarations!!.single().name shouldBe "lookup"
        request.tools.single().codeExecution shouldBe null
    }

    @Test
    fun testPublishedCopyShapeRetainsHostedExecution() {
        val config = GoogleHostedExecutionConfig(
            GoogleHostedExecutionToolCombination.CUSTOM_TOOLS_TAKE_PRECEDENCE
        )
        val params = GoogleParams(hostedExecution = config, temperature = 0.4)

        params.copy(temperature = 0.2).hostedExecution shouldBe config
        params.withHostedExecution(null).hostedExecution shouldBe null
    }

    @Test
    fun testNonStreamCodeAndFailureMapToHostedLifecycle() {
        val response = client.processGoogleCandidate(
            GoogleCandidate(
                content = GoogleContent(
                    role = "model",
                    parts = listOf(
                        GooglePart.ExecutableCode(
                            GoogleData.ExecutableCode("exec-1", GoogleCodeExecutionLanguage.PYTHON, "raise Error()")
                        ),
                        GooglePart.CodeExecutionResult(
                            GoogleData.CodeExecutionResult(
                                "exec-1",
                                GoogleCodeExecutionOutcome.OUTCOME_FAILED,
                                "Error",
                            )
                        ),
                    )
                )
            ),
            ResponseMetaInfo.Empty,
        )

        response.parts[0] shouldBe MessagePart.HostedExecution.Request(
            code = "raise Error()",
            executionId = "exec-1",
            providerItemId = "exec-1",
        )
        response.parts[1] shouldBe MessagePart.HostedExecution.Error(
            message = "Error",
            code = "OUTCOME_FAILED",
            executionId = "exec-1",
            providerItemId = "exec-1",
        )
    }

    @Test
    fun testNonStreamResultAcceptsMissingOptionalOutput() {
        val response = Json.decodeFromString<GoogleResponse>(
            """
            {
              "candidates": [{
                "content": {
                  "role": "model",
                  "parts": [{
                    "codeExecutionResult": {
                      "outcome": "OUTCOME_DEADLINE_EXCEEDED"
                    }
                  }]
                },
                "index": 0
              }]
            }
            """.trimIndent()
        )

        client.processGoogleCandidate(response.candidates.single(), ResponseMetaInfo.Empty).parts.single() shouldBe
            MessagePart.HostedExecution.Error(
                message = "",
                code = "OUTCOME_DEADLINE_EXCEEDED",
                executionId = "google-execution-0-0",
            )
    }
}
