package ai.koog.integration.tests.client

import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAICodeInterpreterConfig
import ai.koog.prompt.executor.clients.openai.OpenAIContainersClient
import ai.koog.prompt.executor.clients.openai.OpenAIHostedShellConfig
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIResourceResult
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

@EnabledIfEnvironmentVariable(named = "KROOG_HOSTED_EXECUTION_LIVE", matches = "1")
@Execution(ExecutionMode.SAME_THREAD)
class HostedExecutionLiveIntegrationTest {
    @Test
    fun integration_testOpenAIHostedShell(): Unit = runBlocking {
        runOpenAI(OpenAIExecution.Shell)
    }

    @Test
    fun integration_testOpenAICodeInterpreterBaseline(): Unit = runBlocking {
        runOpenAI(OpenAIExecution.Python)
    }

    @Test
    fun integration_testOpenAIHostedShellAutomaticContainer(): Unit = runBlocking {
        val apiKey = openAIKey()
        val name = "kroog-auto-${UUID.randomUUID()}.txt"
        val path = "/mnt/data/$name"
        val params = OpenAIResponsesParams(maxTokens = 1200, maxToolCalls = 2, stateless = true)
            .withHostedShell(OpenAIHostedShellConfig())
        val request = prompt("hosted-shell-auto-live", params) {
            user(
                "Run this Bash snippet once in the hosted shell tool:\n" +
                    "${OpenAIExecution.Shell.writeReceipt(path)}\n" +
                    "Keep the file and include the link [Download receipt](sandbox:$path) in your answer."
            )
        }
        withTestContainers(apiKey) { _, ownedIds ->
            val frames = OpenAILLMClient(apiKey).use { client ->
                withTimeout(180_000) {
                    client.executeStreaming(request, OpenAIModels.Chat.GPT6Luna, emptyList())
                        .onEach { frame ->
                            when (frame) {
                                is StreamFrame.HostedExecutionUpdate -> frame.update.containerId?.let(ownedIds::add)
                                is StreamFrame.TextComplete -> frame.generatedFileCitations
                                    .filter { it.filename?.substringAfterLast('/') == name }
                                    .mapNotNullTo(ownedIds) { it.containerId }
                                else -> Unit
                            }
                        }
                        .toList()
                }
            }
            assertNotNull(ownedIds.singleOrNull(), "Expected one discoverable automatic shell container")
            val parts = frames.toMessageResponse().parts
            assertTrue(
                parts.filterIsInstance<MessagePart.HostedExecution.Request>().any { it.language == "bash" },
                "The automatic container request did not invoke Bash",
            )
            assertTrue(
                parts.filterIsInstance<MessagePart.HostedExecution.Result>().any {
                    it.exitCode == 0 && Regex("KROOG_SHA256=[a-f0-9]{64}\\b").containsMatchIn(it.output.orEmpty())
                },
                "The automatic container did not return a digest from execution",
            )
            println("OpenAI automatic shell container returned a genuine execution result and a container ID")
        }
    }

