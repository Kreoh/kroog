@file:OptIn(DetachedPromptExecutorAPI::class, InternalAgentsApi::class)

package ai.koog.agents.core.environment

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.context.AIAgentContext
import ai.koog.agents.core.agent.context.AIAgentLLMContext
import ai.koog.agents.core.agent.context.AgentTestBase
import ai.koog.agents.core.agent.context.DetachedPromptExecutorAPI
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.agent.execution.AgentExecutionInfo
import ai.koog.agents.core.agent.tools.ManagedExecutionTool
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.feature.AIAgentFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.managed.ManagedExecutionErrorKind
import ai.koog.prompt.executor.managed.ManagedExecutionEvent
import ai.koog.prompt.executor.managed.ManagedExecutionFileReference
import ai.koog.prompt.executor.managed.ManagedExecutionSessionReference
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.typeToken
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.JsonPrimitive as KotlinxJsonPrimitive

class ManagedExecutionPipelinePrivacyTest : AgentTestBase() {
    private companion object {
        private const val REDACTED = "<managed-execution-data-redacted>"
        private const val CONTEXTUAL_LOGGER =
            "ai.koog.agents.core.environment.ContextualAgentEnvironment"
        private const val GENERIC_LOGGER =
            "ai.koog.agents.core.environment.ManagedExecutionPipelinePrivacyTest.Generic"
        private const val PIPELINE_LOGGER =
            "ai.koog.agents.core.environment.ManagedExecutionPipelinePrivacyTest.Pipeline"
        private const val ENVIRONMENT_PARENT_LOGGER = "ai.koog.agents.core.environment"
        private const val PROMPT_CALL_SECRET = "callback-prompt-managed-code-sentinel"
        private const val PROMPT_RESULT_SECRET = "callback-prompt-managed-result-sentinel"
        private const val PROMPT_HOSTED_SECRET = "callback-prompt-hosted-output-sentinel"
        private const val PROMPT_FILE_SECRET = "callback-prompt-generated-file-sentinel"
        private const val PROMPT_GRAPH_SECRET = "callback-deep-graph-sentinel"
        private const val PRIOR_CALL_ID = "prior-managed-call-id"
        private const val CONFLICTING_MANAGED_TOOL = "other_managed_execution"
        private val safeArgs = JSONObject(mapOf("data" to JSONPrimitive(REDACTED)))
        private val safeResult = JSONPrimitive(REDACTED)
        private val mutationStorageKey = createStorageKey<String>("managed-callback-mutation")
    }

    private val serializer = KotlinxSerializer()

    @Serializable
    private data class Args(val code: String)

    @Test
    fun testManagedSuccessRedactsEveryPipelineValueAndPreservesAgentResult() = runTest {
        val codeSecret = "pipeline-success-code-sentinel"
        val stdoutSecret = "pipeline-success-stdout-sentinel"
        val stderrSecret = "pipeline-success-stderr-sentinel"
        val fileBytesSecret = "pipeline-success-file-bytes-sentinel"
        val resultSecret = "pipeline-success-result-sentinel"
        val execution = executeManaged(
            tool = PrivacyManagedTool(events = {
                flowOf(
                    request(codeSecret),
                    ManagedExecutionEvent.Stdout(1, "execution-1", session(), stdoutSecret),
                    ManagedExecutionEvent.Stderr(2, "execution-1", session(), stderrSecret),
                    ManagedExecutionEvent.GeneratedFileChunk(
                        sequence = 3,
                        executionId = "execution-1",
                        session = session(),
                        fileId = "file-1",
                        reference = ManagedExecutionFileReference.VertexAgentEngine(
                            sandboxResourceName = "sandbox-1",
                            path = "/tmp/private.bin",
                        ),
                        offset = 0,
                        bytes = fileBytesSecret.encodeToByteArray(),
                    ),
                    result(sequence = 4, output = resultSecret),
                )
            }),
            args = """{"code":"$codeSecret"}""",
        )

        assertEquals(ToolResultKind.Success, execution.result.resultKind)
        assertEquals(resultSecret, execution.result.output)
        assertEquals(resultSecret, execution.result.resultObject)
        assertEquals(listOf("starting", "metadata", "completed"), execution.pipelineEvents.map { it.stage })
        assertManagedPipelineValuesRedacted(execution)
        assertSecretsAbsent(
            execution.logs,
            codeSecret,
            stdoutSecret,
            stderrSecret,
            fileBytesSecret,
            resultSecret,
        )
    }

    @Test
    fun testManagedRawJsonFailureUsesOnlySafeValidationPipelineValues() = runTest {
        val decodeSecret = "pipeline-raw-json-decode-sentinel"
        val execution = executeManaged(
            tool = PrivacyManagedTool(events = { error("Managed tool must not execute") }),
            args = """{"code":"$decodeSecret","broken":""",
        )

        assertIs<ToolResultKind.ValidationError>(execution.result.resultKind)
        assertEquals(listOf("validation"), execution.pipelineEvents.map { it.stage })
        assertManagedPipelineValuesRedacted(execution)
        assertSecretsAbsent(execution.logs, decodeSecret)
    }

    @Test
    fun testManagedTypedDecodeFailureRedactsStartingMetadataAndValidation() = runTest {
        val decodeSecret = "pipeline-typed-decode-sentinel"
        val execution = executeManaged(
            tool = PrivacyManagedTool(events = { error("Managed tool must not execute") }),
            args = """{"code":{"private":"$decodeSecret"}}""",
        )

        assertIs<ToolResultKind.Failure>(execution.result.resultKind)
        assertEquals(listOf("starting", "metadata", "failed"), execution.pipelineEvents.map { it.stage })
        assertManagedPipelineValuesRedacted(execution)
        assertSecretsAbsent(execution.logs, decodeSecret)
    }

    @Test
    fun testManagedExecutionFailureRedactsOriginalThrowableAndRenderedStack() = runTest {
        val codeSecret = "pipeline-execution-code-sentinel"
        val failureSecret = "pipeline-execution-failure-sentinel"
        val execution = executeManaged(
            tool = PrivacyManagedTool(events = {
                flow { throw IllegalStateException(failureSecret) }
            }),
            args = """{"code":"$codeSecret"}""",
        )

        assertIs<ToolResultKind.Failure>(execution.result.resultKind)
        assertEquals(listOf("starting", "metadata", "failed"), execution.pipelineEvents.map { it.stage })
        assertManagedPipelineValuesRedacted(execution)
        assertSecretsAbsent(execution.logs, codeSecret, failureSecret)
    }

