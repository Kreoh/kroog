package ai.koog.prompt.message

import ai.koog.prompt.Prompt
import ai.koog.prompt.streaming.StreamFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ManagedExecutionPresentationReplayTest {
    private val session = ManagedExecutionSessionReference.VertexAgentEngine(
        project = "project-1",
        location = "us-central1",
        reasoningEngineResource = "reasoningEngines/engine-1",
        sandboxResourceName = "reasoningEngines/engine-1/sandboxes/box-1",
    )

    @Test
    fun testClientManagedPresentationIsAcceptedOnlyWithMatchingOrdinaryTranscript() {
        managedPrompt(
            includeCall = true,
            includeResult = true,
        ).validateClientManagedExecutionPresentation()

        listOf(
            managedPrompt(includeCall = false, includeResult = true),
            managedPrompt(includeCall = true, includeResult = false),
        ).forEach { prompt ->
            val failure = assertFailsWith<ClientManagedPresentationReplayException> {
                prompt.validateClientManagedExecutionPresentation()
            }
            assertEquals(
                ManagedExecutionPresentationReplayFailure.MISSING_ORDINARY_TOOL_TRANSCRIPT,
                failure.reason,
            )
        }
    }

    @Test
    fun testManagedReferenceWithNativeOriginFailsBeforeProviderMapping() {
        val prompt = managedPrompt(
            includeCall = true,
            includeResult = true,
            origin = ExecutionOrigin.NATIVE_PROVIDER_HOSTED,
        )

        val failure = assertFailsWith<ClientManagedPresentationReplayException> {
            prompt.validateClientManagedExecutionPresentation()
        }

        assertEquals(ManagedExecutionPresentationReplayFailure.INVALID_ORIGIN_REFERENCE, failure.reason)
    }

    @Test
    fun testMalformedOrderedTranscriptsFailWithPreciseTypedReasons() {
        val call = toolCall()
        val result = toolResult()
        val terminal = managedTerminal()
        val otherSession = session.copy(sandboxResourceName = "reasoningEngines/engine-1/sandboxes/box-2")
        val cases = listOf(
            ManagedExecutionPresentationReplayFailure.DUPLICATE_TOOL_CALL to listOf(call, call, terminal, result),
            ManagedExecutionPresentationReplayFailure.DUPLICATE_TOOL_RESULT to listOf(call, terminal, result, result),
            ManagedExecutionPresentationReplayFailure.RESULT_BEFORE_CALL to listOf(result, call, terminal),
            ManagedExecutionPresentationReplayFailure.PRESENTATION_OUTSIDE_TOOL_TRANSCRIPT to
                listOf(call, result, terminal),
            ManagedExecutionPresentationReplayFailure.TOOL_NAME_MISMATCH to
                listOf(call, terminal, toolResult(tool = "wrong_tool")),
            ManagedExecutionPresentationReplayFailure.TOOL_NAME_MISMATCH to
                listOf(toolCall(tool = "wrong_tool"), terminal, toolResult(tool = "wrong_tool")),
            ManagedExecutionPresentationReplayFailure.SESSION_MISMATCH to listOf(
                call,
                managedRequest(),
                managedTerminal(session = otherSession),
                result,
            ),
            ManagedExecutionPresentationReplayFailure.PROVIDER_ITEM_MISMATCH to listOf(
                call,
                managedRequest(providerItemId = "provider-1"),
                managedTerminal(providerItemId = "provider-2"),
                result,
            ),
            ManagedExecutionPresentationReplayFailure.DUPLICATE_TERMINAL to
                listOf(call, terminal, terminal.copy(output = "again"), result),
            ManagedExecutionPresentationReplayFailure.MISSING_TERMINAL to
                listOf(call, managedRequest(), result),
            ManagedExecutionPresentationReplayFailure.DUPLICATE_FILE_COMPLETION to listOf(
                call,
                managedFile(),
                managedFile(),
                terminal,
                result,
            ),
            ManagedExecutionPresentationReplayFailure.PROVIDER_SESSION_MISMATCH to listOf(
                call,
                managedFile(
                    reference = ManagedExecutionFileReference.BedrockAgentCore(
                        sessionId = "bedrock-session",
                        path = "/tmp/file.txt",
                        region = "eu-west-1",
                    )
                ),
                terminal,
                result,
            ),
        )

        cases.forEach { (expected, parts) ->
            val failure = assertFailsWith<ClientManagedPresentationReplayException> {
                prompt(parts).validateClientManagedExecutionPresentation()
            }
            assertEquals(expected, failure.reason)
        }
    }

    @Test
    fun testCrossCallInterleavingAndMixedOriginsAreRejected() {
        val crossCall = prompt(
            listOf(
                toolCall("call-a"),
                managedTerminal("call-a"),
                toolResult("call-a"),
                toolCall("call-b"),
                managedTerminal("call-b"),
                toolResult("call-b"),
            )
        )
        assertReason(ManagedExecutionPresentationReplayFailure.CROSS_CALL_PAIRING, crossCall)

        val interleaved = prompt(
            listOf(
                toolCall("call-a"),
                toolCall("call-b"),
                managedRequest("call-a", executionId = "execution-a"),
                managedTerminal("call-b", executionId = "execution-b"),
                managedTerminal("call-a", executionId = "execution-a"),
                toolResult("call-a"),
                toolResult("call-b"),
            )
        )
        assertReason(ManagedExecutionPresentationReplayFailure.INTERLEAVED_PRESENTATION, interleaved)

        val mixedOrigin = prompt(
            listOf(
                toolCall(),
                MessagePart.HostedExecution.Request(
                    code = "print(1)",
                    executionId = "execution-1",
                    origin = ExecutionOrigin.NATIVE_PROVIDER_HOSTED,
                ),
                managedTerminal(),
                toolResult(),
            )
        )
        assertReason(ManagedExecutionPresentationReplayFailure.MIXED_EXECUTION_ORIGIN, mixedOrigin)
    }

    @Test
    fun testLegacyJvmDescriptorsRemainReflectivelyAvailable() {
        assertNotNull(
            MessagePart.GeneratedFile::class.java.getDeclaredConstructor(
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Long::class.javaObjectType,
                String::class.java,
                String::class.java,
                CacheControl::class.java,
            )
        )
        assertNotNull(
            MessagePart.GeneratedFile::class.java.getDeclaredMethod(
                "copy",
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Long::class.javaObjectType,
                String::class.java,
                String::class.java,
                CacheControl::class.java,
            )
        )
        assertNotNull(
            StreamFrame.GeneratedFileBytes::class.java.getDeclaredConstructor(
                String::class.java,
                ByteArray::class.java,
                java.lang.Long.TYPE,
                String::class.java,
                Int::class.javaObjectType,
                String::class.java,
            )
        )
        assertNotNull(
            StreamFrame.GeneratedFileBytes::class.java.getDeclaredMethod(
                "copy",
                String::class.java,
                ByteArray::class.java,
                java.lang.Long.TYPE,
                String::class.java,
                Int::class.javaObjectType,
                String::class.java,
            )
        )
        assertNotNull(
            MessagePart.HostedExecution.Progress::class.java.getDeclaredMethod(
                "copy",
                String::class.java,
                Int::class.javaObjectType,
                String::class.java,
                String::class.java,
                String::class.java,
                CacheControl::class.java,
            )
        )
    }

    private fun assertReason(expected: ManagedExecutionPresentationReplayFailure, prompt: Prompt) {
        val failure = assertFailsWith<ClientManagedPresentationReplayException> {
            prompt.validateClientManagedExecutionPresentation()
        }
        assertEquals(expected, failure.reason)
    }

    private fun prompt(parts: List<MessagePart>): Prompt = Prompt(
        messages = parts.map { part ->
            when (part) {
                is MessagePart.RequestPart -> Message.User(part, RequestMetaInfo.Empty)
                is MessagePart.ResponsePart -> Message.Assistant(part, ResponseMetaInfo.Empty)
            }
        },
        id = "managed-prompt",
    )

    private fun toolCall(
        id: String = "tool-call-1",
        tool: String = "managed_execution",
    ): MessagePart.Tool.Call = MessagePart.Tool.Call(
        id = id,
        tool = tool,
        args = """{"executionId":"execution-1","code":"print(1)"}""",
    )

    private fun toolResult(
        id: String = "tool-call-1",
        tool: String = "managed_execution",
    ): MessagePart.Tool.Result = MessagePart.Tool.Result(id = id, tool = tool, output = "done")

    private fun managedRequest(
        toolCallId: String = "tool-call-1",
        executionId: String = "execution-1",
        providerItemId: String? = null,
    ): MessagePart.HostedExecution.Request = MessagePart.HostedExecution.Request(
        code = "print(1)",
        executionId = executionId,
        providerItemId = providerItemId,
        origin = ExecutionOrigin.CLIENT_MANAGED,
        managedSession = session,
        toolCallId = toolCallId,
    )

    private fun managedTerminal(
        toolCallId: String = "tool-call-1",
        executionId: String = "execution-1",
        session: ManagedExecutionSessionReference = this.session,
        providerItemId: String? = null,
    ): MessagePart.HostedExecution.Result = MessagePart.HostedExecution.Result(
        output = "done",
        executionId = executionId,
        providerItemId = providerItemId,
        origin = ExecutionOrigin.CLIENT_MANAGED,
        managedSession = session,
        toolCallId = toolCallId,
    )

    private fun managedFile(
        reference: ManagedExecutionFileReference = ManagedExecutionFileReference.VertexAgentEngine(
            sandboxResourceName = session.sandboxResourceName,
            path = "/tmp/file.txt",
            providerFileId = "provider-file-1",
        ),
    ): MessagePart.GeneratedFile = MessagePart.GeneratedFile(
        providerFileId = "provider-file-1",
        producingExecutionId = "execution-1",
        origin = ExecutionOrigin.CLIENT_MANAGED,
        fileId = "file-1",
        managedReference = reference,
        managedSession = session,
        toolCallId = "tool-call-1",
    )

    private fun managedPrompt(
        includeCall: Boolean,
        includeResult: Boolean,
        origin: ExecutionOrigin = ExecutionOrigin.CLIENT_MANAGED,
    ): Prompt {
        val messages = buildList<Message> {
            if (includeCall) {
                add(
                    Message.Assistant(
                        part = MessagePart.Tool.Call(
                            id = "tool-call-1",
                            tool = "managed_execution",
                            args = """{"executionId":"execution-1","code":"print(1)"}""",
                        ),
                        metaInfo = ResponseMetaInfo.Empty,
                    )
                )
            }
            add(
                Message.Assistant(
                    part = MessagePart.HostedExecution.Result(
                        output = "done",
                        executionId = "execution-1",
                        origin = origin,
                        managedSession = session,
                        toolCallId = "tool-call-1",
                    ),
                    metaInfo = ResponseMetaInfo.Empty,
                )
            )
            if (includeResult) {
                add(
                    Message.User(
                        part = MessagePart.Tool.Result(
                            id = "tool-call-1",
                            tool = "managed_execution",
                            output = "done",
                        ),
                        metaInfo = RequestMetaInfo.Empty,
                    )
                )
            }
        }
        return Prompt(messages, "managed-prompt")
    }
}
