package ai.koog.prompt.executor.managed

import aws.sdk.kotlin.services.bedrockagentcore.model.AccessDeniedException
import aws.sdk.kotlin.services.bedrockagentcore.model.CodeInterpreterResult
import aws.sdk.kotlin.services.bedrockagentcore.model.CodeInterpreterStreamOutput
import aws.sdk.kotlin.services.bedrockagentcore.model.ConflictException
import aws.sdk.kotlin.services.bedrockagentcore.model.ContentBlock
import aws.sdk.kotlin.services.bedrockagentcore.model.ContentBlockType
import aws.sdk.kotlin.services.bedrockagentcore.model.InternalServerException
import aws.sdk.kotlin.services.bedrockagentcore.model.InvokeCodeInterpreterRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.InvokeCodeInterpreterResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.ProgrammingLanguage
import aws.sdk.kotlin.services.bedrockagentcore.model.ResourceContent
import aws.sdk.kotlin.services.bedrockagentcore.model.ResourceContentType
import aws.sdk.kotlin.services.bedrockagentcore.model.ResourceNotFoundException
import aws.sdk.kotlin.services.bedrockagentcore.model.ServiceQuotaExceededException
import aws.sdk.kotlin.services.bedrockagentcore.model.StartCodeInterpreterSessionRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.StartCodeInterpreterSessionResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.StopCodeInterpreterSessionRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.TaskStatus
import aws.sdk.kotlin.services.bedrockagentcore.model.ThrottlingException
import aws.sdk.kotlin.services.bedrockagentcore.model.ToolName
import aws.sdk.kotlin.services.bedrockagentcore.model.ToolResultStructuredContent
import aws.sdk.kotlin.services.bedrockagentcore.model.ValidationException
import aws.sdk.kotlin.services.bedrockagentcore.model.ValidationExceptionReason
import aws.smithy.kotlin.runtime.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BedrockAgentCoreManagedExecutionServiceTest {
    @Test
    fun testSdkMapperUsesExactStartInvokeAndStopShapes() {
        val configuration = configuration(
            codeInterpreterIdentifier = "custom-interpreter",
            sessionTimeoutSeconds = 3_600,
            clientToken = "a12345678901234567890123456789012",
        )
        val reference = reference(
            codeInterpreterIdentifier = "custom-interpreter",
            sessionId = "Session123",
            timeoutSeconds = 3_600,
        )

        val start = BedrockAgentCoreSdkMapper.startRequest(configuration)
        assertEquals("custom-interpreter", start.codeInterpreterIdentifier)
        assertEquals("a12345678901234567890123456789012", start.clientToken)
        assertEquals("session-name", start.name)
        assertEquals(3_600, start.sessionTimeoutSeconds)
        assertNull(start.certificates)

        val invoke = BedrockAgentCoreSdkMapper.executeRequest(
            reference,
            ManagedExecutionRequest("exec", "print(1)", "python"),
        )
        assertEquals("custom-interpreter", invoke.codeInterpreterIdentifier)
        assertEquals("Session123", invoke.sessionId)
        assertEquals(ToolName.ExecuteCode, invoke.name)
        assertEquals("print(1)", invoke.arguments?.code)
        assertEquals(ProgrammingLanguage.Python, invoke.arguments?.language)
        assertNull(invoke.arguments?.content)

        val stop = BedrockAgentCoreSdkMapper.stopRequest(configuration, reference)
        assertEquals("custom-interpreter", stop.codeInterpreterIdentifier)
        assertEquals("Session123", stop.sessionId)
        assertTrue(stop.clientToken?.startsWith("koog-") == true)
        assertEquals(69, stop.clientToken?.length)
    }

    @Test
    fun testDefaultTokensAreUniqueValidAndExplicitTokenIsDeliberatelyStable() {
        val configuration = configuration(clientToken = null)
        val tokens = List(32) { BedrockAgentCoreSdkMapper.startRequest(configuration).clientToken!! }
        assertEquals(tokens.size, tokens.toSet().size)
        tokens.forEach { token ->
            assertTrue(token.length in 33..256)
            assertTrue(Regex("[A-Za-z0-9](?:-*[A-Za-z0-9]){32,255}").matches(token))
        }
        val explicit = configuration(clientToken = "a12345678901234567890123456789012")
        assertEquals(
            "a12345678901234567890123456789012",
            BedrockAgentCoreSdkMapper.startRequest(explicit).clientToken,
        )
        assertEquals(
            "a12345678901234567890123456789012",
            BedrockAgentCoreSdkMapper.startRequest(explicit).clientToken,
        )
        assertThrows<IllegalArgumentException> { configuration(sessionTimeoutSeconds = 0) }
        assertThrows<IllegalArgumentException> { configuration(sessionTimeoutSeconds = 28_801) }
        assertThrows<IllegalArgumentException> { configuration(clientToken = "short") }
    }

    @Test
    fun testSequentialAndConcurrentAcquisitionsUseDistinctDefaultTokensAndRetryReusesOne() = runTest {
        val transport = FakeTransport(startAttemptsPerCall = 3)
        val service = service(transport, configuration(clientToken = null))

        service.acquireSession()
        List(16) { async { service.acquireSession() } }.forEach { it.await() }

        val logicalTokens = transport.startRequests.map { it.clientToken!! }
        assertEquals(17, logicalTokens.size)
        assertEquals(logicalTokens.size, logicalTokens.toSet().size)
        assertEquals(
            logicalTokens.flatMap { token -> List(3) { token } },
            transport.startAttemptTokens,
        )
    }

    @Test
    fun testLegacyJvmConstructorAndCopyDescriptorsRemainAvailable() {
        val defaultConstructorMarker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        ManagedExecutionEvent.Result::class.java.getDeclaredConstructor(
            Long::class.javaPrimitiveType,
            String::class.java,
            ManagedExecutionSessionReference::class.java,
            String::class.java,
            Int::class.javaObjectType,
            List::class.java,
        )
        ManagedExecutionEvent.Result::class.java.getDeclaredConstructor(
            Long::class.javaPrimitiveType,
            String::class.java,
            ManagedExecutionSessionReference::class.java,
            String::class.java,
            Int::class.javaObjectType,
            List::class.java,
            Int::class.javaPrimitiveType,
            defaultConstructorMarker,
        )
        ManagedExecutionEvent.Result::class.java.getDeclaredMethod(
            "copy",
            Long::class.javaPrimitiveType,
            String::class.java,
            ManagedExecutionSessionReference::class.java,
            String::class.java,
            Int::class.javaObjectType,
            List::class.java,
        )
        ManagedExecutionEvent.Result::class.java.getDeclaredMethod(
            "copy\$default",
            ManagedExecutionEvent.Result::class.java,
            Long::class.javaPrimitiveType,
            String::class.java,
            ManagedExecutionSessionReference::class.java,
            String::class.java,
            Int::class.javaObjectType,
            List::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )
        ManagedExecutionFileReference.BedrockAgentCore::class.java.getDeclaredConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
        )
        ManagedExecutionFileReference.BedrockAgentCore::class.java.getDeclaredConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            defaultConstructorMarker,
        )
        ManagedExecutionFileReference.BedrockAgentCore::class.java.getDeclaredMethod(
            "copy",
            String::class.java,
            String::class.java,
            String::class.java,
        )
        ManagedExecutionFileReference.BedrockAgentCore::class.java.getDeclaredMethod(
            "copy\$default",
            ManagedExecutionFileReference.BedrockAgentCore::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )
    }

    @Test
    fun testExecuteCodeMapsAllOfficialProgrammingLanguages() {
        val reference = reference()
        val expected = mapOf(
            "python" to ProgrammingLanguage.Python,
            "javascript" to ProgrammingLanguage.Javascript,
            "typescript" to ProgrammingLanguage.Typescript,
        )
        expected.forEach { (language, programmingLanguage) ->
            val mapped = BedrockAgentCoreSdkMapper.executeRequest(
                reference,
                ManagedExecutionRequest("exec-$language", "code", language),
            )
            assertEquals(ToolName.ExecuteCode, mapped.name)
            assertEquals(programmingLanguage, mapped.arguments?.language)
            assertEquals("code", mapped.arguments?.code)
        }
    }

    @Test
    fun testFileToolMappersUseOfficialArguments() {
        val reference = reference()
        val file = ManagedExecutionInputFile("dir/data.bin", "application/octet-stream", byteArrayOf(1, 2))
        val write = BedrockAgentCoreSdkMapper.writeFilesRequest(reference, listOf(file))
        assertEquals(ToolName.WriteFiles, write.name)
        assertEquals("dir/data.bin", write.arguments?.content?.single()?.path)
        assertContentEquals(byteArrayOf(1, 2), write.arguments?.content?.single()?.blob)
        assertEquals(listOf("out.bin"), BedrockAgentCoreSdkMapper.readFilesRequest(reference, listOf("out.bin")).arguments?.paths)
        assertEquals(
            "",
            BedrockAgentCoreSdkMapper.listFilesRequest(reference, "").arguments?.directoryPath,
        )
        assertEquals(
            listOf("out.bin"),
            BedrockAgentCoreSdkMapper.removeFilesRequest(reference, listOf("out.bin")).arguments?.paths,
        )
    }

    @Test
    fun testBorrowedAttachPreservesIdentityWithoutProviderCalls() = runTest {
        val transport = FakeTransport()
        val service = service(transport)
        val reference = reference(createdAtEpochMilliseconds = 123, timeoutSeconds = 777)

        val session = service.acquireSession(reference)

        assertEquals(reference, session.reference)
        assertEquals(ManagedExecutionSessionOwnership.BORROWED, session.ownership)
        service.releaseSession(session)
        assertEquals(0, transport.totalCalls)
    }

    @Test
    fun testOwnedStartPreservesOfficialResponseAndReleaseIsIdempotent() = runTest {
        val transport = FakeTransport(
            startResponse = StartCodeInterpreterSessionResponse {
                codeInterpreterIdentifier = "aws.codeinterpreter.v1"
                sessionId = "Owned123"
                createdAt = Instant.fromEpochSeconds(12, 345_000_000)
            }
        )
        val service = service(transport)

        val session = service.acquireSession()
        val reference = assertIs<ManagedExecutionSessionReference.BedrockAgentCore>(session.reference)
        assertEquals(12_345, reference.createdAtEpochMilliseconds)
        assertEquals(900, reference.timeoutSeconds)
        assertEquals(ManagedExecutionSessionOwnership.OWNED, session.ownership)

        service.releaseSession(session)
        service.releaseSession(session)
        assertEquals(1, transport.stopRequests.size)
    }

    @Test
    fun testFailedOwnedReleaseRemainsRetryableAndSuccessfulRetryBecomesIdempotent() = runTest {
        val transport = FakeTransport()
        transport.stopHandler = { _, attempt ->
            if (attempt == 1) error("stop failed")
        }
        val service = service(transport)
        val session = ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.OWNED)

        assertFailsWith<BedrockAgentCoreLifecycleException> {
            service.releaseSession(session)
        }
        service.releaseSession(session)
        service.releaseSession(session)

        assertEquals(2, transport.stopRequests.size)
    }

    @Test
    fun testTimedOutOwnedReleaseRemainsRetryable() = runTest {
        val transport = FakeTransport()
        transport.stopHandler = { _, attempt ->
            if (attempt == 1) awaitCancellation()
        }
        val service = service(transport)
        val session = ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.OWNED)

        val completed = withTimeoutOrNull(1) {
            service.releaseSession(session)
            true
        }
        assertNull(completed)

        service.releaseSession(session)
        service.releaseSession(session)
        assertEquals(2, transport.stopRequests.size)
    }

    @Test
    fun testConcurrentSuccessfulOwnedReleasesShareOneStop() = runTest {
        val transport = FakeTransport()
        val stopEntered = CompletableDeferred<Unit>()
        val allowStop = CompletableDeferred<Unit>()
        transport.stopHandler = { _, _ ->
            stopEntered.complete(Unit)
            allowStop.await()
        }
        val service = service(transport)
        val session = ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.OWNED)

        val releases = List(8) { async { service.releaseSession(session) } }
        stopEntered.await()
        runCurrent()
        assertEquals(1, transport.stopRequests.size)

        allowStop.complete(Unit)
        releases.forEach { it.await() }
        assertEquals(1, transport.stopRequests.size)
    }

    @Test
    fun testConcurrentFailedOwnedReleaseAllowsWaitingCallerToRetry() = runTest {
        val transport = FakeTransport()
        val firstStopEntered = CompletableDeferred<Unit>()
        val allowFirstFailure = CompletableDeferred<Unit>()
        transport.stopHandler = { _, attempt ->
            if (attempt == 1) {
                firstStopEntered.complete(Unit)
                allowFirstFailure.await()
                error("stop failed")
            }
        }
        val service = service(transport)
        val session = ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.OWNED)

        supervisorScope {
            val first = async { service.releaseSession(session) }
            firstStopEntered.await()
            val retry = async { service.releaseSession(session) }
            runCurrent()
            assertEquals(1, transport.stopRequests.size)

            allowFirstFailure.complete(Unit)
            assertFailsWith<BedrockAgentCoreLifecycleException> { first.await() }
            retry.await()
        }
        service.releaseSession(session)
        assertEquals(2, transport.stopRequests.size)
    }

    @Test
    fun testCancelledOwnedReleaseAllowsWaitingCallerToRetry() = runTest {
        val transport = FakeTransport()
        val firstStopEntered = CompletableDeferred<Unit>()
        transport.stopHandler = { _, attempt ->
            if (attempt == 1) {
                firstStopEntered.complete(Unit)
                awaitCancellation()
            }
        }
        val service = service(transport)
        val session = ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.OWNED)

        val cancelledRelease = launch { service.releaseSession(session) }
        firstStopEntered.await()
        val retry = async { service.releaseSession(session) }
        runCurrent()
        assertEquals(1, transport.stopRequests.size)

        cancelledRelease.cancelAndJoin()
        retry.await()
        service.releaseSession(session)
        assertEquals(2, transport.stopRequests.size)
    }

    @Test
    fun testExecuteIsColdAndWritesFilesBeforeExecute() = runTest {
        val transport = FakeTransport()
        transport.enqueue(resultResponse())
        transport.enqueue(resultResponse(stdout = "done", exitCode = 0))
        val service = service(transport)
        val flow = service.execute(
            borrowedSession(),
            ManagedExecutionRequest(
                "exec",
                "print('done')",
                files = listOf(
                    ManagedExecutionInputFile("input.txt", "text/plain", "input".encodeToByteArray())
                ),
            ),
        )
        assertEquals(0, transport.invokeRequests.size)

        val events = flow.toList()

        assertEquals(listOf(ToolName.WriteFiles, ToolName.ExecuteCode), transport.invokeRequests.map { it.name })
        assertIs<ManagedExecutionEvent.Request>(events.first())
        assertEquals(1, events.count { it is ManagedExecutionEvent.Terminal })
        assertIs<ManagedExecutionEvent.Result>(events.last())
    }

    @Test
    fun testIncrementalStructuredAndInlineContentMapping() = runTest {
        val transport = FakeTransport()
        transport.enqueue(
            response(
                flowOf(
                    result(stdout = "one\n"),
                    result(stderr = "warn\n"),
                    result(
                        stdout = "two\n",
                        exitCode = 7,
                        executionTime = 0.25,
                        taskId = "task-1",
                        taskStatus = TaskStatus.Completed,
                        content = listOf(
                            ContentBlock {
                                type = ContentBlockType.Image
                                name = "chart.png"
                                mimeType = "image/png"
                                data = byteArrayOf(4, 5, 6)
                                size = 3
                            },
                            ContentBlock {
                                type = ContentBlockType.Text
                                text = "snapshot"
                            },
                        ),
                    ),
                )
            )
        )
        val events = service(transport).execute(borrowedSession(), request()).toList()

        assertEquals(
            listOf(
                ManagedExecutionEvent.Request::class,
                ManagedExecutionEvent.Stdout::class,
                ManagedExecutionEvent.Stderr::class,
                ManagedExecutionEvent.Stdout::class,
                ManagedExecutionEvent.GeneratedFileChunk::class,
                ManagedExecutionEvent.GeneratedFileComplete::class,
                ManagedExecutionEvent.CumulativeOutput::class,
                ManagedExecutionEvent.Result::class,
            ),
            events.map { it::class },
        )
        val chunk = assertIs<ManagedExecutionEvent.GeneratedFileChunk>(events[4])
        assertContentEquals(byteArrayOf(4, 5, 6), chunk.bytes)
        val fileReference = assertIs<ManagedExecutionFileReference.BedrockAgentCore>(chunk.reference)
        assertEquals("eu-west-1", fileReference.region)
        assertEquals("aws.codeinterpreter.v1", fileReference.codeInterpreterIdentifier)
        val terminal = assertIs<ManagedExecutionEvent.Result>(events.last())
        assertEquals("one\ntwo\n", terminal.output)
        assertEquals(7, terminal.exitCode)
        assertEquals(0.25, terminal.executionTimeSeconds)
        assertEquals("task-1", terminal.taskId)
        assertEquals("completed", terminal.taskStatus)
        assertEquals(1, terminal.generatedFiles.size)
    }

    @Test
    fun testPathOnlyContentUsesReadFilesAfterExecuteStream() = runTest {
        val transport = FakeTransport()
        transport.enqueue(
            response(
                flowOf(
                    result(
                        content = listOf(
                            ContentBlock {
                                type = ContentBlockType.ResourceLink
                                name = "report.csv"
                                uri = "report.csv"
                                mimeType = "text/csv"
                                size = 4
                            }
                        )
                    )
                )
            )
        )
        transport.enqueue(
            response(
                flowOf(
                    result(
                        content = listOf(
                            ContentBlock {
                                type = ContentBlockType.EmbeddedResource
                                resource = ResourceContent {
                                    type = ResourceContentType.Blob
                                    uri = "report.csv"
                                    mimeType = "text/csv"
                                    blob = byteArrayOf(1, 2, 3, 4)
                                }
                            }
                        )
                    )
                )
            )
        )

        val events = service(transport).execute(borrowedSession(), request()).toList()

        assertEquals(listOf(ToolName.ExecuteCode, ToolName.ReadFiles), transport.invokeRequests.map { it.name })
        assertEquals(listOf("report.csv"), transport.invokeRequests[1].arguments?.paths)
        assertContentEquals(
            byteArrayOf(1, 2, 3, 4),
            assertIs<ManagedExecutionEvent.GeneratedFileChunk>(events[1]).bytes,
        )
        assertIs<ManagedExecutionEvent.GeneratedFileComplete>(events[2])
        assertIs<ManagedExecutionEvent.Result>(events.last())
    }

    @Test
    fun testReadFilesAcceptsSuccessfulResultsAndUsesMonotonicChunkOffsets() = runTest {
        val transport = FakeTransport()
        transport.enqueue(pathOnlyResultResponse(size = 4))
        transport.enqueue(
            response(
                flowOf(
                    result(content = listOf(readFileContent("report.csv", byteArrayOf(1, 2)))),
                    result(content = listOf(readFileContent("report.csv", byteArrayOf(3, 4)))),
                )
            )
        )

        val events = service(transport).execute(borrowedSession(), request()).toList()
        val chunks = events.filterIsInstance<ManagedExecutionEvent.GeneratedFileChunk>()
        assertEquals(listOf(0L, 2L), chunks.map { it.offset })
        assertContentEquals(byteArrayOf(1, 2), chunks[0].bytes)
        assertContentEquals(byteArrayOf(3, 4), chunks[1].bytes)
        assertEquals(1, events.count { it is ManagedExecutionEvent.GeneratedFileComplete })
        assertEquals(
            4,
            assertIs<ManagedExecutionEvent.GeneratedFileComplete>(events[3]).file.sizeBytes,
        )
        assertIs<ManagedExecutionEvent.Result>(events.last())
    }

    @Test
    fun testReadFilesStreamsFirstChunkBeforeProviderProducesLastChunk() = runTest {
        val transport = FakeTransport()
        val allowLastChunk = CompletableDeferred<Unit>()
        var lastChunkProduced = false
        transport.enqueue(pathOnlyResultResponse(size = 4))
        transport.enqueue(
            response(
                flow {
                    emit(result(content = listOf(readFileContent("report.csv", byteArrayOf(1, 2)))))
                    allowLastChunk.await()
                    lastChunkProduced = true
                    emit(result(content = listOf(readFileContent("report.csv", byteArrayOf(3, 4)))))
                }
            )
        )
        val received = mutableListOf<ManagedExecutionEvent>()

        val collection = async {
            service(transport).execute(borrowedSession(), request()).collect { received += it }
        }
        runCurrent()

        assertFalse(lastChunkProduced)
        assertContentEquals(
            byteArrayOf(1, 2),
            received.filterIsInstance<ManagedExecutionEvent.GeneratedFileChunk>().single().bytes,
        )
        assertEquals(0, received.count { it is ManagedExecutionEvent.GeneratedFileComplete })

        allowLastChunk.complete(Unit)
        collection.await()
        assertEquals(2, received.count { it is ManagedExecutionEvent.GeneratedFileChunk })
        assertEquals(1, received.count { it is ManagedExecutionEvent.GeneratedFileComplete })
    }

    @Test
    fun testReadFilesOutputLimitStopsCollectionBeforeRemainingSourceIsConsumed() = runTest {
        val transport = FakeTransport()
        var producedChunks = 0
        transport.enqueue(pathOnlyResultResponse(size = null))
        transport.enqueue(
            response(
                flow {
                    producedChunks++
                    emit(result(content = listOf(readFileContent("report.csv", byteArrayOf(1, 2)))))
                    producedChunks++
                    emit(result(content = listOf(readFileContent("report.csv", byteArrayOf(3, 4)))))
                    producedChunks++
                    emit(result(content = listOf(readFileContent("report.csv", byteArrayOf(5, 6)))))
                }
            )
        )

        val events = service(transport, configuration(maxOutputBytes = 3))
            .execute(borrowedSession(), request())
            .toList()

        assertEquals(2, producedChunks)
        assertEquals(1, events.count { it is ManagedExecutionEvent.GeneratedFileChunk })
        assertEquals(0, events.count { it is ManagedExecutionEvent.GeneratedFileComplete })
        assertEquals(
            "MALFORMED_PROVIDER_RESPONSE",
            assertIs<ManagedExecutionEvent.Error>(events.last()).providerCode,
        )
    }

    @Test
    fun testReadFilesCancellationPropagatesToProviderAndNeverCommitsFile() = runTest {
        val transport = FakeTransport()
        val providerCancelled = CompletableDeferred<Unit>()
        transport.enqueue(pathOnlyResultResponse(size = null))
        transport.enqueue(
            response(
                flow {
                    try {
                        emit(result(content = listOf(readFileContent("report.csv", byteArrayOf(1, 2)))))
                        awaitCancellation()
                    } finally {
                        providerCancelled.complete(Unit)
                    }
                }
            )
        )
        val received = mutableListOf<ManagedExecutionEvent>()
        val collection = launch {
            service(transport).execute(borrowedSession(), request()).collect { received += it }
        }
        runCurrent()
        assertEquals(1, received.count { it is ManagedExecutionEvent.GeneratedFileChunk })

        collection.cancelAndJoin()

        assertTrue(providerCancelled.isCompleted)
        assertEquals(0, received.count { it is ManagedExecutionEvent.GeneratedFileComplete })
        assertEquals(0, received.count { it is ManagedExecutionEvent.Terminal })
    }

    @Test
    fun testReadFilesMalformedTailLeavesOnlyProvisionalChunks() = runTest {
        val transport = FakeTransport()
        transport.enqueue(pathOnlyResultResponse(size = null))
        transport.enqueue(
            response(
                flowOf(
                    result(content = listOf(readFileContent("report.csv", byteArrayOf(1, 2)))),
                    CodeInterpreterStreamOutput.SdkUnknown,
                )
            )
        )

        val events = service(transport).execute(borrowedSession(), request()).toList()

        assertEquals(1, events.count { it is ManagedExecutionEvent.GeneratedFileChunk })
        assertEquals(0, events.count { it is ManagedExecutionEvent.GeneratedFileComplete })
        assertEquals(
            "MALFORMED_PROVIDER_RESPONSE",
            assertIs<ManagedExecutionEvent.Error>(events.last()).providerCode,
        )
    }

    @Test
    fun testReadFilesRejectsEveryInvalidTerminalAndBlobShapeWithoutCommittingAFile() = runTest {
        val cases = listOf(
            Triple(response(flowOf()), "MALFORMED_PROVIDER_RESPONSE", 0),
            Triple(response(flowOf(CodeInterpreterStreamOutput.SdkUnknown)), "MALFORMED_PROVIDER_RESPONSE", 0),
            Triple(resultResponse(isError = true), "READ_FILES_ERROR", 0),
            Triple(response(flowOf(result(content = emptyList()))), "MALFORMED_PROVIDER_RESPONSE", 0),
            Triple(
                response(
                    flowOf(result(content = listOf(readFileContent("unrelated-secret.csv", byteArrayOf(1)))))
                ),
                "MALFORMED_PROVIDER_RESPONSE",
                0,
            ),
            Triple(
                response(
                    flowOf(
                        result(
                            content = listOf(
                                ContentBlock {
                                    type = ContentBlockType.EmbeddedResource
                                    data = byteArrayOf(1)
                                    resource = ResourceContent {
                                        type = ResourceContentType.Blob
                                        uri = "report.csv"
                                        blob = byteArrayOf(2)
                                    }
                                }
                            )
                        )
                    )
                ),
                "MALFORMED_PROVIDER_RESPONSE",
                0,
            ),
            Triple(
                response(
                    flowOf(result(content = listOf(readFileContent("report.csv", byteArrayOf()))))
                ),
                "MALFORMED_PROVIDER_RESPONSE",
                0,
            ),
            Triple(
                response(
                    flowOf(result(content = listOf(readFileContent("report.csv", byteArrayOf(1, 2)))))
                ),
                "MALFORMED_PROVIDER_RESPONSE",
                1,
            ),
        )

        cases.forEachIndexed { index, (readResponse, expectedCode, expectedChunks) ->
            val transport = FakeTransport()
            transport.enqueue(pathOnlyResultResponse(size = if (index == cases.lastIndex) 4 else null))
            transport.enqueue(readResponse)

            val events = service(transport).execute(borrowedSession(), request()).toList()
            val error = assertIs<ManagedExecutionEvent.Error>(events.last())
            assertEquals(expectedCode, error.providerCode)
            assertEquals(1, events.count { it is ManagedExecutionEvent.Terminal })
            assertEquals(expectedChunks, events.count { it is ManagedExecutionEvent.GeneratedFileChunk })
            assertEquals(0, events.count { it is ManagedExecutionEvent.GeneratedFileComplete })
            assertFalse(error.message.contains("unrelated-secret.csv"))
        }
    }

    @Test
    fun testCancellationDuringReadFilesStopsOwnedSessionWithoutCompletingFile() = runTest {
        val transport = FakeTransport()
        transport.enqueue(pathOnlyResultResponse(size = null))
        transport.enqueue(response(flow { awaitCancellation() }))
        val received = mutableListOf<ManagedExecutionEvent>()
        val session = ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.OWNED)

        val collection = launch {
            service(transport).execute(session, request()).collect { received += it }
        }
        runCurrent()
        collection.cancelAndJoin()

        assertEquals(1, transport.stopRequests.size)
        assertEquals(0, received.count { it is ManagedExecutionEvent.GeneratedFileComplete })
        assertEquals(0, received.count { it is ManagedExecutionEvent.Terminal })
    }

    @Test
    fun testEveryOfficialEventStreamExceptionMapsToRedactedStableError() = runTest {
        val failures = listOf(
            AccessDeniedException { message = "secret request aws-123" } to "ACCESS_DENIED",
            ConflictException { message = "secret request aws-123" } to "CONFLICT",
            InternalServerException { message = "secret request aws-123" } to "INTERNAL_ERROR",
            ResourceNotFoundException { message = "secret request aws-123" } to "SESSION_NOT_FOUND",
            ServiceQuotaExceededException { message = "secret request aws-123" } to "QUOTA_EXCEEDED",
            ThrottlingException { message = "secret request aws-123" } to "THROTTLED",
            ValidationException {
                message = "secret request aws-123"
                reason = ValidationExceptionReason.FieldValidationFailed
            } to "VALIDATION_ERROR",
        )
        failures.forEach { (failure, expectedCode) ->
            val transport = FakeTransport()
            transport.enqueue(failure)
            val terminal = service(transport).execute(borrowedSession(), request()).toList().last()
            val error = assertIs<ManagedExecutionEvent.Error>(terminal)
            assertEquals(expectedCode, error.providerCode)
            assertFalse(error.message.contains("secret"))
            assertFalse(error.message.contains("aws-123"))
        }
    }

    @Test
    fun testIsErrorMalformedUnionAndMissingTerminalMapOnce() = runTest {
        val cases = listOf(
            resultResponse(isError = true) to "CODE_INTERPRETER_ERROR",
            response(flowOf(CodeInterpreterStreamOutput.SdkUnknown)) to "MALFORMED_PROVIDER_RESPONSE",
            response(flowOf()) to "MISSING_RESULT",
            response(
                flowOf(
                    CodeInterpreterStreamOutput.Result(
                        CodeInterpreterResult { content = emptyList() }
                    )
                )
            ) to
                "MALFORMED_PROVIDER_RESPONSE",
        )
        cases.forEach { (response, code) ->
            val transport = FakeTransport()
            transport.enqueue(response)
            val events = service(transport).execute(borrowedSession(), request()).toList()
            val errors = events.filterIsInstance<ManagedExecutionEvent.Error>()
            assertEquals(1, errors.size)
            assertEquals(code, errors.single().providerCode)
            assertEquals(1, events.count { it is ManagedExecutionEvent.Terminal })
            assertEquals(events.last(), errors.single())
        }
    }

    @Test
    fun testProviderStreamIsConsumedWithBackpressure() = runTest {
        val transport = FakeTransport()
        transport.enqueue(
            response(
                flow {
                    emit(result(stdout = "first"))
                    awaitCancellation()
                }
            )
        )
        val events = service(transport)
            .execute(borrowedSession(), request())
            .take(2)
            .toList()

        assertIs<ManagedExecutionEvent.Request>(events[0])
        assertEquals("first", assertIs<ManagedExecutionEvent.Stdout>(events[1]).text)
        assertEquals(1, transport.invokeRequests.size)
    }

    @Test
    fun testRequestCollectorFailurePropagatesUnchangedWithoutProviderError() = runTest {
        val transport = FakeTransport()
        val session = ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.OWNED)
        val failure = IllegalStateException("request-collector-secret")
        val received = mutableListOf<ManagedExecutionEvent>()

        val thrown = assertFailsWith<IllegalStateException> {
            service(transport).execute(session, request()).collect { event ->
                received += event
                if (event is ManagedExecutionEvent.Request) throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(listOf(ManagedExecutionEvent.Request::class), received.map { it::class })
        assertTransparentCollectorFailure(failure, received)
        assertEquals(0, transport.invokeRequests.size)
        assertEquals(1, transport.stopRequests.size)
    }

    @Test
    fun testStdoutCollectorFailurePropagatesUnchangedWithoutProviderError() = runTest {
        val transport = FakeTransport()
        transport.enqueue(resultResponse(stdout = "provider output"))
        val session = ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.OWNED)
        val failure = IllegalArgumentException("stdout-collector-secret")
        val received = mutableListOf<ManagedExecutionEvent>()

        val thrown = assertFailsWith<IllegalArgumentException> {
            service(transport).execute(session, request()).collect { event ->
                received += event
                if (event is ManagedExecutionEvent.Stdout) throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(
            listOf(ManagedExecutionEvent.Request::class, ManagedExecutionEvent.Stdout::class),
            received.map { it::class },
        )
        assertTransparentCollectorFailure(failure, received)
        assertEquals(1, transport.invokeRequests.size)
        assertEquals(1, transport.stopRequests.size)
    }

    @Test
    fun testGeneratedFileChunkCollectorFailurePropagatesUnchangedWithoutProviderError() = runTest {
        val transport = FakeTransport()
        transport.enqueue(
            response(
                flowOf(
                    result(
                        content = listOf(
                            ContentBlock {
                                type = ContentBlockType.Image
                                name = "chart.png"
                                mimeType = "image/png"
                                data = byteArrayOf(1, 2, 3)
                                size = 3
                            }
                        )
                    )
                )
            )
        )
        val session = ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.OWNED)
        val failure = UnsupportedOperationException("file-chunk-collector-secret")
        val received = mutableListOf<ManagedExecutionEvent>()

        val thrown = assertFailsWith<UnsupportedOperationException> {
            service(transport).execute(session, request()).collect { event ->
                received += event
                if (event is ManagedExecutionEvent.GeneratedFileChunk) throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(
            listOf(ManagedExecutionEvent.Request::class, ManagedExecutionEvent.GeneratedFileChunk::class),
            received.map { it::class },
        )
        assertTransparentCollectorFailure(failure, received)
        assertEquals(0, received.count { it is ManagedExecutionEvent.GeneratedFileComplete })
        assertEquals(1, transport.stopRequests.size)
    }

    @Test
    fun testTerminalCollectorFailurePropagatesUnchangedWithoutProviderError() = runTest {
        val transport = FakeTransport()
        transport.enqueue(resultResponse(exitCode = 0))
        val session = ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.OWNED)
        val failure = IllegalStateException("terminal-collector-secret")
        val received = mutableListOf<ManagedExecutionEvent>()

        val thrown = assertFailsWith<IllegalStateException> {
            service(transport).execute(session, request()).collect { event ->
                received += event
                if (event is ManagedExecutionEvent.Result) throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(
            listOf(ManagedExecutionEvent.Request::class, ManagedExecutionEvent.Result::class),
            received.map { it::class },
        )
        assertTransparentCollectorFailure(failure, received)
        assertEquals(1, transport.stopRequests.size)
    }

    @Test
    fun testProviderRuntimeExceptionStillMapsToRedactedSdkFailure() = runTest {
        val transport = FakeTransport()
        transport.enqueue(IllegalStateException("provider-sdk-secret"))
        val session = ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.OWNED)

        val events = service(transport).execute(session, request()).toList()

        val error = assertIs<ManagedExecutionEvent.Error>(events.last())
        assertEquals("SDK_FAILURE", error.providerCode)
        assertFalse(events.any { it.toString().contains("provider-sdk-secret") })
        assertEquals(1, events.count { it is ManagedExecutionEvent.Error })
        assertEquals(1, transport.stopRequests.size)
    }

    @Test
    fun testCollectorCancellationPropagatesUnchangedAndRespectsSessionOwnership() = runTest {
        listOf(
            ManagedExecutionSessionOwnership.OWNED to 1,
            ManagedExecutionSessionOwnership.BORROWED to 0,
        ).forEach { (ownership, expectedStops) ->
            val transport = FakeTransport()
            transport.enqueue(resultResponse(stdout = "provider output"))
            val session = ManagedExecutionSession(reference(), ownership)
            val cancellation = kotlinx.coroutines.CancellationException("cancellation-collector-secret")
            val received = mutableListOf<ManagedExecutionEvent>()

            val thrown = assertFailsWith<kotlinx.coroutines.CancellationException> {
                service(transport).execute(session, request()).collect { event ->
                    received += event
                    if (event is ManagedExecutionEvent.Stdout) throw cancellation
                }
            }

            assertSame(cancellation, thrown)
            assertEquals(
                listOf(ManagedExecutionEvent.Request::class, ManagedExecutionEvent.Stdout::class),
                received.map { it::class },
            )
            assertTransparentCollectorFailure(cancellation, received)
            assertEquals(expectedStops, transport.stopRequests.size)
        }
    }

    @Test
    fun testCancellationStopsOwnedSessionButPreservesBorrowedSession() = runTest {
        suspend fun cancelExecution(session: ManagedExecutionSession): FakeTransport {
            val transport = FakeTransport()
            transport.enqueue(response(flow { awaitCancellation() }))
            val job = launch { service(transport).execute(session, request()).toList() }
            runCurrent()
            job.cancelAndJoin()
            return transport
        }

        val owned = cancelExecution(
            ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.OWNED)
        )
        val borrowed = cancelExecution(borrowedSession())

        assertEquals(1, owned.stopRequests.size)
        assertEquals(0, borrowed.stopRequests.size)
    }

    @Test
    fun testRequestBoundaryCancellationStopsOwnedExactlyOnceBeforeInvocation() = runTest {
        val ownedTransport = FakeTransport()
        val ownedService = service(ownedTransport)
        val ownedSession = ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.OWNED)

        val events = ownedService.execute(ownedSession, request()).take(1).toList()
        ownedService.releaseSession(ownedSession)

        assertIs<ManagedExecutionEvent.Request>(events.single())
        assertEquals(0, ownedTransport.invokeRequests.size)
        assertEquals(1, ownedTransport.stopRequests.size)

        val borrowedTransport = FakeTransport()
        val borrowedEvents = service(borrowedTransport).execute(borrowedSession(), request()).take(1).toList()
        assertIs<ManagedExecutionEvent.Request>(borrowedEvents.single())
        assertEquals(0, borrowedTransport.totalCalls)
    }

    @Test
    fun testFileChunkBoundaryCancellationStopsOwnedAndNeverEmitsComplete() = runTest {
        val transport = FakeTransport()
        transport.enqueue(pathOnlyResultResponse(size = 4))
        transport.enqueue(
            response(
                flowOf(
                    result(
                        content = listOf(
                            readFileContent("report.csv", byteArrayOf(1, 2)),
                            readFileContent("report.csv", byteArrayOf(3, 4)),
                        )
                    )
                )
            )
        )
        val session = ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.OWNED)

        val events = service(transport).execute(session, request()).take(2).toList()

        assertIs<ManagedExecutionEvent.GeneratedFileChunk>(events.last())
        assertEquals(0, events.count { it is ManagedExecutionEvent.GeneratedFileComplete })
        assertEquals(1, transport.stopRequests.size)
    }

    @Test
    fun testOwnedPreflightFailureStopsSessionAndPreservesOriginalFailure() = runTest {
        val transport = FakeTransport()
        val session = ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.OWNED)

        assertFailsWith<IllegalArgumentException> {
            service(transport).execute(session, request().copy(language = "ruby")).toList()
        }

        assertEquals(0, transport.invokeRequests.size)
        assertEquals(1, transport.stopRequests.size)
    }

    @Test
    fun testInvalidPreflightMakesNoSdkCalls() = runTest {
        val invalidRequests = listOf(
            request().copy(executionId = ""),
            request().copy(language = "ruby"),
            request().copy(
                files = listOf(ManagedExecutionInputFile("../secret", "text/plain", byteArrayOf()))
            ),
            request().copy(
                files = listOf(ManagedExecutionInputFile("ok", "invalid media", byteArrayOf()))
            ),
        )
        invalidRequests.forEach { request ->
            val transport = FakeTransport()
            assertFailsWith<IllegalArgumentException> {
                service(transport).execute(borrowedSession(), request).toList()
            }
            assertEquals(0, transport.totalCalls)
        }
    }

    @Test
    fun testSameSessionIdInDifferentRegionsOrInterpretersRemainsDistinctAndRedacted() {
        val first = ManagedExecutionFileReference.BedrockAgentCore(
            region = "eu-west-1",
            codeInterpreterIdentifier = "aws.codeinterpreter.v1",
            sessionId = "Same123",
            path = "file.txt",
        )
        val otherRegion = first.copy(region = "us-east-1")
        val otherInterpreter = first.copy(codeInterpreterIdentifier = "custom")
        assertNotEquals(first, otherRegion)
        assertNotEquals(first, otherInterpreter)

        val configuration = configuration(clientToken = "a12345678901234567890123456789012")
        assertFalse(configuration.toString().contains("a12345678901234567890123456789012"))
        val input = ManagedExecutionInputFile("secret.bin", "application/octet-stream", byteArrayOf(9, 8))
        assertContains(input.toString(), "byteCount=2")
        assertFalse(input.toString().contains("[9, 8]"))
    }

    private fun service(
        transport: FakeTransport,
        configuration: BedrockAgentCoreConfiguration = configuration(),
    ): BedrockAgentCoreManagedExecutionService =
        BedrockAgentCoreManagedExecutionService(transport, configuration)

    private fun configuration(
        codeInterpreterIdentifier: String = "aws.codeinterpreter.v1",
        sessionTimeoutSeconds: Int = 900,
        clientToken: String? = "a12345678901234567890123456789012",
        maxOutputBytes: Long = BedrockAgentCoreConfiguration.MAX_TOOL_ARGUMENT_BYTES,
    ): BedrockAgentCoreConfiguration = BedrockAgentCoreConfiguration(
        region = "eu-west-1",
        codeInterpreterIdentifier = codeInterpreterIdentifier,
        sessionName = "session-name",
        sessionTimeoutSeconds = sessionTimeoutSeconds,
        clientToken = clientToken,
        maxOutputBytes = maxOutputBytes,
    )

    private fun reference(
        region: String = "eu-west-1",
        codeInterpreterIdentifier: String = "aws.codeinterpreter.v1",
        sessionId: String = "Session123",
        createdAtEpochMilliseconds: Long = 100,
        timeoutSeconds: Int = 900,
    ): ManagedExecutionSessionReference.BedrockAgentCore =
        ManagedExecutionSessionReference.BedrockAgentCore(
            region,
            codeInterpreterIdentifier,
            sessionId,
            createdAtEpochMilliseconds,
            timeoutSeconds,
        )

    private fun borrowedSession(): ManagedExecutionSession =
        ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.BORROWED)

    private fun request(): ManagedExecutionRequest =
        ManagedExecutionRequest("exec", "print('ok')", "python")

    private fun assertTransparentCollectorFailure(
        failure: Throwable,
        events: List<ManagedExecutionEvent>,
    ) {
        assertEquals(0, events.count { it is ManagedExecutionEvent.Error })
        assertFalse(events.any { it.toString().contains(failure.message.orEmpty()) })
    }

    private fun resultResponse(
        stdout: String? = null,
        exitCode: Int? = null,
        isError: Boolean? = false,
    ): InvokeCodeInterpreterResponse = response(flowOf(result(stdout = stdout, exitCode = exitCode, isError = isError)))

    private fun pathOnlyResultResponse(size: Long?): InvokeCodeInterpreterResponse =
        response(
            flowOf(
                result(
                    content = listOf(
                        ContentBlock {
                            type = ContentBlockType.ResourceLink
                            name = "report.csv"
                            uri = "report.csv"
                            mimeType = "text/csv"
                            this.size = size
                        }
                    )
                )
            )
        )

    private fun readFileContent(path: String, bytes: ByteArray): ContentBlock =
        ContentBlock {
            type = ContentBlockType.EmbeddedResource
            resource = ResourceContent {
                type = ResourceContentType.Blob
                uri = path
                mimeType = "text/csv"
                blob = bytes
            }
        }

    private fun response(
        stream: Flow<CodeInterpreterStreamOutput>,
        sessionId: String = "Session123",
    ): InvokeCodeInterpreterResponse = InvokeCodeInterpreterResponse {
        this.sessionId = sessionId
        this.stream = stream
    }

    private fun result(
        stdout: String? = null,
        stderr: String? = null,
        exitCode: Int? = null,
        executionTime: Double? = null,
        taskId: String? = null,
        taskStatus: TaskStatus? = null,
        content: List<ContentBlock> = emptyList(),
        isError: Boolean? = false,
    ): CodeInterpreterStreamOutput.Result = CodeInterpreterStreamOutput.Result(
        CodeInterpreterResult {
            this.content = content
            this.isError = isError
            if (
                stdout != null ||
                stderr != null ||
                exitCode != null ||
                executionTime != null ||
                taskId != null ||
                taskStatus != null
            ) {
                structuredContent = ToolResultStructuredContent {
                    this.stdout = stdout
                    this.stderr = stderr
                    this.exitCode = exitCode
                    this.executionTime = executionTime
                    this.taskId = taskId
                    this.taskStatus = taskStatus
                }
            }
        }
    )
}