    @Test
    fun testManagedProtocolFailureRedactsMalformedEventDetail() = runTest {
        val codeSecret = "pipeline-protocol-code-sentinel"
        val stdoutSecret = "pipeline-protocol-stdout-sentinel"
        val execution = executeManaged(
            tool = PrivacyManagedTool(
                events = {
                    flowOf(
                        request(codeSecret),
                        ManagedExecutionEvent.Stdout(0, "execution-1", session(), stdoutSecret),
                    )
                }
            ),
            args = """{"code":"$codeSecret"}""",
        )

        assertIs<ToolResultKind.Failure>(execution.result.resultKind)
        assertEquals(listOf("starting", "metadata", "failed"), execution.pipelineEvents.map { it.stage })
        assertManagedPipelineValuesRedacted(execution)
        assertSecretsAbsent(execution.logs, codeSecret, stdoutSecret)
    }

    @Test
    fun testManagedProviderFailureRedactsProviderDetailAndCode() = runTest {
        val codeSecret = "pipeline-provider-code-input-sentinel"
        val providerDetailSecret = "pipeline-provider-detail-sentinel"
        val providerCodeSecret = "pipeline-provider-code-sentinel"
        val execution = executeManaged(
            tool = PrivacyManagedTool(events = {
                flowOf(
                    request(codeSecret),
                    ManagedExecutionEvent.Error(
                        sequence = 1,
                        executionId = "execution-1",
                        session = session(),
                        kind = ManagedExecutionErrorKind.PROVIDER_FAILURE,
                        message = providerDetailSecret,
                        providerCode = providerCodeSecret,
                    ),
                )
            }),
            args = """{"code":"$codeSecret"}""",
        )

        assertIs<ToolResultKind.Failure>(execution.result.resultKind)
        assertEquals(listOf("starting", "metadata", "failed"), execution.pipelineEvents.map { it.stage })
        assertManagedPipelineValuesRedacted(execution)
        assertSecretsAbsent(execution.logs, codeSecret, providerDetailSecret, providerCodeSecret)
    }

    @Test
    fun testManagedResultEncodingFailureRedactsResultAndThrowableRendering() = runTest {
        val codeSecret = "pipeline-encoding-code-sentinel"
        val resultSecret = "pipeline-encoding-result-sentinel"
        val encodingFailureSecret = "pipeline-encoding-failure-sentinel"
        val execution = executeManaged(
            tool = PrivacyManagedTool(
                events = { flowOf(request(codeSecret), result(sequence = 1, output = resultSecret)) },
                encodeResultToStringBlock = { result, _ ->
                    throw IllegalStateException("$encodingFailureSecret: $result")
                },
            ),
            args = """{"code":"$codeSecret"}""",
        )

        assertIs<ToolResultKind.Failure>(execution.result.resultKind)
        assertEquals(listOf("starting", "metadata", "failed"), execution.pipelineEvents.map { it.stage })
        assertManagedPipelineValuesRedacted(execution)
        assertSecretsAbsent(execution.logs, codeSecret, resultSecret, encodingFailureSecret)
    }

    @Test
    fun testOrdinaryToolRetainsContextualPipelineAndGenericDiagnostics() = runTest {
        val argumentSecret = "ordinary-pipeline-argument-sentinel"
        val resultSecret = "ordinary-pipeline-result-sentinel"
        val execution = executeOrdinary(
            args = """{"code":"$argumentSecret"}""",
            result = resultSecret,
        )

        assertEquals(ToolResultKind.Success, execution.result.resultKind)
        assertEquals(resultSecret, execution.result.output)
        assertEquals(listOf("starting", "metadata", "completed"), execution.pipelineEvents.map { it.stage })
        assertTrue(execution.logs.contains(argumentSecret), execution.logs)
        assertTrue(execution.logs.contains(resultSecret), execution.logs)
        assertFalse(execution.logs.contains(REDACTED), execution.logs)
    }

    @Test
    fun testSharedProductionLoggerCaptureExcludesConcurrentUnrelatedRecordsAndPreservesPropagation() = runTest {
        val unrelatedCorrelationId = "unrelated-parallel-log-sentinel"
        val execution = executeManaged(
            tool = PrivacyManagedTool(events = {
                flowOf(request("parallel-code"), result(sequence = 1, output = "parallel-result"))
            }),
            args = """{"code":"parallel-code"}""",
            unrelatedCorrelationId = unrelatedCorrelationId,
        )

        assertFalse(execution.logs.contains(unrelatedCorrelationId), execution.logs)
        assertTrue(execution.propagatedLogs.contains(unrelatedCorrelationId), execution.propagatedLogs)
    }

    private suspend fun executeManaged(
        tool: PrivacyManagedTool,
        args: String,
        unrelatedCorrelationId: String? = null,
    ): CapturedExecution = execute(
        registry = ToolRegistry {
            tool(tool)
            tool(
                PrivacyManagedTool(
                    name = CONFLICTING_MANAGED_TOOL,
                    events = { error("Conflicting managed tool must not execute") },
                )
            )
        },
        call = MessagePart.Tool.Call(
            id = "managed-call-id",
            tool = "managed_execution",
            args = args,
        ),
        unrelatedCorrelationId = unrelatedCorrelationId,
    )

    private suspend fun executeOrdinary(
        args: String,
        result: String,
    ): CapturedExecution = execute(
        registry = ToolRegistry { tool(OrdinaryTool(result)) },
        call = MessagePart.Tool.Call(
            id = "ordinary-call-id",
            tool = "ordinary",
            args = args,
        ),
    )

