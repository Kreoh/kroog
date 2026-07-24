package ai.koog.prompt.executor.managed

import aws.sdk.kotlin.services.bedrockagentcore.model.InputContentBlock
import aws.sdk.kotlin.services.bedrockagentcore.model.InvokeCodeInterpreterRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.ProgrammingLanguage
import aws.sdk.kotlin.services.bedrockagentcore.model.StartCodeInterpreterSessionRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.StopCodeInterpreterSessionRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.ToolArguments
import aws.sdk.kotlin.services.bedrockagentcore.model.ToolName
import java.util.UUID

/**
 * Configuration for the Bedrock AgentCore Code Interpreter data-plane adapter.
 *
 * When [clientToken] is omitted, each [ManagedExecutionService.acquireSession] call receives a fresh token. Supplying
 * a token deliberately gives every acquisition made through this configuration the same idempotency identity.
 */
public data class BedrockAgentCoreConfiguration(
    val region: String,
    val codeInterpreterIdentifier: String = DEFAULT_CODE_INTERPRETER_IDENTIFIER,
    val sessionName: String = DEFAULT_SESSION_NAME,
    val sessionTimeoutSeconds: Int = DEFAULT_SESSION_TIMEOUT_SECONDS,
    val clientToken: String? = null,
    val maxInputBytes: Long = MAX_TOOL_ARGUMENT_BYTES,
    val maxOutputBytes: Long = MAX_TOOL_ARGUMENT_BYTES,
    val cancellationCleanupTimeoutMillis: Long = DEFAULT_CANCELLATION_CLEANUP_TIMEOUT_MILLIS,
) {
    init {
        require(REGION.matches(region)) {
            "Bedrock AgentCore region must be a valid AWS region identifier"
        }
        require(codeInterpreterIdentifier.isNotBlank() && codeInterpreterIdentifier.none(Char::isISOControl)) {
            "Bedrock AgentCore code interpreter identifier must be non-blank and contain no control characters"
        }
        require(sessionName.isNotBlank() && sessionName.length <= MAX_SESSION_NAME_LENGTH) {
            "Bedrock AgentCore session name must contain 1 to $MAX_SESSION_NAME_LENGTH characters"
        }
        require(sessionName.none(Char::isISOControl)) {
            "Bedrock AgentCore session name must contain no control characters"
        }
        require(sessionTimeoutSeconds in MIN_SESSION_TIMEOUT_SECONDS..MAX_SESSION_TIMEOUT_SECONDS) {
            "Bedrock AgentCore session timeout must be between $MIN_SESSION_TIMEOUT_SECONDS and " +
                "$MAX_SESSION_TIMEOUT_SECONDS seconds"
        }
        clientToken?.let(::requireClientToken)
        require(maxInputBytes in 1..MAX_TOOL_ARGUMENT_BYTES) {
            "Bedrock AgentCore input byte limit must be between 1 and $MAX_TOOL_ARGUMENT_BYTES"
        }
        require(maxOutputBytes in 1..MAX_TOOL_ARGUMENT_BYTES) {
            "Bedrock AgentCore output byte limit must be between 1 and $MAX_TOOL_ARGUMENT_BYTES"
        }
        require(cancellationCleanupTimeoutMillis > 0) {
            "Bedrock AgentCore cancellation cleanup timeout must be positive"
        }
    }

    internal fun newStartClientToken(): String = clientToken ?: "koog-${UUID.randomUUID()}"

    internal fun stopClientToken(sessionId: String): String =
        deterministicToken("stop", region, codeInterpreterIdentifier, sessionId)

    override fun toString(): String =
        "BedrockAgentCoreConfiguration(region=$region, " +
            "codeInterpreterIdentifier=$codeInterpreterIdentifier, sessionName=$sessionName, " +
            "sessionTimeoutSeconds=$sessionTimeoutSeconds, clientToken=<redacted>, " +
            "maxInputBytes=$maxInputBytes, maxOutputBytes=$maxOutputBytes, " +
            "cancellationCleanupTimeoutMillis=$cancellationCleanupTimeoutMillis)"

    public companion object {
        public const val DEFAULT_CODE_INTERPRETER_IDENTIFIER: String = "aws.codeinterpreter.v1"
        public const val DEFAULT_SESSION_NAME: String = "koog-managed-execution"
        public const val DEFAULT_SESSION_TIMEOUT_SECONDS: Int = 900
        public const val MIN_SESSION_TIMEOUT_SECONDS: Int = 1
        public const val MAX_SESSION_TIMEOUT_SECONDS: Int = 28_800
        public const val MAX_SESSION_NAME_LENGTH: Int = 100
        public const val MIN_CLIENT_TOKEN_LENGTH: Int = 33
        public const val MAX_CLIENT_TOKEN_LENGTH: Int = 256
        public const val MAX_TOOL_ARGUMENT_BYTES: Long = 100_000_000L
        public const val DEFAULT_CANCELLATION_CLEANUP_TIMEOUT_MILLIS: Long = 5_000L
    }
}