private class FakeTransport(
    var startResponse: StartCodeInterpreterSessionResponse = StartCodeInterpreterSessionResponse {
        codeInterpreterIdentifier = "aws.codeinterpreter.v1"
        sessionId = "Session123"
        createdAt = Instant.fromEpochSeconds(1)
    },
    private val startAttemptsPerCall: Int = 1,
) : BedrockAgentCoreTransport {
    val startRequests = mutableListOf<StartCodeInterpreterSessionRequest>()
    val startAttemptTokens = mutableListOf<String>()
    val invokeRequests = mutableListOf<InvokeCodeInterpreterRequest>()
    val stopRequests = mutableListOf<StopCodeInterpreterSessionRequest>()
    var stopHandler: suspend (StopCodeInterpreterSessionRequest, Int) -> Unit = { _, _ -> }
    private val invokeActions = ArrayDeque<Any>()

    val totalCalls: Int
        get() = startRequests.size + invokeRequests.size + stopRequests.size

    fun enqueue(response: InvokeCodeInterpreterResponse) {
        invokeActions += response
    }

    fun enqueue(failure: Throwable) {
        invokeActions += failure
    }

    override suspend fun start(request: StartCodeInterpreterSessionRequest): StartCodeInterpreterSessionResponse {
        startRequests += request
        repeat(startAttemptsPerCall) {
            startAttemptTokens += request.clientToken!!
        }
        return startResponse
    }

    override suspend fun invoke(
        request: InvokeCodeInterpreterRequest,
        block: suspend (InvokeCodeInterpreterResponse) -> Unit,
    ) {
        invokeRequests += request
        when (val action = invokeActions.removeFirstOrNull() ?: error("No fake invoke response")) {
            is InvokeCodeInterpreterResponse -> block(action)
            is Throwable -> throw action
        }
    }

    override suspend fun stop(request: StopCodeInterpreterSessionRequest) {
        stopRequests += request
        stopHandler(request, stopRequests.size)
    }
}