    private suspend fun execute(
        registry: ToolRegistry,
        call: MessagePart.Tool.Call,
        unrelatedCorrelationId: String? = null,
    ): CapturedExecution {
        val genericLogger = KotlinLogging.logger(GENERIC_LOGGER)
        val generic = GenericAgentEnvironment(
            agentId = "agent-1",
            logger = genericLogger,
            toolRegistry = registry,
            serializer = serializer,
        )
        val templateContext = createTestLLMContext()
        val livePromptExecutor = LivePromptExecutor()
        val livePrompt = promptWithManagedHistory(templateContext.prompt, call)
        val liveConfig = templateContext.config.copy(prompt = livePrompt)
        val llmContext = AIAgentLLMContext(
            tools = registry.tools.map { it.descriptor },
            toolRegistry = registry,
            prompt = livePrompt,
            model = templateContext.model,
            responseProcessor = templateContext.responseProcessor,
            promptExecutor = livePromptExecutor,
            environment = generic,
            config = liveConfig,
            clock = templateContext.clock,
        )
        val context = createTestContext(
            environment = generic,
            config = liveConfig,
            llmContext = llmContext,
            runId = PROMPT_GRAPH_SECRET,
            strategyName = PROMPT_GRAPH_SECRET,
            pipeline = AIAgentGraphPipeline(liveConfig, templateContext.clock),
            agentInput = PROMPT_GRAPH_SECRET,
            executionInfo = AgentExecutionInfo(
                parent = AgentExecutionInfo(parent = null, partName = PROMPT_GRAPH_SECRET),
                partName = PROMPT_GRAPH_SECRET,
            ),
        )
        context.stateManager.withStateLock { state -> state.iterations = 17 }
        context.storage.set(mutationStorageKey, "live-storage-value")
        val pipelineEvents = installLoggingPipeline(context.pipeline, KotlinLogging.logger(PIPELINE_LOGGER))
        val contextual = ContextualAgentEnvironment(generic, context)

        JvmLogCapture(requireNotNull(call.id), CONTEXTUAL_LOGGER, GENERIC_LOGGER, PIPELINE_LOGGER).use { logs ->
            if (unrelatedCorrelationId == null) {
                val result = contextual.executeTool(call)
                return CapturedExecution(
                    result = result,
                    pipelineEvents = pipelineEvents,
                    logs = logs.rendered(),
                    liveContext = context,
                    livePromptBefore = livePrompt,
                    livePromptAfter = context.llm.prompt,
                    livePromptExecutor = livePromptExecutor,
                )
            }

            JvmLogCapture(unrelatedCorrelationId, ENVIRONMENT_PARENT_LOGGER).use { propagated ->
                val result = coroutineScope {
                    val execution = async { contextual.executeTool(call) }
                    val unrelated = launch {
                        KotlinLogging.logger(CONTEXTUAL_LOGGER).error {
                            "Unrelated concurrent record id=$unrelatedCorrelationId"
                        }
                    }
                    unrelated.join()
                    execution.await()
                }
                return CapturedExecution(
                    result = result,
                    pipelineEvents = pipelineEvents,
                    logs = logs.rendered(),
                    liveContext = context,
                    livePromptBefore = livePrompt,
                    livePromptAfter = context.llm.prompt,
                    livePromptExecutor = livePromptExecutor,
                    propagatedLogs = propagated.rendered(),
                )
            }
        }
    }