/** Typed lifecycle failure from a Bedrock AgentCore session start or stop operation. */
public class BedrockAgentCoreLifecycleException(
    public val kind: ManagedExecutionErrorKind,
    public val providerCode: String,
    message: String,
) : Exception(message)

internal object BedrockAgentCoreSdkMapper {
    fun startRequest(
        configuration: BedrockAgentCoreConfiguration,
        clientToken: String = configuration.newStartClientToken(),
    ): StartCodeInterpreterSessionRequest =
        StartCodeInterpreterSessionRequest {
            codeInterpreterIdentifier = configuration.codeInterpreterIdentifier
            this.clientToken = clientToken
            name = configuration.sessionName
            sessionTimeoutSeconds = configuration.sessionTimeoutSeconds
        }

    fun stopRequest(
        configuration: BedrockAgentCoreConfiguration,
        reference: ManagedExecutionSessionReference.BedrockAgentCore,
    ): StopCodeInterpreterSessionRequest = StopCodeInterpreterSessionRequest {
        codeInterpreterIdentifier = reference.codeInterpreterIdentifier
        sessionId = reference.sessionId
        clientToken = configuration.stopClientToken(reference.sessionId)
    }

    fun executeRequest(
        reference: ManagedExecutionSessionReference.BedrockAgentCore,
        request: ManagedExecutionRequest,
    ): InvokeCodeInterpreterRequest = invokeRequest(
        reference = reference,
        name = ToolName.ExecuteCode,
        arguments = ToolArguments {
            code = request.code
            language = request.language.toProgrammingLanguage()
        },
    )

    fun writeFilesRequest(
        reference: ManagedExecutionSessionReference.BedrockAgentCore,
        files: List<ManagedExecutionInputFile>,
    ): InvokeCodeInterpreterRequest = invokeRequest(
        reference = reference,
        name = ToolName.WriteFiles,
        arguments = ToolArguments {
            content = files.map { file ->
                InputContentBlock {
                    path = file.filename
                    blob = file.bytes
                }
            }
        },
    )

    fun readFilesRequest(
        reference: ManagedExecutionSessionReference.BedrockAgentCore,
        paths: List<String>,
    ): InvokeCodeInterpreterRequest = invokeRequest(
        reference = reference,
        name = ToolName.ReadFiles,
        arguments = ToolArguments {
            this.paths = paths
        },
    )

    fun listFilesRequest(
        reference: ManagedExecutionSessionReference.BedrockAgentCore,
        directoryPath: String,
    ): InvokeCodeInterpreterRequest = invokeRequest(
        reference = reference,
        name = ToolName.ListFiles,
        arguments = ToolArguments {
            this.directoryPath = directoryPath
        },
    )

    fun removeFilesRequest(
        reference: ManagedExecutionSessionReference.BedrockAgentCore,
        paths: List<String>,
    ): InvokeCodeInterpreterRequest = invokeRequest(
        reference = reference,
        name = ToolName.RemoveFiles,
        arguments = ToolArguments {
            this.paths = paths
        },
    )

    private fun invokeRequest(
        reference: ManagedExecutionSessionReference.BedrockAgentCore,
        name: ToolName,
        arguments: ToolArguments,
    ): InvokeCodeInterpreterRequest = InvokeCodeInterpreterRequest {
        codeInterpreterIdentifier = reference.codeInterpreterIdentifier
        sessionId = reference.sessionId
        this.name = name
        this.arguments = arguments
    }
}

internal fun String.toProgrammingLanguage(): ProgrammingLanguage = when (lowercase()) {
    "python" -> ProgrammingLanguage.Python
    "javascript" -> ProgrammingLanguage.Javascript
    "typescript" -> ProgrammingLanguage.Typescript
    else -> throw IllegalArgumentException(
        "Bedrock AgentCore language must be python, javascript, or typescript"
    )
}

private fun requireClientToken(value: String) {
    val officialLengths = IntRange(
        BedrockAgentCoreConfiguration.MIN_CLIENT_TOKEN_LENGTH,
        BedrockAgentCoreConfiguration.MAX_CLIENT_TOKEN_LENGTH,
    )
    require(
        value.length in officialLengths &&
            CLIENT_TOKEN.matches(value)
    ) {
        "Bedrock AgentCore client token must be 33 to 256 alphanumeric or hyphen characters, " +
            "starting and ending with an alphanumeric character"
    }
}

private fun deterministicToken(vararg components: String): String =
    "koog-${sha256Hex(components.joinToString("\u0000").encodeToByteArray())}"

private val REGION = Regex("[a-z]{2}(?:-gov)?-[a-z0-9-]+-[0-9]")
private val CLIENT_TOKEN = Regex("[A-Za-z0-9](?:-*[A-Za-z0-9]){32,255}")
