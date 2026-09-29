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