    private fun promptWithManagedHistory(base: Prompt, currentCall: MessagePart.Tool.Call): Prompt = Prompt(
        id = "live-managed-prompt",
        params = LLMParams(
            temperature = 1.5,
            maxTokens = 987,
            numberOfChoices = 2,
            speculation = PROMPT_GRAPH_SECRET,
            schema = LLMParams.Schema.JSON.Standard(
                name = PROMPT_GRAPH_SECRET,
                schema = JsonObject(mapOf(PROMPT_GRAPH_SECRET to KotlinxJsonPrimitive(PROMPT_GRAPH_SECRET))),
            ),
            toolChoice = LLMParams.ToolChoice.Named(PROMPT_GRAPH_SECRET),
            user = PROMPT_GRAPH_SECRET,
            additionalProperties = mapOf(
                PROMPT_GRAPH_SECRET to KotlinxJsonPrimitive(PROMPT_GRAPH_SECRET)
            ),
        ),
        messages = listOf(
            Message.System(
                content = PROMPT_GRAPH_SECRET,
                metaInfo = requestMetaInfo(),
                id = PROMPT_GRAPH_SECRET,
            ),
            Message.User(
                parts = listOf(
                    MessagePart.Text(
                        text = PROMPT_GRAPH_SECRET,
                        providerItemId = PROMPT_GRAPH_SECRET,
                        generatedFileCitations = listOf(
                            MessagePart.GeneratedFileCitation(
                                providerFileId = PROMPT_GRAPH_SECRET,
                                containerId = PROMPT_GRAPH_SECRET,
                                filename = PROMPT_GRAPH_SECRET,
                                mediaType = PROMPT_GRAPH_SECRET,
                                sizeBytes = 37,
                                producingExecutionId = PROMPT_GRAPH_SECRET,
                                providerItemId = PROMPT_GRAPH_SECRET,
                                startIndex = 1,
                                endIndex = 2,
                            )
                        ),
                    ),
                    attachment(AttachmentSource.Image::class.simpleName.orEmpty()),
                    attachment(AttachmentSource.Audio::class.simpleName.orEmpty()),
                    attachment(AttachmentSource.Video::class.simpleName.orEmpty()),
                    MessagePart.Attachment(
                        AttachmentSource.File(
                            content = AttachmentContent.PlainText(PROMPT_GRAPH_SECRET),
                            format = PROMPT_GRAPH_SECRET,
                            mimeType = PROMPT_GRAPH_SECRET,
                            fileName = PROMPT_GRAPH_SECRET,
                        )
                    ),
                ),
                metaInfo = requestMetaInfo(),
                id = PROMPT_GRAPH_SECRET,
            ),
            Message.Assistant(
                parts = listOf(
                    MessagePart.Text(
                        text = PROMPT_GRAPH_SECRET,
                        providerItemId = PROMPT_GRAPH_SECRET,
                        generatedFileCitations = listOf(
                            MessagePart.GeneratedFileCitation(
                                providerFileId = PROMPT_GRAPH_SECRET,
                                containerId = PROMPT_GRAPH_SECRET,
                                filename = PROMPT_GRAPH_SECRET,
                                mediaType = PROMPT_GRAPH_SECRET,
                                sizeBytes = 37,
                                producingExecutionId = PROMPT_GRAPH_SECRET,
                                providerItemId = PROMPT_GRAPH_SECRET,
                            )
                        ),
                    ),
                    MessagePart.Attachment(
                        AttachmentSource.Image(
                            content = AttachmentContent.Binary.Bytes(PROMPT_GRAPH_SECRET.encodeToByteArray()),
                            format = PROMPT_GRAPH_SECRET,
                            mimeType = PROMPT_GRAPH_SECRET,
                            fileName = PROMPT_GRAPH_SECRET,
                        )
                    ),
                    MessagePart.Reasoning(
                        content = listOf(PROMPT_GRAPH_SECRET),
                        summary = listOf(PROMPT_GRAPH_SECRET),
                        encrypted = PROMPT_GRAPH_SECRET,
                        id = PROMPT_GRAPH_SECRET,
                        providerItemId = PROMPT_GRAPH_SECRET,
                        replay = listOf(
                            MessagePart.ReasoningReplay.Signed(
                                text = PROMPT_GRAPH_SECRET,
                                signature = PROMPT_GRAPH_SECRET,
                            ),
                            MessagePart.ReasoningReplay.OpaqueRedacted(PROMPT_GRAPH_SECRET),
                        ),
                    ),
                    MessagePart.Tool.Call(
                        id = PRIOR_CALL_ID,
                        tool = "managed_execution",
                        args = """{"code":"$PROMPT_CALL_SECRET"}""",
                        providerItemId = PROMPT_GRAPH_SECRET,
                    ),
                    MessagePart.Tool.Call(
                        id = PROMPT_GRAPH_SECRET,
                        tool = PROMPT_GRAPH_SECRET,
                        args = """{"private":"$PROMPT_GRAPH_SECRET"}""",
                        providerItemId = PROMPT_GRAPH_SECRET,
                    ),
                    MessagePart.HostedExecution.Request(
                        code = PROMPT_CALL_SECRET,
                        language = PROMPT_GRAPH_SECRET,
                        executionId = "prior-execution",
                        containerId = PROMPT_GRAPH_SECRET,
                        providerItemId = PROMPT_GRAPH_SECRET,
                    ),
                    MessagePart.HostedExecution.Progress(
                        message = PROMPT_GRAPH_SECRET,
                        sequence = 1,
                        executionId = PROMPT_GRAPH_SECRET,
                        containerId = PROMPT_GRAPH_SECRET,
                        providerItemId = PROMPT_GRAPH_SECRET,
                    ),
                    MessagePart.HostedExecution.CumulativeOutput(
                        output = PROMPT_GRAPH_SECRET,
                        sequence = 2,
                        executionId = PROMPT_GRAPH_SECRET,
                        containerId = PROMPT_GRAPH_SECRET,
                        providerItemId = PROMPT_GRAPH_SECRET,
                    ),
                    MessagePart.HostedExecution.Result(
                        output = PROMPT_HOSTED_SECRET,
                        exitCode = 0,
                        executionId = "prior-execution",
                        containerId = PROMPT_GRAPH_SECRET,
                        providerItemId = PROMPT_GRAPH_SECRET,
                        generatedFiles = listOf(
                            MessagePart.GeneratedFile(
                                providerFileId = PROMPT_GRAPH_SECRET,
                                containerId = PROMPT_GRAPH_SECRET,
                                filename = PROMPT_FILE_SECRET,
                                mediaType = PROMPT_GRAPH_SECRET,
                                sizeBytes = 37,
                                producingExecutionId = "prior-execution",
                                providerItemId = PROMPT_GRAPH_SECRET,
                            )
                        ),
                    ),
                    MessagePart.HostedExecution.Error(
                        message = PROMPT_GRAPH_SECRET,
                        code = PROMPT_GRAPH_SECRET,
                        executionId = PROMPT_GRAPH_SECRET,
                        containerId = PROMPT_GRAPH_SECRET,
                        providerItemId = PROMPT_GRAPH_SECRET,
                    ),
                    MessagePart.CodeExecution(
                        id = PROMPT_GRAPH_SECRET,
                        code = PROMPT_CALL_SECRET,
                        containerId = PROMPT_GRAPH_SECRET,
                        outputs = listOf(
                            MessagePart.CodeExecution.Output.Logs(PROMPT_HOSTED_SECRET),
                            MessagePart.CodeExecution.Output.Image(PROMPT_GRAPH_SECRET),
                        ),
                        providerItemId = PROMPT_GRAPH_SECRET,
                    ),
                    MessagePart.GeneratedFile(
                        providerFileId = PROMPT_GRAPH_SECRET,
                        containerId = PROMPT_GRAPH_SECRET,
                        filename = PROMPT_FILE_SECRET,
                        mediaType = PROMPT_GRAPH_SECRET,
                        sizeBytes = 37,
                        producingExecutionId = "prior-execution",
                        providerItemId = PROMPT_GRAPH_SECRET,
                    ),
                ),
                metaInfo = responseMetaInfo(),
                finishReason = PROMPT_GRAPH_SECRET,
                rawResponse = JsonObject(mapOf(PROMPT_GRAPH_SECRET to KotlinxJsonPrimitive(PROMPT_GRAPH_SECRET))),
                id = PROMPT_GRAPH_SECRET,
            ),
            Message.User(
                parts = listOf(
                    MessagePart.Tool.Result(
                        id = PRIOR_CALL_ID,
                        tool = CONFLICTING_MANAGED_TOOL,
                        parts = listOf(
                            MessagePart.Text(PROMPT_RESULT_SECRET),
                            attachment(AttachmentSource.Image::class.simpleName.orEmpty()),
                        ),
                        providerItemId = PROMPT_GRAPH_SECRET,
                    ),
                    MessagePart.Tool.Result(
                        id = null,
                        tool = "managed_execution",
                        output = PROMPT_GRAPH_SECRET,
                        providerItemId = PROMPT_GRAPH_SECRET,
                    ),
                    MessagePart.Tool.Result(
                        id = PROMPT_GRAPH_SECRET,
                        tool = PROMPT_GRAPH_SECRET,
                        output = PROMPT_GRAPH_SECRET,
                        providerItemId = PROMPT_GRAPH_SECRET,
                    ),
                ),
                metaInfo = requestMetaInfo(),
                id = PROMPT_GRAPH_SECRET,
            ),
            Message.Assistant(
                part = currentCall,
                metaInfo = responseMetaInfo(),
                finishReason = PROMPT_GRAPH_SECRET,
                rawResponse = JsonObject(mapOf(PROMPT_GRAPH_SECRET to KotlinxJsonPrimitive(PROMPT_GRAPH_SECRET))),
                id = PROMPT_GRAPH_SECRET,
            ),
        ),
    )

    private fun attachment(kind: String): MessagePart.Attachment {
        val source = when (kind) {
            AttachmentSource.Audio::class.simpleName -> AttachmentSource.Audio(
                content = AttachmentContent.Binary.Base64("c2VjcmV0"),
                format = PROMPT_GRAPH_SECRET,
                mimeType = PROMPT_GRAPH_SECRET,
                fileName = PROMPT_GRAPH_SECRET,
            )

            AttachmentSource.Video::class.simpleName -> AttachmentSource.Video(
                content = AttachmentContent.URL(PROMPT_GRAPH_SECRET),
                format = PROMPT_GRAPH_SECRET,
                mimeType = PROMPT_GRAPH_SECRET,
                fileName = PROMPT_GRAPH_SECRET,
            )

            else -> AttachmentSource.Image(
                content = AttachmentContent.Binary.Bytes(PROMPT_GRAPH_SECRET.encodeToByteArray()),
                format = PROMPT_GRAPH_SECRET,
                mimeType = PROMPT_GRAPH_SECRET,
                fileName = PROMPT_GRAPH_SECRET,
            )
        }
        return MessagePart.Attachment(source)
    }

