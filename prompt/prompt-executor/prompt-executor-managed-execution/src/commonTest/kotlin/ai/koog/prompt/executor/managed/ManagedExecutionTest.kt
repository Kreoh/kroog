package ai.koog.prompt.executor.managed

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagedExecutionTest {
    private val json = Json {
        classDiscriminator = "provider"
    }

    @Test
    fun testProviderSessionReferencesRoundTripWithoutIdentityLoss() {
        val references = listOf<ManagedExecutionSessionReference>(
            ManagedExecutionSessionReference.VertexAgentEngine(
                project = "project-1",
                location = "us-central1",
                reasoningEngineResource = "projects/project-1/locations/us-central1/reasoningEngines/engine-1",
                sandboxResourceName = "projects/project-1/locations/us-central1/reasoningEngines/engine-1/sandboxes/box-1",
                expiresAtEpochMilliseconds = 1_800_000_000_000,
            ),
            ManagedExecutionSessionReference.BedrockAgentCore(
                region = "eu-west-1",
                codeInterpreterIdentifier = "aws.codeinterpreter.v1",
                sessionId = "session-1",
                createdAtEpochMilliseconds = 1_700_000_000_000,
                timeoutSeconds = 900,
            ),
        )

        references.forEach { reference ->
            val encoded = json.encodeToString<ManagedExecutionSessionReference>(reference)
            assertEquals(reference, json.decodeFromString<ManagedExecutionSessionReference>(encoded))
        }
    }

    @Test
    fun testVertexSessionLanguageRoundTripsAndLegacyOmissionDecodesSafely() {
        val sandboxResource =
            "projects/project-1/locations/us-central1/reasoningEngines/engine-1/sandboxEnvironments/box-1"
        val reference = ManagedExecutionSessionReference.VertexAgentEngine(
            project = "project-1",
            location = "us-central1",
            reasoningEngineResource = "projects/project-1/locations/us-central1/reasoningEngines/engine-1",
            sandboxResourceName = sandboxResource,
            codeLanguage = VertexAgentEngineCodeLanguage.JAVASCRIPT,
        )

        val encoded = json.encodeToString<ManagedExecutionSessionReference>(reference)
        val decoded = json.decodeFromString<ManagedExecutionSessionReference>(encoded)

        assertTrue(encoded.contains(""""codeLanguage":"JAVASCRIPT""""))
        assertEquals(reference, decoded)

        val legacy = """
            {
              "provider":"vertex_agent_engine",
              "project":"project-1",
              "location":"us-central1",
              "reasoningEngineResource":"projects/project-1/locations/us-central1/reasoningEngines/engine-1",
              "sandboxResourceName":"projects/project-1/locations/us-central1/reasoningEngines/engine-1/sandboxEnvironments/box-1"
            }
        """.trimIndent()
        val legacyDecoded = json.decodeFromString<ManagedExecutionSessionReference>(legacy)
        assertNull(assertIs<ManagedExecutionSessionReference.VertexAgentEngine>(legacyDecoded).codeLanguage)
    }

    @Test
    fun testManagedExecutionRequestDefaultFilesSerialiseAndDecodeAsEmpty() {
        val request = ManagedExecutionRequest(
            executionId = "execution-default-files",
            code = "print('hello')",
        )

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<ManagedExecutionRequest>(encoded)

        assertFalse(encoded.contains(""""files""""))
        assertEquals(emptyList(), decoded.files)
        assertEquals(request, decoded)
    }

    @Test
    fun testInputFileUsesContentValueSemanticsRedactedRenderingAndRoundTrip() {
        val payloadSentinel = "managed-input-payload-sentinel"
        val first = ManagedExecutionInputFile(
            filename = "input.txt",
            mediaType = "text/plain",
            bytes = payloadSentinel.encodeToByteArray(),
        )
        val equal = ManagedExecutionInputFile(
            filename = "input.txt",
            mediaType = "text/plain",
            bytes = payloadSentinel.encodeToByteArray(),
        )
        val unequal = ManagedExecutionInputFile(
            filename = "input.txt",
            mediaType = "text/plain",
            bytes = "different".encodeToByteArray(),
        )

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotEquals(first, unequal)

        val rendered = first.toString()
        assertEquals(
            "ManagedExecutionInputFile(filename=input.txt, mediaType=text/plain, byteCount=30)",
            rendered,
        )
        assertFalse(rendered.contains(payloadSentinel))
        assertFalse(rendered.contains("bytes="))
        assertFalse(rendered.contains("[B@"))

        val encoded = json.encodeToString(first)
        val decoded = json.decodeFromString<ManagedExecutionInputFile>(encoded)
        assertEquals(first, decoded)
        assertEquals(first.hashCode(), decoded.hashCode())
    }

    @Test
    fun testBinaryFileChunkRoundTripsWithOptionalProviderIdentity() {
        val reference = ManagedExecutionFileReference.BedrockAgentCore(
            sessionId = "session-1",
            path = "/tmp/result.bin",
        )
        val event = ManagedExecutionEvent.GeneratedFileChunk(
            sequence = 2,
            executionId = "execution-1",
            session = bedrockSession(),
            fileId = "file-1",
            reference = reference,
            offset = 4,
            bytes = byteArrayOf(0, 127, -1, 32),
        )

        val encoded = json.encodeToString<ManagedExecutionEvent>(event)
        val decoded = json.decodeFromString<ManagedExecutionEvent>(encoded)
            as ManagedExecutionEvent.GeneratedFileChunk

        assertNull(decoded.reference.providerFileId)
        assertEquals(event.fileId, decoded.fileId)
        assertEquals(event.offset, decoded.offset)
        assertTrue(event.bytes.contentEquals(decoded.bytes))
    }

    @Test
    fun testBinaryFileChunkUsesContentEqualityHashingAndRedactedToString() {
        val first = ManagedExecutionEvent.GeneratedFileChunk(
            sequence = 4,
            executionId = "execution-1",
            session = bedrockSession(),
            fileId = "file-1",
            reference = ManagedExecutionFileReference.BedrockAgentCore(
                sessionId = "session-1",
                path = "/tmp/result.bin",
            ),
            offset = 128,
            bytes = byteArrayOf(11, 22, 33),
        )
        val equal = first.copy(bytes = byteArrayOf(11, 22, 33))
        val unequal = first.copy(bytes = byteArrayOf(11, 22, 34))

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotEquals(first, unequal)

        val rendered = first.toString()
        assertEquals(
            "GeneratedFileChunk(sequence=4, executionId=execution-1, fileId=file-1, offset=128, byteCount=3)",
            rendered,
        )
        assertFalse(rendered.contains("bytes="))
        assertFalse(rendered.contains("[11, 22, 33]"))
        assertFalse(rendered.contains("[B@"))
    }

    @Test
    fun testExecutionFlowIsColdOrderedAndBackpressured() = runTest {
        val service = FakeService()
        val session = service.acquireSession()
        val request = ManagedExecutionRequest("execution-1", "print('hello')")
        val flow = service.execute(session, request)

        assertEquals(0, service.collectionCount)

        val releaseFirstEvent = CompletableDeferred<Unit>()
        val received = mutableListOf<ManagedExecutionEvent>()
        val collection = backgroundScope.launch {
            flow.collect { event ->
                received += event
                if (received.size == 1) {
                    releaseFirstEvent.await()
                }
            }
        }

        runCurrent()
        assertEquals(1, service.collectionCount)
        assertEquals(listOf(0L), service.emittedSequences)

        releaseFirstEvent.complete(Unit)
        collection.join()

        assertEquals(listOf(0L, 1L, 2L), service.emittedSequences)
        assertEquals(listOf(0L, 1L, 2L), received.map { it.sequence })
    }

    private fun bedrockSession(): ManagedExecutionSessionReference.BedrockAgentCore =
        ManagedExecutionSessionReference.BedrockAgentCore(
            region = "eu-west-1",
            codeInterpreterIdentifier = "aws.codeinterpreter.v1",
            sessionId = "session-1",
            createdAtEpochMilliseconds = 1_700_000_000_000,
            timeoutSeconds = 900,
        )

    private inner class FakeService : ManagedExecutionService {
        var collectionCount: Int = 0
        val emittedSequences: MutableList<Long> = mutableListOf()

        override suspend fun acquireSession(
            existing: ManagedExecutionSessionReference?,
        ): ManagedExecutionSession = ManagedExecutionSession(
            reference = existing ?: bedrockSession(),
            ownership = if (existing == null) {
                ManagedExecutionSessionOwnership.OWNED
            } else {
                ManagedExecutionSessionOwnership.BORROWED
            },
        )

        override fun execute(
            session: ManagedExecutionSession,
            request: ManagedExecutionRequest,
        ): Flow<ManagedExecutionEvent> = flow {
            collectionCount += 1
            emitTracked(
                ManagedExecutionEvent.Request(
                    sequence = 0,
                    executionId = request.executionId,
                    session = session.reference,
                    code = request.code,
                    language = request.language,
                )
            )
            emitTracked(
                ManagedExecutionEvent.Stdout(
                    sequence = 1,
                    executionId = request.executionId,
                    session = session.reference,
                    text = "hello",
                )
            )
            emitTracked(
                ManagedExecutionEvent.Result(
                    sequence = 2,
                    executionId = request.executionId,
                    session = session.reference,
                    output = "hello",
                    exitCode = 0,
                )
            )
        }

        override suspend fun releaseSession(session: ManagedExecutionSession) = Unit

        private suspend fun kotlinx.coroutines.flow.FlowCollector<ManagedExecutionEvent>.emitTracked(
            event: ManagedExecutionEvent,
        ) {
            emittedSequences += event.sequence
            emit(event)
        }
    }
}
