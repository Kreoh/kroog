# Model additions verified on 29 September 2026

These additions extend the upstream implementation and preserve existing fork features. There is no release version change.

| Catalogue field | API identifier | Context or input limit | Maximum output |
| --- | --- | ---: | ---: |
| `OpenAIModels.Chat.GPT6Sol` | `gpt-6-sol` | 1,050,000 context; 922,000 input | 128,000 |
| `OpenAIModels.Chat.GPT6Luna` | `gpt-6-luna` | 1,050,000 context; 922,000 input | 128,000 |
| `AnthropicModels.Opus_5_5` | `claude-opus-5-5` | 1,000,000 context | 128,000 |
| `AnthropicModels.Sonnet_5_5` | `claude-sonnet-5-5` | 1,000,000 context | 128,000 |
| `GoogleModels.Gemini3_8Flash` | `gemini-3.8-flash` | 1,048,576 input | 65,536 |

Sources: [GPT-6 Sol](https://developers.openai.com/api/docs/models/gpt-6-sol), [GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna), [Claude model comparison](https://platform.claude.com/docs/en/models/overview), [Gemini 3.8 Flash](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash). The catalogue represents Google's published input limit in its existing `contextLength` field. `LLModel` has no separate maximum-input field, so OpenAI's 922,000-token input limit is documented separately.

`gpt-6-terra` was not added: it is absent from the verified public catalogue and the authenticated model lookup returned HTTP 404. Sol and Luna each returned HTTP 200 with the expected identifier. GPT-5.6 Terra remains available in the existing catalogue.

## Request compatibility

- Sol and Luna default to Responses and preserve all supported reasoning efforts, including `max`. Explicit Chat Completions permits tools only with `none` reasoning. Sampling and log probabilities are retained at `none` and omitted otherwise. Existing Astra and GPT-5.6 behaviour remains covered by regression tests. OpenAI deployment aliases retain family behaviour when their context, output and capability profile matches the catalogue, including rebuilt capability lists.
- Claude 5.5 validates the flattened payload, including raw properties. Both models reject forced tool selection, manual thinking budgets, disabled thinking and assistant prefill. Sampling controls are omitted. Sonnet accepts raw `between_tools` thinking at low, medium or high effort, with no other thinking fields. Existing signed and redacted reasoning replay remains in use.
- Gemini 3.8 uses the existing fixed-sampling and tool-replay implementation. It rejects minimal thinking and manual thinking budgets, including raw overrides, and retains low, medium and high levels. Existing streaming, hosted execution, cache accounting and thought-signature handling remain in use.

## Validation

Module-specific JVM tests and JAR builds ran with Java 21 and Gradle offline:

```sh
./gradlew :prompt:prompt-executor:prompt-executor-clients:prompt-executor-openai-client:jvmTest :prompt:prompt-executor:prompt-executor-clients:prompt-executor-anthropic-client:jvmTest :prompt:prompt-executor:prompt-executor-clients:prompt-executor-google-client:jvmTest :prompt:prompt-executor:prompt-executor-clients:prompt-executor-openai-client:jvmJar :prompt:prompt-executor:prompt-executor-clients:prompt-executor-anthropic-client:jvmJar :prompt:prompt-executor:prompt-executor-clients:prompt-executor-google-client:jvmJar --offline --console=plain
```

Results: OpenAI 321, Anthropic 80, Google 66 tests, all passing with no failures or skips. Anthropic was rerun after tightening the Sonnet thinking-field check. Integration test classes compile. All three module-specific `generatePomFileForJvmPublication` tasks pass; no artefacts were published.

Compiled JVM ABI comparisons against the captured pre-edit classes show exactly five additive model fields. Checked-in JVM dumps include those fields. The Anthropic dump matches exactly. OpenAI and Google already differed from their checked-in dumps before these changes; their existing differences are preserved. No non-JVM task or ABI update ran.

### Live tests

```sh
./gradlew :integration-tests:jvmIntegrationTest --tests '*LatestModelsIntegrationTest.integration_testOpenAI*' --offline --console=plain --no-parallel
```

Both tests passed with no skips. Each verifies authenticated model retrieval, streamed text and usage, Responses tool calling, replay of the assistant response and tool result, and Chat Completions tool calling at `none` effort. Tests use bounded output budgets and synthetic prompts.

Three reusable Vertex tests passed for Opus 5.5, Sonnet 5.5 and Gemini 3.8 Flash, with no failures, errors or skips:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :integration-tests:jvmIntegrationTest --tests '*LatestModelsIntegrationTest.integration_testVertex*' --offline --console=plain --no-parallel --no-daemon --quiet
```

Each checks streamed text, positive input usage, a tool call, replay of the assistant response and tool result, the returned probe value, and positive output usage. Requests use synthetic prompts and a 2,048-token output budget. Claude uses adaptive thinking with low effort; Gemini uses low thinking. These checks exercise Anthropic's Vertex client and Google's client with an injected authenticated HTTP client.

The Vertex tests accept `VERTEX_ACCESS_TOKEN`, `VERTEX_PROJECT_ID` and optional `VERTEX_LOCATION` (default `eu`). This run used an in-memory OAuth access token derived from the user-specified ChatUI service account and the `eu` location. No credentials are stored in the repository. The `eu` and `us` multi-region endpoints use `https://aiplatform.LOCATION.rep.googleapis.com`, as documented in [Google's endpoint locations](https://docs.cloud.google.com/gemini-enterprise-agent-platform/resources/locations). Global and individual regional endpoints are also supported by the test configuration.

The native Anthropic and Gemini tests compile but remain unrun because direct provider API keys were not supplied. Those separate tests compare token limits with native model metadata before checking streaming and tool replay. The Vertex tests do not call native metadata endpoints. To run the native tests, configure the provider keys and select:

```sh
./gradlew :integration-tests:jvmIntegrationTest --tests '*LatestModelsIntegrationTest.integration_testAnthropic*' --tests '*LatestModelsIntegrationTest.integration_testGemini*' --offline --console=plain --no-parallel
```

The tests accept `ANTHROPIC_API_TEST_KEY` or `ANTHROPIC_API_KEY`, and `GEMINI_API_TEST_KEY`, `GEMINI_API_KEY` or `GOOGLE_API_KEY`. OpenAI accepts `OPEN_AI_API_TEST_KEY` or `OPENAI_API_KEY`. Missing credentials fail a selected test explicitly. The existing `skip.llm.providers` property is honoured before any credential or network access.

Token limits were verified against official specifications only. OpenAI's model metadata endpoint does not report those limits, and the native Anthropic and Gemini metadata checks remain unrun. No full-window or maximum-output stress test was performed. Successful small requests establish runtime compatibility; they do not empirically prove the entire context boundary.

Independent review identified an Astra alias regression, which was corrected by retaining structural profile matching and adding rebuilt-list regression cases. The [alignment audit](upstream/koog-1.3.0-alignment.md#s8-final-accounting) records the accepted local publication closure and current whole-plan review status.

## Additional models for Kroog 1.1.1-kroog.13

GPT-6.1 Sol, DeepSeek V4.1 Flash and the Bedrock model additions were committed after
`1.1.1-kroog.12`. GPT-6.1 Sol's user-added live test has a passing local result. Its
streaming and Responses tool replay checks and explicit Chat Completions request use low
reasoning effort. The OpenAI JVM suite passed 330 tests, DeepSeek passed 29 and Bedrock
passed 216, with no failures, errors or skips.

Eight selected Bedrock live tests passed across three runs on 30 September 2026:

- `integration_testBedrockRuntimeOpus55` and `integration_testBedrockRuntimeSonnet55`:
  streamed text and input usage, tool calls, replay and returned probe text with output usage.
- `integration_testBedrockRuntimeAstra`: the same checks after removing automatic Converse
  cache markers. Runtime uses the `us.openai.gpt-6-astra` inference profile and default reasoning.
- `integration_testBedrockMantleGemma31B`, `integration_testBedrockMantleGemma26BA4B`,
  `integration_testBedrockMantleGemmaE2B` and `integration_testBedrockMantleAstra`:
  streamed Responses text, tools, replay and a separate Chat Completions request.
- `integration_testBedrockNovaEmbeddings`: 3072 finite values, including non-zero values,
  returned from a text embedding request in `us-east-1`.

Select the live checks with the JVM integration task:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :integration-tests:jvmIntegrationTest \
  --tests '*NewProviderModelsIntegrationTest.integration_testBedrock*' \
  --offline --console=plain --no-parallel
```

The tests accept AWS access credentials with an optional session token. Mantle uses a
Bedrock API key when supplied, otherwise test-only SigV4 authentication through the
injected HTTP client. Credentials and signatures stay in memory. No external credentials
were created or committed. Initial failed attempts identified Astra's cache-marker issue,
an unsupported `reasoning_effort` Runtime parameter and Nova's regional restriction.
These were corrected before the passing runs.

`integration_testDeepSeekV41Flash` is ready but remains unrun because neither
`DEEPSEEK_API_TEST_KEY` nor `DEEPSEEK_API_KEY` was set. It checks generation, streaming,
tools and replay, then red-image recognition through the stable Flash ID and both legacy
aliases. The DeepSeek JVM tests exercise URL and Base64 image serialisation through a
mocked HTTP engine.

The complete Bedrock JVM ABI dump matches compiled classes. OpenAI's previously recorded
unrelated dump drift is retained. No non-JVM compilation or ABI regeneration ran.

### Local release preparation

The `1.1.1-kroog.13` release tree was staged unsigned in an isolated archive of feature
commit `e94990f4dc4437e1e199d9da7fc3097e78056056` with the reviewed version and documentation
changes overlaid. Java 21 ran all 87 module-qualified `ArtifactsRepository` publication
tasks from `gradle/kroog-jvm-publications.txt`, using `--offline --no-parallel --no-daemon`
and `BRANCH_KOOG_IS_RELEASING_FROM=release/1.1.1-kroog.13`. The task graph was checked before
execution; no non-JVM compile or aggregate ABI task ran. Signing and remote publication
credentials were omitted.

Local validation verified 87 coordinates (46 stable and 41 beta), 522 primary files,
2088 MD5, SHA-1, SHA-256 and SHA-512 checksum sidecars, and 252 internal POM dependency
references. Binary, sources and Javadoc JARs passed ZIP integrity checks. POMs, Gradle
module files and coordinate metadata were non-empty, and every internal dependency
resolved to a coordinate and version in the staged closure. The Bedrock POM exports the
new OpenAI client dependency.

A separate Kotlin JVM consumer used POM and artefact resolution with Gradle metadata
redirection disabled. It compiled and ran checks for GPT-6.1 Sol, DeepSeek V4.1 Flash and
image capability, all seven Bedrock definitions and the Mantle client class. No provider
request was made by this consumer. Local staging validates unsigned JVM artefacts;
release signing, the signed Central Portal bundle and remote publication remain separate.