    private fun requestMetaInfo(): RequestMetaInfo = RequestMetaInfo(
        timestamp = Instant.parse("2025-01-01T00:00:00Z"),
        metadata = JsonObject(mapOf(PROMPT_GRAPH_SECRET to KotlinxJsonPrimitive(PROMPT_GRAPH_SECRET))),
    )

    private fun responseMetaInfo(): ResponseMetaInfo = ResponseMetaInfo(
        timestamp = Instant.parse("2025-01-01T00:00:00Z"),
        totalTokensCount = 99,
        inputTokensCount = 44,
        outputTokensCount = 55,
        modelId = PROMPT_GRAPH_SECRET,
        metadata = JsonObject(mapOf(PROMPT_GRAPH_SECRET to KotlinxJsonPrimitive(PROMPT_GRAPH_SECRET))),
    )

    private fun installLoggingPipeline(
        pipeline: ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline,
        logger: KLogger,
    ): MutableList<PipelineEvent> {
        val events = mutableListOf<PipelineEvent>()

        pipeline.interceptToolCallStarting(PipelineLoggingFeature) { event ->
            events += event.toPipelineEvent("starting")
            logger.info {
                "starting id=${event.toolCallId} tool=${event.toolName} description=${event.toolDescription} " +
                    "args=${event.toolArgs}"
            }
            event.context.llm.prompt = event.context.llm.prompt.copy(id = "callback-mutated-starting")
        }
        pipeline.provideToolCallMetadata(PipelineLoggingFeature) { event ->
            events += event.toPipelineEvent("metadata")
            logger.info {
                "metadata id=${event.toolCallId} tool=${event.toolName} description=${event.toolDescription} " +
                    "args=${event.toolArgs}"
            }
            event.context.llm.prompt = event.context.llm.prompt.copy(id = "callback-mutated-metadata")
            emptyMap()
        }
        pipeline.interceptToolValidationFailed(PipelineLoggingFeature) { event ->
            events += PipelineEvent(
                stage = "validation",
                runId = event.runId,
                executionInfo = event.executionInfo,
                toolCallId = event.toolCallId,
                toolName = event.toolName,
                toolDescription = event.toolDescription,
                toolArgs = event.toolArgs,
                message = event.message,
                error = event.error,
                callbackContext = event.context,
                callbackPrompt = event.context.llm.prompt,
            )
            logger.error(event.error) {
                "validation id=${event.toolCallId} tool=${event.toolName} description=${event.toolDescription} " +
                    "args=${event.toolArgs} message=${event.message}"
            }
            event.context.llm.prompt = event.context.llm.prompt.copy(id = "callback-mutated-validation")
        }
        pipeline.interceptToolCallFailed(PipelineLoggingFeature) { event ->
            events += PipelineEvent(
                stage = "failed",
                runId = event.runId,
                executionInfo = event.executionInfo,
                toolCallId = event.toolCallId,
                toolName = event.toolName,
                toolDescription = event.toolDescription,
                toolArgs = event.toolArgs,
                message = event.message,
                error = event.error,
                callbackContext = event.context,
                callbackPrompt = event.context.llm.prompt,
            )
            if (event.error == null) {
                logger.error {
                    "failed id=${event.toolCallId} tool=${event.toolName} description=${event.toolDescription} " +
                        "args=${event.toolArgs} message=${event.message}"
                }
            } else {
                logger.error(event.error) {
                    "failed id=${event.toolCallId} tool=${event.toolName} description=${event.toolDescription} " +
                        "args=${event.toolArgs} message=${event.message}"
                }
            }
            event.context.llm.prompt = event.context.llm.prompt.copy(id = "callback-mutated-failed")
        }
        pipeline.interceptToolCallCompleted(PipelineLoggingFeature) { event ->
            events += PipelineEvent(
                stage = "completed",
                runId = event.runId,
                executionInfo = event.executionInfo,
                toolCallId = event.toolCallId,
                toolName = event.toolName,
                toolDescription = event.toolDescription,
                toolArgs = event.toolArgs,
                result = event.toolResult,
                callbackContext = event.context,
                callbackPrompt = event.context.llm.prompt,
            )
            logger.info {
                "completed id=${event.toolCallId} tool=${event.toolName} description=${event.toolDescription} " +
                    "args=${event.toolArgs} result=${event.toolResult}"
            }
            event.context.llm.prompt = event.context.llm.prompt.copy(id = "callback-mutated-completed")
        }

        return events
    }

    private fun ai.koog.agents.core.feature.handler.tool.ToolCallStartingContext.toPipelineEvent(
        stage: String,
    ): PipelineEvent = PipelineEvent(
        stage = stage,
        runId = runId,
        executionInfo = executionInfo,
        toolCallId = toolCallId,
        toolName = toolName,
        toolDescription = toolDescription,
        toolArgs = toolArgs,
        callbackContext = context,
        callbackPrompt = context.llm.prompt,
    )

