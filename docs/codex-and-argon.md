# Codex account transport and Gemini 4 Argon

## Codex on the JVM

`CodexLLMClient` reuses the OpenAI Responses serialisation and streaming pipeline.
Kroog continues to own tool execution. It does not launch a second agent process.
Select credentials explicitly so an application can use a separate account from
an interactive Codex session:

```kotlin
import ai.koog.prompt.executor.clients.openai.CodexAuthFileSource
import ai.koog.prompt.executor.clients.openai.CodexLLMClient
import ai.koog.http.client.java.JavaKoogHttpClient
import java.nio.file.Path

val client = CodexLLMClient(
    CodexAuthFileSource(Path.of("/configured/account/auth.json")),
    JavaKoogHttpClient.Factory(),
)
```

Use the Codex CLI to sign in with file-based credential storage under the chosen
`CODEX_HOME`. The client reads that file for each request, but does not perform
OAuth refresh. Keep the account refreshed externally through the Codex CLI;
expired or rejected credentials produce an actionable authentication failure.
Credentials must not be committed or included in diagnostics. HTTP decorators
must redact authentication headers.

Select the Java HTTP factory explicitly and include `http-client-java` in the
application dependencies. The Codex endpoint can omit the SSE content-type header,
which Ktor rejects. When multiple HTTP factories are installed, select factories
explicitly for other clients too; automatic discovery requires a single provider.

Use `OpenAIResponsesParams`. Ordinary `execute` also consumes a streaming
response. The route forces `store=false`, omits unsupported sampling and output
limit fields, and supports caller-managed function tools. Hosted Code Interpreter,
embeddings and moderation are unavailable. Low, medium, high, xhigh and max are
provider reasoning levels. Codex Ultra orchestration is not a raw reasoning value.

The target models are `gpt-6.1-sol`, `gpt-6.1-terra`, `gpt-6-astra`, `gpt-6-sol`,
`gpt-6-luna`, `gpt-5.6-sol`, `gpt-5.6-terra` and `gpt-5.6-luna`. Account access can
vary. Query the catalogue with `ProviderApi.CODEX_RESPONSES` for route-specific
capabilities rather than using the ordinary OpenAI API profile. Codex's shared
context includes output; an unknown output ceiling is not an unlimited allowance.
Token usage is reported when supplied by the backend. Subscription allowance is
not an API-dollar price, and unavailable monetary cost must not be treated as zero.

## Gemini 4 Argon on Vertex

`GoogleModels.Gemini4Argon` uses the existing Google client and caller-configured
Vertex HTTP authentication and endpoint. The model ID is `gemini-4-argon`.
Use the supported `global`, `us` or `eu` location for an authorised project.

Argon has a shared 1,048,576-token context. The conservative catalogue budget
reserves 32,768 tokens for output. A normal non-streaming request is limited to
32,768 output tokens; streaming can request up to 65,536. Low, medium and high
thinking are supported. Thinking budgets and minimal thinking are rejected.
Sampling controls are omitted, explicit caching is rejected, and a conversation
must not end with a prefilled model response.

Million-token output continuation is not implemented. A large context window
does not imply that a single request can generate a million tokens.

## Explicit live validation

The integration tests make paid provider calls and must be selected explicitly.
`CodexModelsIntegrationTest` requires `CODEX_AUTH_FILE`; it exercises all eight
models plus tool continuation and structured output on 6.1 Sol and Terra.
`LatestModelsIntegrationTest.integration_testVertexGemini4Argon` requires
`VERTEX_PROJECT_ID`, `VERTEX_ACCESS_TOKEN` and optionally `VERTEX_LOCATION`.
Run Codex checks through the OpenAI client module’s `jvmIntegrationTest` task;
run Vertex checks through `:integration-tests:jvmIntegrationTest`. Select the class
or method with `--tests`;
the ordinary `jvmTest` task excludes live checks. Keep test logs outside the repository.

Implementation tests and actual account access are separate checks. See the
current validation record for results; this document does not assert that a model
has passed live validation merely because it is registered.

Sources: [Codex authentication](https://learn.chatgpt.com/docs/auth),
[Argon specification](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/gemini/4-argon),
[Argon guide](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/guides/gemini-4-argon).

## Validation status, 28 October 2026

The Google JVM suite passed 78 tests with no skips. The catalogue suite passed
298 tests with three existing skips. The final OpenAI suite passed 356 tests with no skips, including 13 Codex
regressions. Scoped new-file lint and JVM JAR generation passed.
An external JVM consumer passed 133 assertions against final local staged binary
artefacts, including the catalogue fixture and provider registrations. These
checks cover the working-tree candidate, not a clean release archive or complete
Maven dependency resolution. New public JVM signatures were checked against
compiled output; older unrelated OpenAI API-dump drift remains outside this change.

The Argon Vertex live check passed streaming, tool calls, tool-result continuation
and structured JSON. Its four requests recorded 449 input and 59 output tokens.

Ten Codex live tests passed: all eight target models returned a response, and
6.1 Sol and Terra both completed tool-call continuation and structured JSON checks.
Those 14 requests recorded 444 input and 116 output tokens. Separate synthetic
smoke requests also succeeded. These token counts exclude earlier failed
transport attempts whose usage could not be captured, so they are not a total
cost statement. Neither route supplied monetary cost metadata.

The susometer review found no material issues. The correctness review found one
credential-reload error-classification issue; the local fix and regression test
preserve the authentication error and external-refresh guidance after a 401.