    private suspend fun runOpenAI(execution: OpenAIExecution) {
        val apiKey = openAIKey()
        val model = OpenAIModels.Chat.GPT6Luna
        val name = "kroog-execution-${UUID.randomUUID()}"
        val path = "/mnt/data/$name.txt"
        val initial = prompt("${execution.name}-live") {
            user(
                "Run this ${execution.language} snippet once in the hosted ${execution.toolName}:\n" +
                    "${execution.writeReceipt(path)}\n" +
                    "Keep the file in place. In your answer include the link [Download receipt](sandbox:$path)."
            )
        }

        withTestContainers(apiKey) { containers, ownedIds ->
            val provisioning = TimeSource.Monotonic.markNow()
            val containerId = withTimeout(30_000) { containers.create(name).required("Create container").id }
            ownedIds.add(containerId)
            val provisionMs = provisioning.elapsedNow().inWholeMilliseconds
            OpenAILLMClient(apiKey).use { client ->
                val params = execution.params(containerId)
                val coldStart = TimeSource.Monotonic.markNow()
                var firstResultMs: Long? = null
                val frames = withTimeout(180_000) {
                    client.executeStreaming(initial.withParams(params), model, emptyList())
                        .onEach { frame ->
                            if (firstResultMs == null && execution.isResult(frame)) {
                                firstResultMs = coldStart.elapsedNow().inWholeMilliseconds
                            }
                        }
                        .toList()
                }
                val coldTotalMs = coldStart.elapsedNow().inWholeMilliseconds
                assertNotNull(firstResultMs, "The stream did not contain an execution result")
                val cold = frames.toMessageResponse()
                val coldDigest = assertNotNull(
                    Regex("KROOG_SHA256=([a-f0-9]{64})\\b").find(execution.output(cold, containerId)),
                    "The tool did not print a digest from its execution workspace",
                ).groupValues[1]

                val warmPrompt = Prompt.build(initial.withParams(params)) {
                    message(cold)
                    user(
                        "Read the existing file in the same hosted workspace. " +
                            "Run this ${execution.language} snippet once using ${execution.toolName}:\n" +
                            "${execution.readReceipt(path)}\n" +
                            "Do not recreate the file. Reply ACK after the tool finishes."
                    )
                }
                val warmStart = TimeSource.Monotonic.markNow()
                val warm = withTimeout(180_000) { client.execute(warmPrompt, model, emptyList()) }
                val warmTotalMs = warmStart.elapsedNow().inWholeMilliseconds
                val receipt = assertNotNull(
                    Regex("KROOG_RECEIPT=([a-f0-9]{32})\\b").find(execution.output(warm, containerId)),
                    "The tool did not retrieve the receipt from the reused workspace",
                ).groupValues[1]
                assertEquals(coldDigest, sha256(receipt.encodeToByteArray()), "Container did not preserve the file")

                val citation = assertNotNull(
                    cold.parts.filterIsInstance<MessagePart.Text>().flatMap { it.generatedFileCitations }
                        .firstOrNull { it.filename?.substringAfterLast('/') == "$name.txt" },
                    "The model did not return a downloadable container-file citation",
                )
                assertEquals(containerId, citation.containerId)
                val download = TimeSource.Monotonic.markNow()
                val file = withTimeout(30_000) {
                    containers.downloadFile(containerId, citation.providerFileId).required("Download receipt")
                }
                val downloadMs = download.elapsedNow().inWholeMilliseconds
                assertTrue(file.contentEquals(receipt.encodeToByteArray()), "Downloaded receipt differed from tool output")
                println(
                    "OpenAI ${execution.name}, ${model.id}: provision_ms=$provisionMs " +
                        "cold_stream_first_result_ms=$firstResultMs cold_stream_total_ms=$coldTotalMs " +
                        "warm_nonstream_total_ms=$warmTotalMs download_ms=$downloadMs"
                )

                if (execution == OpenAIExecution.Shell) {
                    val disabled = Prompt.build(warmPrompt.withParams(params.withHostedShell(null))) {
                        message(warm)
                        user("Repeat only the 32 hex characters after KROOG_RECEIPT= in the most recent tool output.")
                    }
                    val replayFrames = withTimeout(180_000) {
                        client.executeStreaming(disabled, model, emptyList()).toList()
                    }
                    assertTrue(
                        replayFrames.none { it is StreamFrame.HostedExecutionUpdate },
                        "The follow-up emitted a new hosted execution event with shell disabled",
                    )
                    val answer = replayFrames.toMessageResponse().parts.filterIsInstance<MessagePart.Text>()
                        .joinToString("") { it.text }.trim()
                    assertEquals(receipt, answer, "The follow-up did not use the recorded shell output")
                    println("OpenAI accepted the conversation with hosted shell disabled")
                }
            }
        }
    }

    private fun openAIKey(): String = listOfNotNull(System.getenv("OPEN_AI_API_TEST_KEY"), System.getenv("OPENAI_API_KEY"))
        .firstOrNull(String::isNotBlank)
        ?: error("Set OPEN_AI_API_TEST_KEY or OPENAI_API_KEY for the live OpenAI execution tests")