    private suspend fun assertManagedPipelineValuesRedacted(execution: CapturedExecution) {
        assertTrue(execution.logs.contains(REDACTED), execution.logs)
        assertTrue(execution.logs.contains("managed_execution"), execution.logs)
        assertTrue(execution.logs.contains("managed-call-id"), execution.logs)
        execution.pipelineEvents.forEach { event ->
            assertEquals("managed-call-id", event.toolCallId)
            assertEquals("managed_execution", event.toolName)
            assertEquals(REDACTED, event.toolDescription)
            assertEquals(safeArgs, event.toolArgs)
            event.message?.let { assertEquals(REDACTED, it) }
            event.result?.let { assertEquals(safeResult, it) }
            event.error?.let {
                assertEquals(REDACTED, it.message)
                assertNull(it.cause)
            }
            assertEquals(REDACTED, event.runId)
            assertEquals(event.callbackContext.runId, event.runId)
            assertTrue(event.executionInfo === event.callbackContext.executionInfo)
            assertFalse(event.runId.contains(PROMPT_GRAPH_SECRET))
            assertDetachedExecutionAncestry(event.executionInfo, execution.liveContext.executionInfo)
            assertTrue(event.callbackContext !== execution.liveContext)
            assertTrue(event.callbackContext.llm !== execution.liveContext.llm)
            assertTrue(event.callbackContext.config !== execution.liveContext.config)
            assertTrue(event.callbackContext.config.prompt !== execution.liveContext.config.prompt)
            assertTrue(event.callbackContext.config.serializer !== execution.liveContext.config.serializer)
            assertTrue(event.callbackContext.storage !== execution.liveContext.storage)
            assertTrue(event.callbackContext.stateManager !== execution.liveContext.stateManager)
            assertTrue(event.callbackContext.pipeline !== execution.liveContext.pipeline)
            assertTrue(event.callbackContext.environment !== execution.liveContext.environment)
            assertTrue(event.callbackContext.executionInfo !== execution.liveContext.executionInfo)
            assertTrue(event.callbackContext.llm.toolRegistry !== execution.liveContext.llm.toolRegistry)
            assertTrue(event.callbackContext.llm.tools !== execution.liveContext.llm.tools)
            assertTrue(event.callbackContext.llm.model !== execution.liveContext.llm.model)
            assertTrue(event.callbackContext.llm.model.provider !== execution.liveContext.llm.model.provider)
            assertTrue(event.callbackContext.llm.promptExecutor !== execution.liveContext.llm.promptExecutor)
            assertTrue(event.callbackContext.llm.clock !== execution.liveContext.llm.clock)
            assertTrue(event.callbackContext.llm.config === event.callbackContext.config)
            assertTrue(event.callbackContext.config.prompt !== execution.livePromptBefore)
            assertTrue(event.callbackPrompt !== execution.livePromptBefore)
            assertTrue(event.callbackPrompt.messages !== execution.livePromptBefore.messages)
            assertTrue(event.callbackPrompt.params !== execution.livePromptBefore.params)
            event.callbackPrompt.messages.zip(execution.livePromptBefore.messages).forEach { (safe, live) ->
                assertTrue(safe !== live)
                assertTrue(safe.parts !== live.parts)
                assertTrue(safe.metaInfo !== live.metaInfo)
                safe.parts.zip(live.parts).forEach { (safePart, livePart) ->
                    assertTrue(safePart !== livePart)
                    assertNestedPartDetached(safePart, livePart)
                }
            }
            assertNull(event.callbackContext.parentContext)
            assertEquals(REDACTED, event.callbackContext.agentInput)
            assertManagedCallbackPrompt(event.callbackPrompt)
            assertDetachedCollectionsCannotAffectLive(event.callbackPrompt, execution.livePromptAfter)
            assertDetachedToolsCannotAffectLive(event.callbackContext, execution.liveContext)
            assertDetachedInfrastructureCannotAffectLive(event.callbackContext, execution.liveContext)
            assertDisabledServices(event.callbackContext)
            assertEquals(0, execution.livePromptExecutor.calls)
        }
        assertEquals(execution.livePromptBefore, execution.livePromptAfter)
        assertEquals("live-managed-prompt", execution.livePromptAfter.id)
        assertEquals(17, execution.liveContext.stateManager.withStateLock { it.iterations })
        assertEquals("live-storage-value", execution.liveContext.storage.get(mutationStorageKey))
        assertNull(
            execution.liveContext.pipeline.feature(
                Unit::class,
                DetachedMutationFeature,
            )
        )
        assertNull(execution.liveContext.llm.toolRegistry.getToolOrNull("safe-detached-test-tool"))
    }

