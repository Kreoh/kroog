package ai.koog.prompt.provider

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderCapabilityMatrixTest {
    @Test
    fun testEveryProviderApiHasExactExecutionOutcome() {
        val expected = mapOf(
            ProviderApi.OPENAI_RESPONSES to HostedExecutionMode.INLINE_PROVIDER_TOOL,
            ProviderApi.AZURE_RESPONSES to HostedExecutionMode.INLINE_PROVIDER_TOOL,
            ProviderApi.OPENAI_COMPATIBLE_RESPONSES to null,
            ProviderApi.OPENAI_COMPATIBLE_CHAT_COMPLETIONS to null,
            ProviderApi.CODEX_RESPONSES to null,
            ProviderApi.VERTEX_GEMINI_GENERATE_CONTENT to HostedExecutionMode.INLINE_PROVIDER_TOOL,
            ProviderApi.VERTEX_ANTHROPIC_MESSAGES to HostedExecutionMode.PROVIDER_MANAGED_SANDBOX,
            ProviderApi.BEDROCK_ANTHROPIC_MESSAGES to HostedExecutionMode.PROVIDER_MANAGED_SANDBOX,
            ProviderApi.BEDROCK_CONVERSE to HostedExecutionMode.PROVIDER_MANAGED_SANDBOX,
            ProviderApi.OPENAI_EMBEDDINGS to null,
            ProviderApi.AZURE_EMBEDDINGS to null,
            ProviderApi.OPENAI_REALTIME to null,
            ProviderApi.AZURE_REALTIME to null,
        )

        assertEquals(expected.keys, ProviderApi.entries.toSet())
        expected.forEach { (api, expectedMode) ->
            val capability = ProviderCapabilityMatrix.hostedExecution(api)
            if (expectedMode == null) {
                assertIs<HostedExecutionCapability.Unsupported>(capability)
            } else {
                assertEquals(
                    expectedMode,
                    assertIs<HostedExecutionCapability.Supported>(capability).configuration.mode,
                )
            }
        }
    }

    @Test
    fun testInlineExecutionRestrictionsAreProviderSpecific() {
        val openAi = supported(ProviderApi.OPENAI_RESPONSES)
        val azure = supported(ProviderApi.AZURE_RESPONSES)
        val gemini = supported(ProviderApi.VERTEX_GEMINI_GENERATE_CONTENT)

        assertEquals("code_interpreter", openAi.providerTool)
        assertEquals(openAi, azure)
        assertEquals(HostedExecutionFeatureSupport.SUPPORTED, openAi.files)
        assertTrue(openAi.callerAddressableContainer)
        assertEquals(ProviderClientIntegration.IMPLEMENTED, openAi.clientIntegration)

        assertEquals("code_execution", gemini.providerTool)
        assertEquals(HostedExecutionFeatureSupport.UNSUPPORTED, gemini.files)
        assertFalse(gemini.callerAddressableContainer)
        assertEquals(HostedExecutionFeatureSupport.SUPPORTED, gemini.streaming)
        assertEquals(HostedExecutionFeatureSupport.SUPPORTED, gemini.replay)
    }

    @Test
    fun testManagedClaudeExecutionDoesNotClaimCurrentClientSupport() {
        val vertex = supported(ProviderApi.VERTEX_ANTHROPIC_MESSAGES)
        val bedrockMessages = supported(ProviderApi.BEDROCK_ANTHROPIC_MESSAGES)
        val bedrockConverse = supported(ProviderApi.BEDROCK_CONVERSE)

        assertEquals("Vertex Agent Engine Code Execution", vertex.managedService)
        assertEquals("Bedrock AgentCore Code Interpreter", bedrockMessages.managedService)
        assertEquals(bedrockMessages, bedrockConverse)
        listOf(vertex, bedrockMessages, bedrockConverse).forEach { configuration ->
            assertNull(configuration.providerTool)
            assertFalse(configuration.callerAddressableContainer)
            assertEquals(HostedExecutionFeatureSupport.UNSUPPORTED, configuration.files)
            assertEquals(HostedExecutionFeatureSupport.UNSUPPORTED, configuration.streaming)
            assertEquals(HostedExecutionFeatureSupport.SUPPORTED, configuration.replay)
            assertEquals(
                ProviderClientIntegration.REQUIRES_CLIENT_INTEGRATION,
                configuration.clientIntegration,
            )
        }
    }

    @Test
    fun testCompatibleTransportSelectionPrefersResponsesWithoutRuntimeFallback() {
        assertEquals(
            CompatibleTransportSelection.Selected(ProviderApi.OPENAI_COMPATIBLE_RESPONSES),
            CompatibleTransportSupport(responses = true, chatCompletions = true).select(),
        )
        assertEquals(
            CompatibleTransportSelection.Selected(
                ProviderApi.OPENAI_COMPATIBLE_CHAT_COMPLETIONS
            ),
            CompatibleTransportSupport(responses = false, chatCompletions = true).select(),
        )
        assertEquals(
            CompatibleTransportSelection.Rejected(
                CompatibleTransportRejectionReason.NO_COMPATIBLE_TRANSPORT_DECLARED
            ),
            CompatibleTransportSupport(responses = false, chatCompletions = false).select(),
        )
    }

    @Test
    fun testExecutionConfigurationRejectsContradictoryModes() {
        assertThrows<IllegalArgumentException> {
            HostedExecutionConfiguration(
                mode = HostedExecutionMode.INLINE_PROVIDER_TOOL,
                managedService = "wrong service",
                files = HostedExecutionFeatureSupport.UNSUPPORTED,
                callerAddressableContainer = false,
                streaming = HostedExecutionFeatureSupport.UNSUPPORTED,
                replay = HostedExecutionFeatureSupport.UNSUPPORTED,
                combinesWithCustomTools = HostedExecutionFeatureSupport.UNSUPPORTED,
                clientIntegration = ProviderClientIntegration.REQUIRES_CLIENT_INTEGRATION,
            )
        }
    }

    private fun supported(api: ProviderApi): HostedExecutionConfiguration =
        assertIs<HostedExecutionCapability.Supported>(
            ProviderCapabilityMatrix.hostedExecution(api)
        ).configuration
}
