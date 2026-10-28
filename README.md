# Kroog

[![Kotlin Stable](https://kotl.in/badges/stable.svg)](https://kotlinlang.org/docs/components-stability.html)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.10-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![GitHub licence](https://img.shields.io/github/license/Kreoh/kroog)](LICENSE.txt)

Kroog is a JVM framework for building AI agents in Kotlin and Java. Define tools, connect a language model and build workflows that can call external services, retain conversation history and stream responses.

It is Kreoh's fork of [Koog](https://github.com/JetBrains/koog), based on Koog 1.3.0, with additional model support and improvements to provider replay, prompt caching and token accounting. Kotlin packages remain under `ai.koog`; Maven coordinates use `com.kreoh.kroog`.

## What you can build

- **Agents with tools:** define typed tool arguments and results, call external APIs and use MCP tools.
- **Agent workflows:** combine model calls, tool execution and branching in Kotlin graph workflows, with streaming and parallel tool calls.
- **Agents with memory:** retain conversation history, compress long conversations and retrieve knowledge through embeddings and RAG.
- **Applications with several providers:** select models through a shared catalogue and preserve provider-specific reasoning, tool calls and results across requests.
- **JVM services:** integrate agents with Spring Boot, Ktor and Bedrock AgentCore Runtime, or expose them through ACP.
- **Observable execution:** trace model requests and tool calls through OpenTelemetry, Langfuse and W&B Weave.

Kroog also provides prompt cache policies, separate cache-read and cache-write token counts, batch embeddings and reusable skills with immutable reference files. See the [skills guide](skills/README.md) for loading skills and configuring trusted filesystem sources.

## Installation

Use JDK 17 or later and Kotlin 2.3.10 or later. Kroog publishes JVM artefacts only.

The examples below use the published `1.3.0-kroog.2` release. The stable `koog-agents-jvm` dependency supplies the main agent APIs; `koog-agents-additions-jvm` adds optional beta modules. Gradle needs the POM-based repository configuration shown below to resolve these JVM publications.

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
    implementation("com.kreoh.kroog:koog-agents-jvm:1.3.0-kroog.2")
    // Optional beta modules:
    implementation("com.kreoh.kroog:koog-agents-additions-jvm:1.3.0-beta-kroog.2")
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
    implementation 'com.kreoh.kroog:koog-agents-jvm:1.3.0-kroog.2'
    // Optional beta modules:
    implementation 'com.kreoh.kroog:koog-agents-additions-jvm:1.3.0-beta-kroog.2'
}
```

### Maven

Maven Central is enabled by default. Add these entries inside `dependencies` in `pom.xml`:

```xml
<dependency>
    <groupId>com.kreoh.kroog</groupId>
    <artifactId>koog-agents-jvm</artifactId>
    <version>1.3.0-kroog.2</version>
</dependency>
<!-- Optional beta modules: -->
<dependency>
    <groupId>com.kreoh.kroog</groupId>
    <artifactId>koog-agents-additions-jvm</artifactId>
    <version>1.3.0-beta-kroog.2</version>
</dependency>
```

## Quickstart

Set `OPENAI_API_KEY` in your environment, then run:

```kotlin
import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
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

## Providers and models

Kroog supports OpenAI, Anthropic, Google, Amazon Bedrock, Azure OpenAI, DeepSeek, OpenRouter and Ollama. Model capabilities depend on the provider API used to access them.

| Model family | Integration |
| --- | --- |
| OpenAI GPT-6 Astra, Sol and Luna; GPT-6.1 Sol and Terra | OpenAI Responses, with selected models also supported through Bedrock and compatible APIs |
| OpenAI Codex models | Codex Responses with an explicitly selected account |
| Anthropic Claude Opus 5.5 and Sonnet 5.5 | Anthropic Messages, Vertex AI and Bedrock |
| Google Gemini 3.8 Flash and Gemini 4 Argon | Google client; Argon uses Vertex AI |
| DeepSeek | DeepSeek client and compatible APIs |
| Other hosted and local models | OpenRouter, Ollama and provider-specific clients |

Use `ModelCatalogue.find(id, api)` to look up token limits, reasoning settings, sampling restrictions, supported MIME types, structured output and hosted execution for a specific model and API. See the [model catalogue](prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/models/ModelCatalogue.kt) for the complete list.

For Codex account selection, supported models and Gemini 4 Argon configuration, see the [Codex and Argon guide](docs/codex-and-argon.md).

## Documentation

- [Koog documentation](https://docs.koog.ai/) for the framework's concepts and APIs.
- [Skills](skills/README.md) for reusable instructions and reference files.
- [Changelog](CHANGELOG.md) for release changes and migration notes.
- [Versioning](VERSIONING.md) for Kroog's release numbering.
- [Testing](TESTING.md) and [publishing](PUBLISHING.md) for contributors.

## Contributing

For changes that apply to Koog generally, contribute to [upstream Koog](https://github.com/JetBrains/koog). Issues and pull requests specific to Kroog are welcome here.

## Licence

Kroog is licensed under the [Apache 2.0 licence](LICENSE.txt).