    private fun assertManagedCallbackPrompt(prompt: Prompt) {
        val rendered = prompt.toString()
        assertSecretsAbsent(
            rendered,
            PROMPT_CALL_SECRET,
            PROMPT_RESULT_SECRET,
            PROMPT_HOSTED_SECRET,
            PROMPT_FILE_SECRET,
            PROMPT_GRAPH_SECRET,
        )
        assertTrue(rendered.contains(REDACTED), rendered)
        assertTrue(prompt.id == REDACTED || prompt.id.startsWith("callback-mutated-"))
        assertEquals(LLMParams(), prompt.params)
        assertEquals(
            listOf(
                Message.Role.System,
                Message.Role.User,
                Message.Role.Assistant,
                Message.Role.User,
                Message.Role.Assistant,
            ),
            prompt.messages.map { it.role },
        )
        val parts = prompt.messages.flatMap(Message::parts)
        val calls = parts.filterIsInstance<MessagePart.Tool.Call>()
        assertEquals(listOf(PRIOR_CALL_ID, "managed-call-id"), calls.map { it.id })
        assertTrue(calls.all { it.tool == "managed_execution" })
        assertTrue(calls.all { it.args.contains(REDACTED) })
        val result = parts.filterIsInstance<MessagePart.Tool.Result>().single { it.id == PRIOR_CALL_ID }
        assertEquals(PRIOR_CALL_ID, result.id)
        assertEquals("managed_execution", result.tool)
        assertTrue(result.parts.all { it is MessagePart.Text && it.text == REDACTED })
        prompt.messages.forEach { message ->
            assertEquals(Instant.DISTANT_PAST, message.metaInfo.timestamp)
            assertNull(message.metaInfo.metadata)
            if (message is Message.Assistant) {
                assertNull(message.finishReason)
                assertNull(message.rawResponse)
                assertNull(message.metaInfo.totalTokensCount)
                assertNull(message.metaInfo.inputTokensCount)
                assertNull(message.metaInfo.outputTokensCount)
                assertNull(message.metaInfo.modelId)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertDetachedCollectionsCannotAffectLive(
        callbackPrompt: Prompt,
        livePrompt: Prompt,
    ) {
        val liveSnapshot = livePrompt.toString()
        attemptMutation(callbackPrompt.messages)
        callbackPrompt.messages.forEach { message ->
            attemptMutation(message.parts)
            message.parts.forEach { part ->
                when (part) {
                    is MessagePart.Text -> attemptMutation(part.generatedFileCitations)
                    is MessagePart.Attachment -> Unit
                    is MessagePart.Reasoning -> {
                        attemptMutation(part.content)
                        part.summary?.let(::attemptMutation)
                        attemptMutation(part.replay)
                    }

                    is MessagePart.GeneratedFile -> Unit
                    is MessagePart.HostedExecution.Request -> Unit
                    is MessagePart.HostedExecution.Progress -> Unit
                    is MessagePart.HostedExecution.CumulativeOutput -> Unit
                    is MessagePart.HostedExecution.Result -> attemptMutation(part.generatedFiles)
                    is MessagePart.HostedExecution.Error -> Unit
                    is MessagePart.CodeExecution -> attemptMutation(part.outputs)
                    is MessagePart.Tool.Call -> Unit
                    is MessagePart.Tool.Result -> {
                        attemptMutation(part.parts)
                        part.parts.forEach { contentPart ->
                            if (contentPart is MessagePart.Text) {
                                attemptMutation(contentPart.generatedFileCitations)
                            }
                        }
                    }
                }
            }
        }
        assertEquals(liveSnapshot, livePrompt.toString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertDetachedToolsCannotAffectLive(
        callbackContext: AIAgentContext,
        liveContext: AIAgentContext,
    ) {
        val liveTools = liveContext.llm.tools.toList()
        try {
            (callbackContext.llm.tools as MutableList<ToolDescriptor>).add(
                ToolDescriptor(name = "safe-detached-test-tool", description = REDACTED)
            )
        } catch (_: UnsupportedOperationException) {
            // The deliberately empty detached tool list is immutable.
        } catch (_: ClassCastException) {
            // Kotlin's immutable empty-list implementation is not a MutableList.
        }
        assertEquals(liveTools, liveContext.llm.tools)
    }

    private suspend fun assertDetachedInfrastructureCannotAffectLive(
        callbackContext: AIAgentContext,
        liveContext: AIAgentContext,
    ) {
        val liveConfigPrompt = liveContext.config.prompt.toString()
        val liveParams = liveContext.config.prompt.params.toString()

        assertDetachedConfigMutation(callbackContext)
        callbackContext.stateManager.withStateLock { state -> state.iterations = 999 }
        callbackContext.storage.set(mutationStorageKey, "detached-storage-value")
        callbackContext.pipeline.install(
            DetachedMutationFeature.key,
            DetachedMutationConfig(),
            Unit,
        )
        callbackContext.llm.toolRegistry.add(OrdinaryTool("detached-result"))

        assertEquals(liveConfigPrompt, liveContext.config.prompt.toString())
        assertEquals(liveParams, liveContext.config.prompt.params.toString())
        assertEquals(17, liveContext.stateManager.withStateLock { it.iterations })
        assertEquals("live-storage-value", liveContext.storage.get(mutationStorageKey))
        assertNull(liveContext.pipeline.feature(Unit::class, DetachedMutationFeature))
        assertNull(liveContext.llm.toolRegistry.getToolOrNull("safe-detached-test-tool"))
        assertNull(liveContext.llm.toolRegistry.getToolOrNull("ordinary"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertDetachedConfigMutation(callbackContext: AIAgentContext) {
        attemptMutation(callbackContext.config.prompt.messages)
        callbackContext.llm.prompt = callbackContext.llm.prompt.copy(
            params = callbackContext.config.prompt.params.copy(temperature = 0.25),
        )
        callbackContext.config.prompt.params.additionalProperties?.let { additionalProperties ->
            try {
                val properties = additionalProperties as MutableMap<String, Any?>
                properties["detached-only"] = REDACTED
                properties.remove("detached-only")
            } catch (_: UnsupportedOperationException) {
                // The detached default parameter map is deliberately immutable.
            } catch (_: ClassCastException) {
                // Kotlin read-only map implementations need not implement MutableMap.
            }
        }
    }

    private fun assertDetachedExecutionAncestry(
        detached: AgentExecutionInfo,
        live: AgentExecutionInfo,
    ) {
        assertTrue(detached !== live)
        assertEquals(REDACTED, detached.partName)
        val liveParent = live.parent
        if (liveParent == null) {
            assertNull(detached.parent)
        } else {
            assertDetachedExecutionAncestry(requireNotNull(detached.parent), liveParent)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> attemptMutation(values: List<T>) {
        if (values.isEmpty()) return
        try {
            val mutable = values as MutableList<T>
            val last = mutable.removeAt(mutable.lastIndex)
            mutable.add(last)
        } catch (_: UnsupportedOperationException) {
            // Some safe leaf collections are deliberately immutable singleton lists.
        } catch (_: ClassCastException) {
            // Kotlin immutable-list implementations need not implement MutableList.
        }
    }

    private fun assertNestedPartDetached(
        safePart: MessagePart,
        livePart: MessagePart,
    ) {
        when (livePart) {
            is MessagePart.Text -> {
                val safe = assertIs<MessagePart.Text>(safePart)
                assertTrue(safe.generatedFileCitations !== livePart.generatedFileCitations)
                safe.generatedFileCitations.zip(livePart.generatedFileCitations).forEach { (safeCitation, liveCitation) ->
                    assertTrue(safeCitation !== liveCitation)
                }
            }

            is MessagePart.Attachment -> assertIs<MessagePart.Text>(safePart)
            is MessagePart.Reasoning -> {
                val safe = assertIs<MessagePart.Reasoning>(safePart)
                assertTrue(safe.content !== livePart.content)
                assertTrue(safe.summary !== livePart.summary)
                assertTrue(safe.replay !== livePart.replay)
                safe.replay.zip(livePart.replay).forEach { (safeReplay, liveReplay) ->
                    assertTrue(safeReplay !== liveReplay)
                }
            }

            is MessagePart.GeneratedFile -> assertIs<MessagePart.GeneratedFile>(safePart)
            is MessagePart.HostedExecution.Request -> assertIs<MessagePart.HostedExecution.Request>(safePart)
            is MessagePart.HostedExecution.Progress -> assertIs<MessagePart.HostedExecution.Progress>(safePart)
            is MessagePart.HostedExecution.CumulativeOutput ->
                assertIs<MessagePart.HostedExecution.CumulativeOutput>(safePart)

            is MessagePart.HostedExecution.Result -> {
                val safe = assertIs<MessagePart.HostedExecution.Result>(safePart)
                assertTrue(safe.generatedFiles !== livePart.generatedFiles)
                safe.generatedFiles.zip(livePart.generatedFiles).forEach { (safeFile, liveFile) ->
                    assertTrue(safeFile !== liveFile)
                }
            }

            is MessagePart.HostedExecution.Error -> assertIs<MessagePart.HostedExecution.Error>(safePart)
            is MessagePart.CodeExecution -> {
                val safe = assertIs<MessagePart.CodeExecution>(safePart)
                assertTrue(safe.outputs !== livePart.outputs)
                safe.outputs.zip(livePart.outputs).forEach { (safeOutput, liveOutput) ->
                    assertTrue(safeOutput !== liveOutput)
                }
            }

            is MessagePart.Tool.Call -> {
                if (livePart.tool == "managed_execution") {
                    assertIs<MessagePart.Tool.Call>(safePart)
                } else {
                    assertIs<MessagePart.Text>(safePart)
                }
            }

            is MessagePart.Tool.Result -> {
                if (livePart.tool == "managed_execution" || livePart.id == PRIOR_CALL_ID) {
                    val safe = assertIs<MessagePart.Tool.Result>(safePart)
                    assertTrue(safe.parts !== livePart.parts)
                    safe.parts.zip(livePart.parts).forEach { (safeContent, liveContent) ->
                        assertTrue(safeContent !== liveContent)
                    }
                } else {
                    assertIs<MessagePart.Text>(safePart)
                }
            }
        }
    }

    private suspend fun assertDisabledServices(callbackContext: AIAgentContext) {
        val disabledCall = MessagePart.Tool.Call(
            id = "disabled-call",
            tool = "managed_execution",
            args = """{"data":"$REDACTED"}""",
        )
        assertUnsupported {
            callbackContext.environment.executeTool(disabledCall)
        }
        assertUnsupported {
            callbackContext.environment.executeTool(disabledCall, ToolCallMetadata.EMPTY)
        }
        assertUnsupported {
            callbackContext.environment.executeTools(listOf(disabledCall))
        }
        assertUnsupported {
            callbackContext.environment.executeTools(listOf(disabledCall), ToolCallMetadata.EMPTY)
        }
        assertUnsupported {
            callbackContext.environment.reportProblem(IllegalStateException(REDACTED))
        }
        val executor = callbackContext.llm.promptExecutor
        val prompt = callbackContext.llm.prompt
        val model = callbackContext.llm.model
        val tools = callbackContext.llm.tools
        val resolvedModel = ResolvedModel(model)
        assertUnsupported {
            executor.execute(prompt, model, tools)
        }
        assertUnsupported {
            executor.execute(prompt, resolvedModel, tools)
        }
        assertUnsupported {
            executor.executeStreaming(prompt, model, tools).toList()
        }
        assertUnsupported {
            executor.executeStreaming(prompt, resolvedModel, tools).toList()
        }
        assertUnsupported {
            executor.executeMultipleChoices(prompt, model, tools)
        }
        assertUnsupported {
            executor.executeMultipleChoices(prompt, resolvedModel, tools)
        }
        assertUnsupported {
            executor.moderate(prompt, model)
        }
        assertUnsupported {
            executor.moderate(prompt, resolvedModel)
        }
        assertUnsupported {
            executor.models()
        }
    }

    private suspend fun assertUnsupported(block: suspend () -> Unit) {
        val failure = try {
            block()
            null
        } catch (error: Throwable) {
            error
        }
        assertIs<UnsupportedOperationException>(failure)
    }

    private fun assertSecretsAbsent(renderedLogs: String, vararg secrets: String) {
        secrets.forEach { secret -> assertFalse(renderedLogs.contains(secret), renderedLogs) }
    }

    private fun request(code: String): ManagedExecutionEvent.Request = ManagedExecutionEvent.Request(
        sequence = 0,
        executionId = "execution-1",
        session = session(),
        code = code,
    )

    private fun result(
        sequence: Long,
        output: String,
    ): ManagedExecutionEvent.Result = ManagedExecutionEvent.Result(
        sequence = sequence,
        executionId = "execution-1",
        session = session(),
        output = output,
        exitCode = 0,
    )

    private fun session(): ManagedExecutionSessionReference.VertexAgentEngine =
        ManagedExecutionSessionReference.VertexAgentEngine(
            project = "project-1",
            location = "us-central1",
            reasoningEngineResource = "reasoningEngines/engine-1",
            sandboxResourceName = "reasoningEngines/engine-1/sandboxes/box-1",
        )

    private class PrivacyManagedTool(
        name: String = "managed_execution",
        private val events: (Args) -> Flow<ManagedExecutionEvent>,
        private val encodeResultToStringBlock: (String, JSONSerializer) -> String = { result, _ -> result },
    ) : ManagedExecutionTool<Args, String>(
        argsType = typeToken<Args>(),
        resultType = typeToken<String>(),
        name = name,
        description = "Pipeline description must be redacted.",
    ) {
        override fun executeStreaming(
            args: Args,
            metadata: ToolCallMetadata,
        ): Flow<ManagedExecutionEvent> = events(args)

        override fun decodeResult(result: ManagedExecutionEvent.Result): String =
            requireNotNull(result.output)

        override fun encodeResultToString(result: String, serializer: JSONSerializer): String =
            encodeResultToStringBlock(result, serializer)
    }

    private class OrdinaryTool(
        private val result: String,
    ) : SimpleTool<Args>(
        argsType = typeToken<Args>(),
        name = "ordinary",
        description = "Ordinary tool description.",
    ) {
        override suspend fun execute(args: Args): String = result
    }

    private class PipelineLoggingConfig : FeatureConfig()

    private class DetachedMutationConfig : FeatureConfig()

    private object PipelineLoggingFeature : AIAgentFeature<PipelineLoggingConfig, Unit> {
        override val key = createStorageKey<Unit>("managed-execution-pipeline-logging")

        override fun createInitialConfig(agentConfig: AIAgentConfig): PipelineLoggingConfig =
            PipelineLoggingConfig()
    }

    private object DetachedMutationFeature : AIAgentFeature<DetachedMutationConfig, Unit> {
        override val key = createStorageKey<Unit>("managed-callback-detached-mutation")

        override fun createInitialConfig(agentConfig: AIAgentConfig): DetachedMutationConfig =
            DetachedMutationConfig()
    }

    private class LivePromptExecutor : PromptExecutor() {
        var calls: Int = 0
            private set

        private fun providerCall(): Nothing {
            calls++
            error("Live prompt executor must not be called from a managed callback")
        }

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant = providerCall()

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Flow<StreamFrame> = flow { providerCall() }

        override suspend fun executeMultipleChoices(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): LLMChoice = providerCall()

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            providerCall()

        override fun close() = Unit
    }

    private data class CapturedExecution(
        val result: ReceivedToolResult,
        val pipelineEvents: List<PipelineEvent>,
        val logs: String,
        val liveContext: AIAgentContext,
        val livePromptBefore: Prompt,
        val livePromptAfter: Prompt,
        val livePromptExecutor: LivePromptExecutor,
        val propagatedLogs: String = "",
    )

    private data class PipelineEvent(
        val stage: String,
        val runId: String,
        val executionInfo: AgentExecutionInfo,
        val toolCallId: String?,
        val toolName: String,
        val toolDescription: String?,
        val toolArgs: JSONObject,
        val message: String? = null,
        val result: JSONElement? = null,
        val error: Throwable? = null,
        val callbackContext: AIAgentContext,
        val callbackPrompt: Prompt,
    )
}
