# Kroog

[![Kotlin Stable](https://kotl.in/badges/stable.svg)](https://kotlinlang.org/docs/components-stability.html)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.10-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![GitHub licence](https://img.shields.io/github/license/Kreoh/kroog)](LICENSE.txt)

Kroog is Kreoh's JVM-only fork of [Koog](https://github.com/JetBrains/koog), an AI agent framework for Kotlin and Java. It incorporates upstream Koog **1.3.0**, with additional model support, provider replay, prompt caching and token accounting fixes. Kroog is licensed under Apache 2.0.

## Release preparation and upstream comparison

Comparison checked on **1 October 2026**: this README describes the source at [commit `7ca670394e`][release-source], prepared as `1.3.0-kroog.2` (beta modules: `1.3.0-beta-kroog.2`). The incorporated upstream baseline is [Koog 1.3.0, commit `3acc88cf8c`][upstream-source]. The [alignment audit][alignment] records the incorporated changes and retained fork contracts.

The first streamed-identity preparation is `1.3.0-kroog.1`; the later correctness batches prepare `1.3.0-kroog.2`. **Both are unpublished release preparations.** Installation examples below use confirmed published revision 15. The [correctness import record][correctness-imports] identifies the three batches and pinned upstream authorship.

Compared with that upstream baseline, Kroog retains these additions and adaptations:

- **Provider replay and hosted execution:** preserve signed and redacted reasoning, tool-call identities, generated files and provider-managed tool results across subsequent requests. See the [alignment audit][alignment] and [provider validation record][model-validation].
- **Prompt caching:** construct immutable request views, validate the four-breakpoint limit and cache TTL ordering, and add an eligible rolling breakpoint. See [the cache policy][cache-policy]. Bedrock Astra disables automatic Converse cache markers, as recorded in the [model validation][model-validation].
- **Token accounting:** keep input and total usage inclusive, expose cache reads and writes separately, and preserve omitted counts versus reported zero. OpenAI and Azure cache-write counts survive ordinary and streamed responses, including incomplete responses. See the [revision 15 notes][release-notes]. Upstream Google cache-count support is incorporated with the fork's structured accounting retained [in the audit][alignment].
- **Model catalogue:** expose canonical semantic profiles, explicit aliases, reasoning and sampling restrictions, MIME types and provider API compatibility. Deployment names remain separate from semantic IDs. See [the catalogue][catalogue] and [revision 14 notes][release-notes].
- **Streamed tool-call identities:** buffer early argument fragments until a usable ID and name arrive, then emit them once in per-call order with resolved identity. Parallel calls remain separate. Completion retains the full arguments without replaying argument deltas. Google emits serialised parsed arguments; this release does not establish incremental Google argument support. See the [identity release notes][release-notes].
- **Correctness batches:** improve nested tool schemas, discriminator handling, Gemini argument and safety handling, request credentials, memory pairing, parallel context isolation, MCP schemas, tracing and Spring metadata. See the [import record][correctness-imports].
- **Streamed history and serialisers:** record completed streamed responses automatically within active write sessions, preserving metadata, tools and reasoning. Failed, cancelled or incomplete streams leave history unchanged. Explicit cache directive serializer modules preserve concrete types. See the [remaining changes][correctness-imports].
- **Batch embeddings and DeepSeek helpers:** add batch embedding APIs with a compatible sequential default, an OpenAI batch path and simple DeepSeek executor helpers. See the [remaining changes][correctness-imports].
- **Skills discovery safeguards:** retain strict YAML validation, immutable registries, typed loading and secure JVM filesystem discovery while adopting upstream skills APIs. See [the alignment audit][alignment].

Upstream features such as skills discovery, Bedrock AgentCore Runtime, Google cache-count parsing and Langfuse reasoning traces are incorporated baseline features. The audit records their fork adaptations.

The source validation covers 26 affected JVM suites and two Spring suites, with 2,665 passing tests and 57 existing skips. These are local test results and do not establish live provider compatibility or Maven Central publication. See the [import record][correctness-imports] for scope.

## Recent model support

Source and catalogue checked on **1 October 2026** at [the prepared release commit][release-source]. This table describes implemented support at that revision. It does not assert current provider availability. Limits distinguish the catalogue's maximum input from the shared context window.

| Model developer | Provider definition and semantic ID | Hosting and API support | Input and output token ceilings |
| --- | --- | --- | --- |
| OpenAI | `OpenAIModels.Chat.GPT6Astra`, `gpt-6-astra` | OpenAI Responses; Bedrock Runtime Converse and Mantle through OpenAI-compatible Responses and Chat Completions | 922,000 input; 128,000 output; 1,050,000 shared context |
| OpenAI | `OpenAIModels.Chat.GPT6Sol`, `gpt-6-sol`; `GPT6Luna`, `gpt-6-luna` | OpenAI Responses declared in the catalogue; provider definitions also allow explicit Chat Completions, with tools at `none` reasoning | 922,000 input; 128,000 output; 1,050,000 shared context |
| OpenAI | `OpenAIModels.Chat.GPT6_1Sol`, `gpt-6.1-sol` | OpenAI Responses declared in the catalogue; explicit Chat Completions supports text only | 922,000 input; 128,000 output; 1,050,000 shared context |
| Anthropic | `AnthropicModels.Opus_5_5`, `claude-opus-5-5`; `Sonnet_5_5`, `claude-sonnet-5-5` | Anthropic Messages client definitions; catalogue routes: Vertex Anthropic Messages, Bedrock Anthropic Messages and Bedrock Converse | 1,000,000 catalogue input; 128,000 output |
| Google | `GoogleModels.Gemini3_8Flash`, `gemini-3.8-flash` | Gemini GenerateContent client definition; catalogue route: Vertex Gemini GenerateContent | 1,048,576 input; 65,536 output |

Definitions: [OpenAI][openai-models], [Anthropic][anthropic-models], [Google][google-models] and [Bedrock][bedrock-models]. [The central catalogue][catalogue] records exact declared API routes and aliases. Native Anthropic and Gemini APIs have provider definitions but no separate native API enum in this revision's catalogue; route lookup must not invent those entries. The listed semantic IDs have no additional catalogue aliases. Other profiles include explicit DeepSeek Flash aliases, Mantle-only Gemma 4 routes and Bedrock Nova text embeddings, documented in [the validation record][model-validation].

Sol and Luna accept `none`, `low`, `medium`, `high`, `xhigh` and `max` reasoning; sampling is sent only at `none`. On OpenAI, Astra and Sol 6.1 omit sampling and require Responses for tools. Claude 5.5 omits sampling and rejects disabled thinking, manual budgets, forced tool selection and assistant prefill. Gemini 3.8 omits sampling and supports `low`, `medium` and `high` thinking. Astra's Bedrock Converse catalogue profile excludes structured output and hosted execution; compatible API routes exclude hosted execution. Use `ModelCatalogue.find(id, api)` when selecting a declared route.

The [dated validation record][model-validation] contains provider documentation links and the precise test scope. Recorded bounded live checks cover OpenAI, Vertex and selected Bedrock routes. Direct Anthropic and Gemini metadata and live checks were unrun; full context-window stress tests were unrun. Historical staged consumer results belong to their recorded source versions and coordinates. The [release notes][release-notes] record the identity regression checks and existing validation limits, including unrelated JVM ABI dump drift.

### Key features

Key features of Koog include:

- **JVM development**: Build Kotlin and Java agents with JVM artefacts.
- **Reliability and fault-tolerance**: Handle failures with built-in retries and restore the agent state at specific points during execution with the agent persistence feature.
- **Intelligent history compression**: Optimise token usage while maintaining context in long-running conversations using advanced built-in history compression techniques.
- **Enterprise-ready integrations**: Use integration with popular JVM frameworks such as Spring Boot and Ktor to embed Koog into your applications.
- **Observability with OpenTelemetry exporters**: Monitor and debug applications with built-in support for popular observability providers (W&B Weave, Langfuse).
- **LLM switching and seamless history adaptation**: Switch to a different LLM at any point without losing the existing conversation history, or reroute between multiple LLM providers.
- **Integration with JVM and Kotlin applications**: Build AI agents with an idiomatic, type-safe Kotlin DSL designed specifically for JVM and Kotlin developers.
- **Model Context Protocol integration**: Use Model Context Protocol (MCP) tools in AI agents.
- **Agent Client Protocol integration**: Build ACP-compliant agents that can communicate with standardised client applications using the Agent Client Protocol (ACP).
- **Knowledge retrieval and memory**: Retain and retrieve knowledge across conversations using vector embeddings and RAG.
- **Powerful Streaming API**: Process responses in real-time with streaming support and parallel tool calls.
- **Modular feature system**: Customise agent capabilities through a composable architecture.
- **Flexible graph workflows**: Design complex agent behaviours using intuitive graph-based workflows.
- **Custom tool creation**: Enhance your agents with tools that access external systems and APIs.
- **Comprehensive tracing**: Debug and monitor agent execution with detailed, configurable tracing.

### Available LLM providers and platforms

The LLM providers and platforms whose LLMs you can use to power your agent capabilities:

- Google
- OpenAI
- Anthropic
- DeepSeek
- OpenRouter
- Ollama
- Bedrock

### Quickstart example

To help you get started with AI agents, here is a quick example:

```kotlin
import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // Before you run the example, assign a corresponding API key as an environment variable.
    val apiKey = requireNotNull(System.getenv("OPENAI_API_KEY"))

    val agent = AIAgent(
        promptExecutor = MultiLLMPromptExecutor(OpenAILLMClient(apiKey)),
        systemPrompt = "You are a helpful assistant. Answer user questions concisely.",
        llmModel = OpenAIModels.Chat.GPT4o
    )

    val result = agent.run("Hello! How can you help me?")
    println(result)
}
```

## Using in your projects

### Supported targets

Kroog currently builds, validates and publishes JVM targets only. Upstream Koog also supports JS, WasmJS and iOS.

### Requirements

- JDK 17 or higher is required to use the framework on JVM.
- Kotlin 2.3.10 or higher should be set explicitly in existing projects. Please check the [libs.versions.toml](gradle/libs.versions.toml) to know more about Kotlin dependencies (currently it uses kotlinx-coroutines 1.10.2, kotlinx-serialization 1.10.0 and kotlinx-datetime 0.7.1)

### Published dependencies

Checked on **1 October 2026**: Maven Central serves the [stable umbrella POM][stable-pom] at `1.1.1-kroog.15` and the [beta additions umbrella POM][beta-pom] at `1.1.1-beta-kroog.15`. The prepared stable `1.3.0-kroog.2` umbrella POM returns HTTP 404. Keep the following published coordinates until the prepared release is published and verified. Package imports remain `ai.koog`; Maven coordinates use `com.kreoh.kroog`.

The stable umbrella supplies the main agent APIs. Add the beta umbrella only when using its additional modules. Gradle consumers should resolve these JVM publications through POMs, using the repository configuration below. See [PUBLISHING.md](PUBLISHING.md) for the publication inventory and resolution checks.

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral {
        metadataSources {
            mavenPom()
            artifact()
            ignoreGradleMetadataRedirection()
        }
    }
}

dependencies {
    implementation("com.kreoh.kroog:koog-agents-jvm:1.1.1-kroog.15")
    // Optional beta modules:
    implementation("com.kreoh.kroog:koog-agents-additions-jvm:1.1.1-beta-kroog.15")
}
```

### Gradle (Groovy)

```groovy
repositories {
    mavenCentral {
        metadataSources {
            mavenPom()
            artifact()
            ignoreGradleMetadataRedirection()
        }
    }
}

dependencies {
    implementation 'com.kreoh.kroog:koog-agents-jvm:1.1.1-kroog.15'
    // Optional beta modules:
    implementation 'com.kreoh.kroog:koog-agents-additions-jvm:1.1.1-beta-kroog.15'
}
```

### Maven

Maven Central is enabled by default. Add these entries inside `dependencies` in `pom.xml`:

```xml
<dependency>
    <groupId>com.kreoh.kroog</groupId>
    <artifactId>koog-agents-jvm</artifactId>
    <version>1.1.1-kroog.15</version>
</dependency>
<!-- Optional beta modules: -->
<dependency>
    <groupId>com.kreoh.kroog</groupId>
    <artifactId>koog-agents-additions-jvm</artifactId>
    <version>1.1.1-beta-kroog.15</version>
</dependency>
```

## Versioning

Kroog uses the incorporated Koog version number as a prefix, with a suffix integer to identify our versions, for example `1.3.0-kroog.1` for a Kroog version incorporating Koog v1.3.0. See [VERSIONING.md](VERSIONING.md) for details.

## Contributing
It would be best to contribute directly to upstream Koog, but you can open a PR here if you like.

## Licence

Kroog is licensed under the [Apache 2.0 licence](LICENSE.txt).

[release-source]: https://github.com/Kreoh/kroog/tree/7ca670394ee85767b621ba46dd788b5b4b65a00f
[upstream-source]: https://github.com/JetBrains/koog/tree/3acc88cf8ce70b87d8afbd3cf184844a50aa504e
[alignment]: https://github.com/Kreoh/kroog/blob/7ca670394ee85767b621ba46dd788b5b4b65a00f/docs/upstream/koog-1.3.0-alignment.md
[model-validation]: https://github.com/Kreoh/kroog/blob/7ca670394ee85767b621ba46dd788b5b4b65a00f/docs/latest-models-2026-09.md
[release-notes]: https://github.com/Kreoh/kroog/blob/7ca670394ee85767b621ba46dd788b5b4b65a00f/CHANGELOG.md
[cache-policy]: https://github.com/Kreoh/kroog/blob/7ca670394ee85767b621ba46dd788b5b4b65a00f/prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/cache/PromptCachePolicy.kt
[catalogue]: https://github.com/Kreoh/kroog/blob/7ca670394ee85767b621ba46dd788b5b4b65a00f/prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/models/ModelCatalogue.kt
[stable-pom]: https://repo.maven.apache.org/maven2/com/kreoh/kroog/koog-agents-jvm/1.1.1-kroog.15/koog-agents-jvm-1.1.1-kroog.15.pom
[beta-pom]: https://repo.maven.apache.org/maven2/com/kreoh/kroog/koog-agents-additions-jvm/1.1.1-beta-kroog.15/koog-agents-additions-jvm-1.1.1-beta-kroog.15.pom
[openai-models]: https://github.com/Kreoh/kroog/blob/7ca670394ee85767b621ba46dd788b5b4b65a00f/prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIModels.kt
[anthropic-models]: https://github.com/Kreoh/kroog/blob/7ca670394ee85767b621ba46dd788b5b4b65a00f/prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicModels.kt
[google-models]: https://github.com/Kreoh/kroog/blob/7ca670394ee85767b621ba46dd788b5b4b65a00f/prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/GoogleModels.kt
[bedrock-models]: https://github.com/Kreoh/kroog/blob/7ca670394ee85767b621ba46dd788b5b4b65a00f/prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/BedrockModels.kt
[correctness-imports]: https://github.com/Kreoh/kroog/blob/7ca670394ee85767b621ba46dd788b5b4b65a00f/docs/upstream/correctness-imports.md