    private suspend fun withTestContainers(
        apiKey: String,
        action: suspend (OpenAIContainersClient, MutableSet<String>) -> Unit,
    ) {
        HttpClientFactoryResolver.resolve().create(
            clientName = "HostedExecutionLiveIntegrationTest",
            baseUrl = "https://api.openai.com",
            headers = mapOf("Authorization" to "Bearer $apiKey"),
        ).use { resourceHttp ->
            val containers = OpenAIContainersClient(resourceHttp)
            val ownedIds = linkedSetOf<String>()
            var failure: Throwable? = null
            try {
                action(containers, ownedIds)
            } catch (primary: Throwable) {
                failure = primary
                if (ownedIds.isEmpty()) {
                    primary.addSuppressed(
                        IllegalStateException("No test container ID was observed, so deletion could not be confirmed")
                    )
                }
                throw primary
            } finally {
                var cleanupFailure: Throwable? = null
                for (id in ownedIds) {
                    try {
                        withContext(NonCancellable) {
                            withTimeout(30_000) {
                                val deletion = containers.delete(id).required("Delete test container")
                                assertEquals(id, deletion.id)
                                assertTrue(deletion.deleted, "OpenAI did not confirm deletion of the test container")
                            }
                        }
                    } catch (cleanup: Throwable) {
                        val first = failure ?: cleanupFailure
                        if (first == null) cleanupFailure = cleanup else first.addSuppressed(cleanup)
                    }
                }
                cleanupFailure?.let { throw it }
            }
        }
    }

    private enum class OpenAIExecution(val language: String, val toolName: String) {
        Shell("Bash", "shell tool"),
        Python("Python", "Code Interpreter tool");

        fun params(containerId: String): OpenAIResponsesParams {
            val params = OpenAIResponsesParams(
                maxTokens = 1200,
                maxToolCalls = 2,
                reasoning = ReasoningConfig(ReasoningEffort.LOW),
                include = listOf(OpenAIInclude.REASONING_ENCRYPTED_CONTENT) +
                    if (this == Python) listOf(OpenAIInclude.CODE_INTERPRETER_CALL_OUTPUTS) else emptyList(),
                stateless = true,
            )
            return when (this) {
                Shell -> params.withHostedShell(OpenAIHostedShellConfig(containerId = containerId))
                Python -> params.withCodeInterpreter(OpenAICodeInterpreterConfig(containerId = containerId))
            }
        }

        fun writeReceipt(path: String): String = when (this) {
            Shell -> "od -An -N16 -tx1 /dev/urandom | tr -d ' \\n' > '$path' && " +
                "printf 'KROOG_SHA256=%s\\n' \"\$(sha256sum '$path' | cut -d' ' -f1)\""
            Python -> "from pathlib import Path\nimport hashlib, secrets\n" +
                "path = Path('$path')\npath.write_text(secrets.token_hex(16))\n" +
                "print('KROOG_SHA256=' + hashlib.sha256(path.read_bytes()).hexdigest())"
        }

        fun readReceipt(path: String): String = when (this) {
            Shell -> "test -f '$path' && printf 'KROOG_RECEIPT=%s\\n' \"\$(cat -- '$path')\""
            Python -> "from pathlib import Path\nprint('KROOG_RECEIPT=' + Path('$path').read_text())"
        }

        fun isResult(frame: StreamFrame): Boolean = when (this) {
            Shell -> frame is StreamFrame.HostedExecutionUpdate && frame.update is MessagePart.HostedExecution.Result
            Python -> frame is StreamFrame.CodeExecutionComplete
        }

        fun output(reply: Message.Assistant, containerId: String): String = when (this) {
            Shell -> {
                val calls = reply.parts.filterIsInstance<MessagePart.HostedExecution.Request>()
                val results = reply.parts.filterIsInstance<MessagePart.HostedExecution.Result>()
                assertTrue(calls.any { it.language == "bash" }, "The model did not request Bash execution")
                assertTrue(results.isNotEmpty(), "The model did not return a hosted shell result")
                assertTrue(results.all { it.containerId == containerId }, "Shell result used a different container")
                assertTrue(results.all { it.exitCode == 0 }, "Shell execution did not exit successfully")
                results.mapNotNull { it.output }.joinToString("\n")
            }
            Python -> {
                val calls = reply.parts.filterIsInstance<MessagePart.CodeExecution>()
                assertTrue(calls.isNotEmpty(), "The model did not return a Code Interpreter result")
                assertTrue(calls.all { it.containerId == containerId }, "Code Interpreter used a different container")
                assertTrue(calls.all { it.failure == null }, "Code Interpreter execution failed")
                calls.flatMap { it.outputs.filterIsInstance<MessagePart.CodeExecution.Output.Logs>() }
                    .joinToString("\n") { it.logs }
            }
        }
    }

    private fun <T> OpenAIResourceResult<T>.required(operation: String): T = when (this) {
        is OpenAIResourceResult.Success -> requireNotNull(value) { "$operation returned no value" }
        else -> error("$operation failed with HTTP $statusCode")
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
