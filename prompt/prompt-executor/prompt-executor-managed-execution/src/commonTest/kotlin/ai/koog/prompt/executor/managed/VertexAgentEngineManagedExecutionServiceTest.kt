@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package ai.koog.prompt.executor.managed

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.KoogHttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VertexAgentEngineManagedExecutionServiceTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun testCreateUsesExactV1RequestAndPollsToSandboxResource() = runTest {
        var now = 0L
        val fake = FakeKoogHttpClient { request ->
            when (request.index) {
                0 -> {
                    assertEquals("POST", request.method)
                    assertEquals("$BASE_URL/v1/$REASONING_ENGINE/sandboxEnvironments", request.path)
                    assertEquals("Bearer token-1", request.headers["Authorization"])
                    assertEquals("application/json", request.headers["Content-Type"])
                    assertEquals(
                        VertexCreateSandboxWire(
                            displayName = "koog-test",
                            ttl = "3600s",
                            spec = VertexSandboxSpecWire(
                                VertexCodeExecutionEnvironmentWire("LANGUAGE_PYTHON")
                            ),
                        ),
                        assertIs<VertexCreateSandboxWire>(request.body),
                    )
                    VertexOperationWire(name = OPERATION)
                }

                1 -> {
                    assertEquals("GET", request.method)
                    assertEquals("$BASE_URL/v1/$OPERATION", request.path)
                    assertEquals("Bearer token-2", request.headers["Authorization"])
                    completedCreate()
                }

                else -> error("Unexpected request")
            }
        }
        var tokenIndex = 0
        val service = service(
            fake = fake,
            tokenProvider = VertexAccessTokenProvider { "token-${++tokenIndex}" },
            now = { now },
            sleep = { now += it },
        )

        val session = service.acquireSession()

        assertEquals(ManagedExecutionSessionOwnership.OWNED, session.ownership)
        val reference = assertIs<ManagedExecutionSessionReference.VertexAgentEngine>(session.reference)
        assertEquals(SANDBOX, reference.sandboxResourceName)
        assertEquals(1_767_225_600_000, reference.expiresAtEpochMilliseconds)
        assertEquals(2, fake.requests.size)
    }

    @Test
    fun testCreateMapsLongRunningOperationError() = runTest {
        val fake = FakeKoogHttpClient {
            VertexOperationWire(
                name = OPERATION,
                done = true,
                error = VertexRpcStatusWire(code = 7, message = "secret provider detail"),
            )
        }

        val failure = assertFailsWith<VertexAgentEngineLifecycleException> {
            service(fake).acquireSession()
        }

        assertEquals(ManagedExecutionErrorKind.PROVIDER_FAILURE, failure.kind)
        assertEquals("RPC_7", failure.providerCode)
        assertFalse(failure.message.orEmpty().contains("secret"))
    }

    @Test
    fun testRpcNotFoundDuringCreateAndDeletePollingRemainsProviderFailure() = runTest {
        val createSentinel = "poll-create-rpc-not-found-sentinel"
        val createFailure = assertFailsWith<VertexAgentEngineLifecycleException> {
            service(
                FakeKoogHttpClient {
                    VertexOperationWire(
                        name = OPERATION,
                        done = true,
                        error = VertexRpcStatusWire(code = 5, message = createSentinel),
                    )
                }
            ).acquireSession()
        }
        assertEquals(ManagedExecutionErrorKind.PROVIDER_FAILURE, createFailure.kind)
        assertEquals("RPC_5", createFailure.providerCode)
        assertSanitisedThrowable(createFailure, createSentinel)

        val deleteSentinel = "poll-delete-rpc-not-found-sentinel"
        val deleteOperation = VertexOperationWire(
            name = OPERATION,
            done = true,
            error = VertexRpcStatusWire(code = 5, message = deleteSentinel),
        )
        val deleteFailure = assertFailsWith<VertexAgentEngineLifecycleException> {
            service(
                FakeKoogHttpClient {
                    KoogHttpResponse(
                        json.encodeToString(deleteOperation).encodeToByteArray(),
                        200,
                        emptyMap(),
                        null,
                    )
                }
            ).releaseSession(ownedSession())
        }
        assertEquals(ManagedExecutionErrorKind.PROVIDER_FAILURE, deleteFailure.kind)
        assertEquals("RPC_5", deleteFailure.providerCode)
        assertSanitisedThrowable(deleteFailure, deleteSentinel)
    }

    @Test
    fun testCreatePollingHasBoundedDeadline() = runTest {
        var now = 0L
        val fake = FakeKoogHttpClient {
            VertexOperationWire(name = OPERATION, done = false)
        }
        val configuration = configuration(
            operationPollIntervalMillis = 5,
            operationDeadlineMillis = 10,
        )
        val service = service(
            fake = fake,
            configuration = configuration,
            now = { now },
            sleep = { now += it },
        )

        val failure = assertFailsWith<VertexAgentEngineLifecycleException> {
            service.acquireSession()
        }

        assertEquals("OPERATION_DEADLINE_EXCEEDED", failure.providerCode)
        assertEquals(3, fake.requests.size)
    }

    @Test
    fun testBorrowedAcquireGetsOfficialResourceAndRetainsVerifiedLanguage() = runTest {
        val fake = FakeKoogHttpClient { request ->
            assertEquals("GET", request.method)
            assertEquals("$BASE_URL/v1/$SANDBOX", request.path)
            sandboxWire()
        }
        val reference = reference()

        val session = service(fake).acquireSession(reference)

        assertEquals(ManagedExecutionSessionOwnership.BORROWED, session.ownership)
        assertEquals(
            reference.copy(
                expiresAtEpochMilliseconds = 1_767_225_600_000,
                codeLanguage = VertexAgentEngineCodeLanguage.PYTHON,
            ),
            session.reference,
        )
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun testBorrowedAcquireRequiresAndVerifiesLanguageWhenProviderOmitsIt() = runTest {
        val omittedLanguage = sandboxWire().copy(spec = null)
        val matching = service(FakeKoogHttpClient { omittedLanguage }).acquireSession(reference())
        assertEquals(
            VertexAgentEngineCodeLanguage.PYTHON,
            assertIs<ManagedExecutionSessionReference.VertexAgentEngine>(matching.reference).codeLanguage,
        )

        val missingFake = FakeKoogHttpClient { omittedLanguage }
        val missing = assertFailsWith<VertexAgentEngineLifecycleException> {
            service(missingFake).acquireSession(reference().copy(codeLanguage = null))
        }
        assertEquals("MISSING_SANDBOX_LANGUAGE", missing.providerCode)
        assertEquals(1, missingFake.requests.size)

        val mismatchFake = FakeKoogHttpClient {
            sandboxWire(VertexAgentEngineCodeLanguage.JAVASCRIPT)
        }
        val mismatch = assertFailsWith<VertexAgentEngineLifecycleException> {
            service(mismatchFake).acquireSession(reference())
        }
        assertEquals("SANDBOX_LANGUAGE_MISMATCH", mismatch.providerCode)
        assertEquals(1, mismatchFake.requests.size)
    }

    @Test
    fun testExecuteMapsPythonAndJavaScriptCodeAndFilesToOfficialChunks() = runTest {
        VertexAgentEngineCodeLanguage.entries.forEach { language ->
            val fake = FakeKoogHttpClient { request ->
                val body = assertIs<VertexExecuteSandboxWire>(request.body)
                assertEquals("$BASE_URL/v1/$SANDBOX:execute", request.path)
                assertEquals("Bearer access-token", request.headers["Authorization"])
                assertEquals(2, body.inputs.size)
                val codeChunk = body.inputs[0]
                assertEquals("application/json", codeChunk.mimeType)
                assertEquals(
                    """{"code":"print(1)"}""",
                    Base64.decode(codeChunk.data!!).decodeToString(),
                )
                val fileChunk = body.inputs[1]
                assertEquals("text/plain", fileChunk.mimeType)
                assertContentEquals("input".encodeToByteArray(), Base64.decode(fileChunk.data!!))
                assertEquals(
                    "input.txt",
                    Base64.decode(fileChunk.metadata!!.attributes!!["file_name"]!!).decodeToString(),
                )
                validResponse()
            }
            val service = service(
                fake,
                configuration(codeLanguage = language),
            )
            val request = ManagedExecutionRequest(
                executionId = "exec-${language.requestName}",
                code = "print(1)",
                language = language.requestName,
                files = listOf(
                    ManagedExecutionInputFile(
                        filename = "input.txt",
                        mediaType = "text/plain",
                        bytes = "input".encodeToByteArray(),
                    )
                ),
            )

            val events = service.execute(
                ownedSession().copy(reference = reference().copy(codeLanguage = language)),
                request,
            ).toList()

            assertIs<ManagedExecutionEvent.Result>(events.last())
            assertEquals(1, fake.requests.size)
        }
    }

    @Test
    fun testUnsupportedLanguageAndInvalidReferenceFailBeforeNetwork() = runTest {
        val fake = FakeKoogHttpClient()
        val service = service(fake)
        val unsupported = service.execute(
            ownedSession(),
            ManagedExecutionRequest("unsupported", "println(1)", "kotlin"),
        ).toList()
        val crossProject = service.execute(
            ManagedExecutionSession(
                reference().copy(project = "other-project"),
                ManagedExecutionSessionOwnership.BORROWED,
            ),
            ManagedExecutionRequest("cross-project", "print(1)"),
        ).toList()
        val staleExpiry = service.execute(
            ManagedExecutionSession(
                reference().copy(expiresAtEpochMilliseconds = 0),
                ManagedExecutionSessionOwnership.BORROWED,
            ),
            ManagedExecutionRequest("expired", "print(1)"),
        ).toList()

        assertEquals("UNSUPPORTED_LANGUAGE", assertIs<ManagedExecutionEvent.Error>(unsupported.last()).providerCode)
        assertEquals(
            "INVALID_SESSION_REFERENCE",
            assertIs<ManagedExecutionEvent.Error>(crossProject.last()).providerCode,
        )
        assertIs<ManagedExecutionEvent.Error>(staleExpiry.last())
        assertEquals(1, fake.requests.size)
        assertFailsWith<IllegalArgumentException> {
            configuration(location = "europe-west1")
        }
    }

    @Test
    fun testSupportedLanguageMismatchFailsBeforeExecuteNetworkWork() = runTest {
        val fake = FakeKoogHttpClient()

        val events = service(fake).execute(
            ownedSession(),
            ManagedExecutionRequest(
                executionId = "supported-language-mismatch",
                code = "console.log(1)",
                language = "javascript",
            ),
        ).toList()

        assertEquals(2, events.size)
        assertIs<ManagedExecutionEvent.Request>(events.first())
        val failure = assertIs<ManagedExecutionEvent.Error>(events.last())
        assertEquals(ManagedExecutionErrorKind.INVALID_REQUEST, failure.kind)
        assertEquals("SANDBOX_LANGUAGE_MISMATCH", failure.providerCode)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun testExecuteUsesProviderTruthAcrossOriginalExpiryAfterSuccessfulTtlRefresh() = runTest {
        var now = 900L
        val fake = FakeKoogHttpClient { validResponse() }
        val service = service(fake, now = { now })
        val session = ownedSession().copy(
            reference = reference().copy(expiresAtEpochMilliseconds = 1_000L)
        )

        val first = service.execute(
            session,
            ManagedExecutionRequest("before-original-expiry", "print(1)"),
        ).toList()
        now = 1_100L
        val second = service.execute(
            session,
            ManagedExecutionRequest("after-original-expiry", "print(2)"),
        ).toList()

        assertIs<ManagedExecutionEvent.Result>(first.last())
        assertIs<ManagedExecutionEvent.Result>(second.last())
        assertEquals(2, fake.requests.size)
        assertEquals(listOf("$BASE_URL/v1/$SANDBOX:execute", "$BASE_URL/v1/$SANDBOX:execute"), fake.requests.map { it.path })
    }

    @Test
    fun testExecuteMapsOrderedStdoutStderrAndOneResultWithExactIdentities() = runTest {
        val response = VertexExecuteSandboxResponseWire(
            outputs = listOf(
                jsonChunk("""{"msg_out":"out-1","msg_err":"err-1"}"""),
                jsonChunk("""{"msg_out":"out-2","msg_err":""}"""),
            )
        )
        val fake = FakeKoogHttpClient { response }
        val session = ownedSession()

        val events = service(fake).execute(
            session,
            ManagedExecutionRequest("exec-ordered", "print(1)"),
        ).toList()

        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), events.map { it.sequence })
        assertTrue(events.all { it.executionId == "exec-ordered" && it.session == session.reference })
        assertEquals("out-1", assertIs<ManagedExecutionEvent.Stdout>(events[1]).text)
        assertEquals("err-1", assertIs<ManagedExecutionEvent.Stderr>(events[2]).text)
        assertEquals("out-2", assertIs<ManagedExecutionEvent.Stdout>(events[3]).text)
        val result = assertIs<ManagedExecutionEvent.Result>(events[4])
        assertEquals("out-1out-2", result.output)
        assertNull(result.exitCode)
        assertEquals(1, events.count { it is ManagedExecutionEvent.Terminal })
    }

    @Test
    fun testJsonAndRawFilesDeduplicateByFilenameAndDigestInProviderOrder() = runTest {
        val bytes = "same".encodeToByteArray()
        val jsonOutput = """
            {
              "msg_out":"",
              "msg_err":"",
              "output_files":[
                {"name":"same.txt","content":"${Base64.encode(bytes)}","mimeType":"text/plain"}
              ]
            }
        """.trimIndent()
        val response = VertexExecuteSandboxResponseWire(
            outputs = listOf(
                jsonChunk(jsonOutput),
                rawFileChunk("same.txt", "text/plain", bytes),
            )
        )

        val events = service(FakeKoogHttpClient { response }).execute(
            ownedSession(),
            ManagedExecutionRequest("exec-dedupe", "print(1)"),
        ).toList()

        val chunks = events.filterIsInstance<ManagedExecutionEvent.GeneratedFileChunk>()
        val completions = events.filterIsInstance<ManagedExecutionEvent.GeneratedFileComplete>()
        assertEquals(1, chunks.size)
        assertEquals(1, completions.size)
        assertContentEquals(bytes, chunks.single().bytes)
        assertEquals("exec-dedupe:vertex-output:0", chunks.single().fileId)
        assertNull(
            assertIs<ManagedExecutionFileReference.VertexAgentEngine>(
                completions.single().file.reference
            ).providerFileId
        )
        assertEquals(1, assertIs<ManagedExecutionEvent.Result>(events.last()).generatedFiles.size)
    }

    @Test
    fun testSameFilenameWithDifferentBytesRemainsDistinct() = runTest {
        val response = VertexExecuteSandboxResponseWire(
            outputs = listOf(
                rawFileChunk("same.bin", "application/octet-stream", byteArrayOf(1)),
                rawFileChunk("same.bin", "application/octet-stream", byteArrayOf(2)),
            )
        )

        val events = service(FakeKoogHttpClient { response }).execute(
            ownedSession(),
            ManagedExecutionRequest("exec-distinct", "print(1)"),
        ).toList()

        val chunks = events.filterIsInstance<ManagedExecutionEvent.GeneratedFileChunk>()
        assertEquals(2, chunks.size)
        assertContentEquals(byteArrayOf(1), chunks[0].bytes)
        assertContentEquals(byteArrayOf(2), chunks[1].bytes)
        assertEquals(
            listOf("exec-distinct:vertex-output:0", "exec-distinct:vertex-output:1"),
            chunks.map { it.fileId },
        )
    }

    @Test
    fun testMalformedBase64MetadataJsonAndMissingChunkBecomeProtocolErrors() = runTest {
        val responses = listOf(
            VertexExecuteSandboxResponseWire(
                listOf(VertexChunkWire(data = "***", mimeType = "application/json"))
            ),
            VertexExecuteSandboxResponseWire(
                listOf(
                    VertexChunkWire(
                        data = Base64.encode(byteArrayOf(1)),
                        mimeType = "text/plain",
                        metadata = VertexChunkMetadataWire(mapOf("file_name" to "***")),
                    )
                )
            ),
            VertexExecuteSandboxResponseWire(listOf(jsonChunk("{bad json"))),
            VertexExecuteSandboxResponseWire(listOf(VertexChunkWire(data = null, mimeType = "text/plain"))),
        )

        responses.forEachIndexed { index, response ->
            val events = service(FakeKoogHttpClient { response }).execute(
                ownedSession(),
                ManagedExecutionRequest("malformed-$index", "print(1)"),
            ).toList()
            assertEquals(
                ManagedExecutionErrorKind.PROTOCOL_FAILURE,
                assertIs<ManagedExecutionEvent.Error>(events.last()).kind,
            )
            assertEquals(1, events.count { it is ManagedExecutionEvent.Terminal })
        }
    }

    @Test
    fun testInputAndOutputLimitsFailWithoutOverflow() = runTest {
        val inputFake = FakeKoogHttpClient()
        val inputService = service(inputFake, maxPayloadBytes = 8)

        val inputEvents = inputService.execute(
            ownedSession(),
            ManagedExecutionRequest("too-large-input", "print(123456789)"),
        ).toList()

        assertEquals(
            "SIZE_LIMIT_EXCEEDED",
            assertIs<ManagedExecutionEvent.Error>(inputEvents.last()).providerCode,
        )
        assertEquals(
            ManagedExecutionErrorKind.INVALID_REQUEST,
            assertIs<ManagedExecutionEvent.Error>(inputEvents.last()).kind,
        )
        assertTrue(inputFake.requests.isEmpty())

        val unsafeInput = inputService.execute(
            ownedSession(),
            ManagedExecutionRequest(
                executionId = "unsafe-file",
                code = "x",
                files = listOf(
                    ManagedExecutionInputFile("../secret", "text/plain", byteArrayOf(1))
                ),
            ),
        ).toList()
        assertEquals(
            ManagedExecutionErrorKind.INVALID_REQUEST,
            assertIs<ManagedExecutionEvent.Error>(unsafeInput.last()).kind,
        )

        val outputResponse = VertexExecuteSandboxResponseWire(
            outputs = listOf(rawFileChunk("large.bin", "application/octet-stream", ByteArray(9)))
        )
        val outputEvents = service(
            FakeKoogHttpClient { outputResponse },
            maxPayloadBytes = 8,
        ).execute(
            borrowedSession(),
            ManagedExecutionRequest("too-large-output", "x", "python"),
        ).toList()
        assertEquals(
            "SIZE_LIMIT_EXCEEDED",
            assertIs<ManagedExecutionEvent.Error>(outputEvents.last()).providerCode,
        )
    }

    @Test
    fun testCancellationDuringPollPropagatesWithoutExtraRequest() = runTest {
        val fake = FakeKoogHttpClient { VertexOperationWire(name = OPERATION) }
        val service = service(fake, sleep = { awaitCancellation() })
        var cancellation: Throwable? = null
        val job = launch {
            try {
                service.acquireSession()
            } catch (failure: Throwable) {
                cancellation = failure
            }
        }
        runCurrent()

        job.cancelAndJoin()

        assertIs<CancellationException>(cancellation)
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun testCancellationDuringExecuteEmitsNoTerminalAndNeverDeletesBorrowedSession() = runTest {
        val fake = FakeKoogHttpClient { awaitCancellation() }
        val service = service(fake)
        val events = mutableListOf<ManagedExecutionEvent>()
        var cancellation: Throwable? = null
        val session = borrowedSession()
        val job = launch {
            try {
                service.execute(session, ManagedExecutionRequest("cancel-execute", "print(1)")).collect(events::add)
            } catch (failure: Throwable) {
                cancellation = failure
            }
        }
        runCurrent()

        job.cancelAndJoin()
        service.releaseSession(session)

        assertIs<CancellationException>(cancellation)
        assertEquals(1, events.size)
        assertIs<ManagedExecutionEvent.Request>(events.single())
        assertFalse(events.any { it is ManagedExecutionEvent.Terminal })
        assertEquals(listOf("POST"), fake.requests.map { it.method })
    }

    @Test
    fun testOwnedDeletePollsAndRepeatedReleaseIsLocalNoOpWhileBorrowedIsUntouched() = runTest {
        val deleteBody = json.encodeToString(VertexOperationWire(name = OPERATION)).encodeToByteArray()
        val fake = FakeKoogHttpClient { request ->
            when (request.method) {
                "DELETE" -> KoogHttpResponse(deleteBody, 200, emptyMap(), null)
                "GET" -> VertexOperationWire(name = OPERATION, done = true)
                else -> error("Unexpected request")
            }
        }
        val service = service(fake)

        service.releaseSession(ownedSession())
        service.releaseSession(ownedSession())
        service.releaseSession(borrowedSession())

        assertEquals(listOf("DELETE", "GET"), fake.requests.map { it.method })
        assertEquals("$BASE_URL/v1/$SANDBOX", fake.requests.first().path)
    }

    @Test
    fun testProviderDelete404RemainsTypedBecauseV1HasNoAllowMissing() = runTest {
        val fake = FakeKoogHttpClient {
            throw KoogHttpClientException(
                clientName = "fake",
                statusCode = 404,
                errorBody = """{"error":{"message":"gone"}}""",
            )
        }

        val failure = assertFailsWith<VertexAgentEngineLifecycleException> {
            service(fake).releaseSession(ownedSession())
        }

        assertEquals(ManagedExecutionErrorKind.SESSION_EXPIRED, failure.kind)
        assertEquals("HTTP_404", failure.providerCode)
        assertEquals("Vertex sandbox was not found during deletion", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun testNotFoundAndGoneMappingIsEndpointAndOperationAware() = runTest {
        listOf(404, 410).forEach { status ->
            suspend fun failureFor(
                expectedKind: ManagedExecutionErrorKind,
                operation: suspend (VertexAgentEngineManagedExecutionService) -> Unit,
                handler: suspend (CapturedVertexRequest) -> Any = {
                    throw httpFailure(status, "provider-$status-sentinel")
                },
            ) {
                val failure = assertFailsWith<VertexAgentEngineLifecycleException> {
                    operation(service(FakeKoogHttpClient(handler)))
                }
                assertEquals(expectedKind, failure.kind)
                assertEquals("HTTP_$status", failure.providerCode)
                assertEquals(status, failure.statusCode)
                assertNull(failure.cause)
                assertFalse(failure.toString().contains("provider-$status-sentinel"))
            }

            failureFor(ManagedExecutionErrorKind.PROVIDER_FAILURE, { it.acquireSession() })
            failureFor(
                ManagedExecutionErrorKind.PROVIDER_FAILURE,
                { it.acquireSession() },
                { request ->
                    if (request.method == "POST") {
                        VertexOperationWire(name = OPERATION)
                    } else {
                        throw httpFailure(status, "poll-create-$status-sentinel")
                    }
                },
            )
            failureFor(ManagedExecutionErrorKind.SESSION_EXPIRED, { it.getSandbox(reference()) })
            failureFor(ManagedExecutionErrorKind.PROVIDER_FAILURE, { it.listSandboxes() })
            failureFor(ManagedExecutionErrorKind.SESSION_EXPIRED, { it.releaseSession(ownedSession()) })
            failureFor(
                ManagedExecutionErrorKind.PROVIDER_FAILURE,
                { it.releaseSession(ownedSession()) },
                { request ->
                    if (request.method == "DELETE") {
                        KoogHttpResponse(
                            json.encodeToString(VertexOperationWire(name = OPERATION)).encodeToByteArray(),
                            200,
                            emptyMap(),
                            null,
                        )
                    } else {
                        throw httpFailure(status, "poll-delete-$status-sentinel")
                    }
                },
            )

            val execute = service(
                FakeKoogHttpClient { throw httpFailure(status, "execute-$status-sentinel") }
            ).execute(
                borrowedSession(),
                ManagedExecutionRequest("not-found-$status", "print(1)"),
            ).toList()
            val terminal = assertIs<ManagedExecutionEvent.Error>(execute.last())
            assertEquals(ManagedExecutionErrorKind.SESSION_EXPIRED, terminal.kind)
            assertEquals("HTTP_$status", terminal.providerCode)
            assertFalse(terminal.toString().contains("execute-$status-sentinel"))
        }
    }

    @Test
    fun testHeadersTokensAndSensitiveProviderBodiesAreRedactedFromTypedError() = runTest {
        val secretToken = "token-secret-123"
        val fake = FakeKoogHttpClient { request ->
            assertEquals("Bearer $secretToken", request.headers["Authorization"])
            throw KoogHttpClientException(
                clientName = "fake",
                statusCode = 500,
                errorBody = "code-secret stdout-secret file-secret $secretToken",
            )
        }

        val events = service(
            fake,
            tokenProvider = VertexAccessTokenProvider { secretToken },
        ).execute(
            borrowedSession(),
            ManagedExecutionRequest("redacted", "code-secret"),
        ).toList()

        val error = assertIs<ManagedExecutionEvent.Error>(events.last())
        val diagnostic = "${error.message} ${error.providerCode}"
        listOf(secretToken, "code-secret", "stdout-secret", "file-secret").forEach {
            assertFalse(diagnostic.contains(it))
        }
    }

    @Test
    fun testTopLevelDeserialisationFailureBecomesProtocolError() = runTest {
        val fake = FakeKoogHttpClient {
            throw SerializationException("malformed response with sensitive bytes")
        }

        val events = service(fake).execute(
            borrowedSession(),
            ManagedExecutionRequest("malformed-top-level", "print(1)"),
        ).toList()

        val error = assertIs<ManagedExecutionEvent.Error>(events.last())
        assertEquals(ManagedExecutionErrorKind.PROTOCOL_FAILURE, error.kind)
        assertEquals("MALFORMED_PROVIDER_RESPONSE", error.providerCode)
        assertFalse(error.message.contains("sensitive"))
    }

    @Test
    fun testProviderDerivedParsingFailuresNeverEscapeInDiagnosticsOrCauseChains() = runTest {
        val timestampSentinel = "timestamp-provider-sentinel"
        val timestampFailure = assertFailsWith<VertexAgentEngineLifecycleException> {
            service(
                FakeKoogHttpClient {
                    sandboxWire().copy(expireTime = timestampSentinel)
                }
            ).getSandbox(reference())
        }
        assertSanitisedThrowable(timestampFailure, timestampSentinel)

        val operationSentinel = "operation-provider-sentinel"
        val operationFailure = assertFailsWith<VertexAgentEngineLifecycleException> {
            service(
                FakeKoogHttpClient {
                    VertexOperationWire(
                        name = OPERATION,
                        done = true,
                        error = VertexRpcStatusWire(code = 7, message = operationSentinel),
                    )
                }
            ).acquireSession()
        }
        assertSanitisedThrowable(operationFailure, operationSentinel)

        val rawJsonSentinel = "raw-json-provider-sentinel"
        val rawJsonError = service(
            FakeKoogHttpClient {
                VertexExecuteSandboxResponseWire(listOf(jsonChunk("{$rawJsonSentinel")))
            }
        ).execute(
            ownedSession(),
            ManagedExecutionRequest("raw-json", "print(1)"),
        ).toList().last()
        assertSanitisedTerminal(assertIs<ManagedExecutionEvent.Error>(rawJsonError), rawJsonSentinel)

        val base64Sentinel = "base64-provider-sentinel"
        val base64Error = service(
            FakeKoogHttpClient {
                VertexExecuteSandboxResponseWire(
                    listOf(VertexChunkWire(data = "***$base64Sentinel", mimeType = "application/json"))
                )
            }
        ).execute(
            ownedSession(),
            ManagedExecutionRequest("base64", "print(1)"),
        ).toList().last()
        assertSanitisedTerminal(assertIs<ManagedExecutionEvent.Error>(base64Error), base64Sentinel)
    }

    @Test
    fun testInvalidProviderReturnedSandboxNamesAreRedactedAcrossEveryConversionRoute() = runTest {
        val returnedNames = listOf(
            "malformed-provider-sandbox-name-sentinel" to "malformed-provider-sandbox-name-sentinel",
            (
                "projects/cross-parent-provider-sentinel/locations/us-central1/reasoningEngines/engine-1/" +
                    "sandboxEnvironments/sandbox-1"
                ) to "cross-parent-provider-sentinel",
        )

        returnedNames.forEach { (returnedName, sentinel) ->
            suspend fun assertProtocolFailure(
                operation: suspend (VertexAgentEngineManagedExecutionService) -> Unit,
                handler: suspend (CapturedVertexRequest) -> Any,
            ) {
                val failure = assertFailsWith<VertexAgentEngineLifecycleException> {
                    operation(service(FakeKoogHttpClient(handler)))
                }
                assertEquals(ManagedExecutionErrorKind.PROTOCOL_FAILURE, failure.kind)
                assertEquals("MALFORMED_PROVIDER_RESPONSE", failure.providerCode)
                assertEquals("Vertex sandbox response name was invalid", failure.message)
                assertSanitisedThrowable(failure, sentinel)
                assertTrue(failure.suppressed.isEmpty())
            }

            assertProtocolFailure(
                operation = { it.acquireSession() },
                handler = {
                    completedCreate(sandboxWire().copy(name = returnedName))
                },
            )
            assertProtocolFailure(
                operation = { it.acquireSession(reference()) },
                handler = { sandboxWire().copy(name = returnedName) },
            )
            assertProtocolFailure(
                operation = { it.getSandbox(reference()) },
                handler = { sandboxWire().copy(name = returnedName) },
            )
            assertProtocolFailure(
                operation = { it.listSandboxes() },
                handler = {
                    VertexListSandboxesResponseWire(
                        sandboxEnvironments = listOf(sandboxWire().copy(name = returnedName))
                    )
                },
            )
        }
    }

    @Test
    fun testSha256StandardKnownVectors() {
        val vectors = listOf(
            byteArrayOf() to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "abc".encodeToByteArray() to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray() to
                "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            ByteArray(1_000_000) { 'a'.code.toByte() } to
                "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
        )

        vectors.forEach { (input, expected) ->
            assertEquals(expected, sha256Hex(input))
        }
    }

    @Test
    fun testExecuteFlowIsColdAndPerformsNoWorkBeforeCollection() = runTest {
        var tokenCalls = 0
        val fake = FakeKoogHttpClient { validResponse() }
        val service = service(
            fake,
            tokenProvider = VertexAccessTokenProvider {
                tokenCalls++
                "token"
            },
        )

        val flow = service.execute(ownedSession(), ManagedExecutionRequest("cold", "print(1)"))

        assertEquals(0, tokenCalls)
        assertTrue(fake.requests.isEmpty())
        val events = flow.toList()
        assertEquals(1, tokenCalls)
        assertIs<ManagedExecutionEvent.Request>(events.first())
        assertIs<ManagedExecutionEvent.Result>(events.last())
    }

    @Test
    fun testGetAndListUseOfficialV1PathsAndValidateReturnedResources() = runTest {
        val fake = FakeKoogHttpClient { request ->
            when (request.index) {
                0 -> sandboxWire()
                1 -> VertexListSandboxesResponseWire(
                    sandboxEnvironments = listOf(sandboxWire()),
                    nextPageToken = "next",
                )
                else -> error("Unexpected request")
            }
        }
        val service = service(fake)

        val sandbox = service.getSandbox(reference())
        val page = service.listSandboxes(pageSize = 25, pageToken = "page")

        assertEquals(SANDBOX, sandbox.name)
        assertEquals(listOf(SANDBOX), page.sandboxes.map { it.name })
        assertEquals("next", page.nextPageToken)
        assertEquals("$BASE_URL/v1/$SANDBOX", fake.requests[0].path)
        assertEquals("$BASE_URL/v1/$REASONING_ENGINE/sandboxEnvironments", fake.requests[1].path)
        assertEquals(mapOf("pageSize" to "25", "pageToken" to "page"), fake.requests[1].parameters)
    }

    private fun completedCreate(
        sandbox: VertexSandboxEnvironmentWire = sandboxWire(),
    ): VertexOperationWire = VertexOperationWire(
        name = OPERATION,
        done = true,
        response = json.encodeToJsonElement(sandbox).jsonObject,
    )

    private fun sandboxWire(
        codeLanguage: VertexAgentEngineCodeLanguage = VertexAgentEngineCodeLanguage.PYTHON,
    ): VertexSandboxEnvironmentWire = VertexSandboxEnvironmentWire(
        name = SANDBOX,
        displayName = "koog-test",
        state = "STATE_RUNNING",
        expireTime = "2026-01-01T00:00:00Z",
        spec = VertexSandboxSpecWire(
            VertexCodeExecutionEnvironmentWire(codeLanguage.wireName)
        ),
    )

    private fun httpFailure(statusCode: Int, sentinel: String): KoogHttpClientException =
        KoogHttpClientException(
            clientName = "fake",
            statusCode = statusCode,
            errorBody = """{"error":{"message":"$sentinel"}}""",
        )

    private fun assertSanitisedThrowable(failure: Throwable, sentinel: String) {
        fun assertRedacted(current: Throwable) {
            assertFalse(current.message.orEmpty().contains(sentinel))
            assertFalse(current.toString().contains(sentinel))
            current.suppressed.forEach(::assertRedacted)
            current.cause?.let(::assertRedacted)
        }
        assertRedacted(failure)
        assertNull(failure.cause)
    }

    private fun assertSanitisedTerminal(error: ManagedExecutionEvent.Error, sentinel: String) {
        assertFalse(error.message.contains(sentinel))
        assertFalse(error.toString().contains(sentinel))
    }

    private fun validResponse(): VertexExecuteSandboxResponseWire =
        VertexExecuteSandboxResponseWire(listOf(jsonChunk("""{"msg_out":"","msg_err":""}""")))

    private fun jsonChunk(value: String): VertexChunkWire = VertexChunkWire(
        data = Base64.encode(value.encodeToByteArray()),
        mimeType = "application/json",
    )

    private fun rawFileChunk(filename: String, mediaType: String, bytes: ByteArray): VertexChunkWire =
        VertexChunkWire(
            data = Base64.encode(bytes),
            mimeType = mediaType,
            metadata = VertexChunkMetadataWire(
                mapOf("file_name" to Base64.encode(filename.encodeToByteArray()))
            ),
        )

    private fun configuration(
        location: String = "us-central1",
        codeLanguage: VertexAgentEngineCodeLanguage = VertexAgentEngineCodeLanguage.PYTHON,
        operationPollIntervalMillis: Long = 1,
        operationDeadlineMillis: Long = 100,
    ): VertexAgentEngineConfiguration = VertexAgentEngineConfiguration(
        project = PROJECT,
        location = location,
        reasoningEngineResource = REASONING_ENGINE,
        baseUrl = BASE_URL,
        displayName = "koog-test",
        codeLanguage = codeLanguage,
        operationPollIntervalMillis = operationPollIntervalMillis,
        operationDeadlineMillis = operationDeadlineMillis,
    )

    private fun service(
        fake: FakeKoogHttpClient,
        configuration: VertexAgentEngineConfiguration = configuration(),
        tokenProvider: VertexAccessTokenProvider = VertexAccessTokenProvider { "access-token" },
        sleep: suspend (Long) -> Unit = {},
        now: () -> Long = { 0L },
        maxPayloadBytes: Long = VertexAgentEngineConfiguration.MAX_REQUEST_OR_RESPONSE_BYTES,
    ): VertexAgentEngineManagedExecutionService = VertexAgentEngineManagedExecutionService(
        httpClient = fake,
        accessTokenProvider = tokenProvider,
        configuration = configuration,
        sleep = sleep,
        nowEpochMilliseconds = now,
        maxPayloadBytes = maxPayloadBytes,
    )

    private fun reference(): ManagedExecutionSessionReference.VertexAgentEngine =
        ManagedExecutionSessionReference.VertexAgentEngine(
            project = PROJECT,
            location = "us-central1",
            reasoningEngineResource = REASONING_ENGINE,
            sandboxResourceName = SANDBOX,
            codeLanguage = VertexAgentEngineCodeLanguage.PYTHON,
        )

    private fun ownedSession(): ManagedExecutionSession =
        ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.OWNED)

    private fun borrowedSession(): ManagedExecutionSession =
        ManagedExecutionSession(reference(), ManagedExecutionSessionOwnership.BORROWED)

    private companion object {
        const val PROJECT = "project-1"
        const val REASONING_ENGINE =
            "projects/project-1/locations/us-central1/reasoningEngines/engine-1"
        const val SANDBOX =
            "$REASONING_ENGINE/sandboxEnvironments/sandbox-1"
        const val OPERATION =
            "projects/project-1/locations/us-central1/operations/operation-1"
        const val BASE_URL = "https://us-central1-aiplatform.test"
    }
}

private data class CapturedVertexRequest(
    val index: Int,
    val method: String,
    val path: String,
    val body: Any?,
    val parameters: Map<String, String>,
    val headers: Map<String, String>,
)

private class FakeKoogHttpClient(
    private val handler: suspend (CapturedVertexRequest) -> Any = {
        error("Unexpected ${it.method} request to ${it.path}")
    },
) : KoogHttpClient {
    override val clientName: String = "vertex-fake"
    val requests = mutableListOf<CapturedVertexRequest>()

    override suspend fun <R : Any> get(
        path: String,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = capture("GET", path, null, parameters, headers)

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = capture("POST", path, requestBody, parameters, headers)

    override suspend fun delete(
        path: String,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): KoogHttpResponse<ByteArray> = capture("DELETE", path, null, parameters, headers)

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> = flow { error("SSE is not used by the unary Vertex adapter") }

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> = flow { error("Line streaming is not used by the unary Vertex adapter") }

    override fun close() = Unit

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> capture(
        method: String,
        path: String,
        body: Any?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): T {
        val request = CapturedVertexRequest(
            index = requests.size,
            method = method,
            path = path,
            body = body,
            parameters = parameters,
            headers = headers,
        )
        requests += request
        return handler(request) as T
    }
}
