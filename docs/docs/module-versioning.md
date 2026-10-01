# Versioning

Kroog versions advance independently of upstream Koog. On the current release
line, revision `1.1.1-kroog.15` follows `1.1.1-kroog.14`. Incorporating
upstream Koog 1.3.0 does not change the Kroog base version or reset the revision.
The configured stable version comes from `gradle.properties`; its annotated
release tag must match exactly, without a `v` prefix. See
[Publishing Kroog](https://github.com/Kreoh/kroog/blob/master/PUBLISHING.md)
for release preparation, tagging, CI dispatch and Central Portal approval.

The configured Kroog release version is `1.1.1-kroog.15`, incorporating upstream Koog 1.3.0. Maven Central publication is a separate release step. Coordinates use `com.kreoh.kroog`; Kotlin JVM publications append `-jvm` to the module name. Pure JVM publications keep the module name.

The beta transform inserts `-beta` before the fork suffix: `1.1.1-beta-kroog.15`. Snapshot builds append `-SNAPSHOT` to both versions. The policies below describe upstream semantic versioning; the module table gives the corresponding Kroog source-line versions. See the repository's `PUBLISHING.md` for the publication inventory and POM-based resolution.

Upstream Koog follows [Semantic Versioning](https://semver.org/) with the format `X.Y.Z` (e.g., `1.3.0`).

The framework is API-stable: once a public API is released, it will not be broken without a major version bump.

## Version Components

| Component | Name    | Format | Meaning |
|-----------|---------|--------|---------|
| `X`       | Major   | `X.y.z` | Breaking changes to existing APIs |
| `Y`       | Minor   | `x.Y.z` | New API additions and deprecations; all existing APIs continue to work |
| `Z`       | Bugfix  | `x.y.Z` | Bug fixes only; no API changes |

### Major (`X`)

- May introduce breaking changes to existing APIs.
- Old APIs may be removed.
- A migration guide will be provided.
- Released at most once per year.

### Minor (`Y`)

- May add new APIs.
- May deprecate existing APIs (with replacements provided), but deprecated APIs remain functional.
- No breaking changes: all code that compiled against the previous minor version continues to compile and work.
- Released at most once per month.

### Bugfix (`Z`)

- Contains bug fixes only.
- No API additions, removals, or deprecations.
- Released at most once per week.

## Deprecation Policy

APIs deprecated in a minor release (`Y`) will remain available until at least the next major release (`X`).
Deprecation warnings will indicate the recommended replacement.

## Stable and Beta Modules

Some modules are considered experimental and published with a `-beta` version suffix (e.g., `1.1.1-beta-kroog.15`) rather than the standard `X.Y.Z`. A module may be beta for one of several reasons:

- **External integrations**: the underlying LLM provider API or external framework (e.g., Spring AI) may itself be unstable or subject to frequent or expected change.
- **Experimental functionality**: the feature area is still being explored and the API shape may evolve (e.g., GOAP planning strategies).
- **Experimental protocols**: the module implements a protocol that is not yet stable itself (e.g., A2A, Kotlin MCP).

While every effort is made to keep beta modules stable, some API changes may occur across minor releases. Beta changes will not affect any stable module.

A stable module at version `X.Y.Z` is always compatible with a beta module at version `X.Y.Z-beta` (and vice versa). All modules can be updated in sync.

### Umbrella Modules

| Module | Version      | Contents |
|--------|--------------|----------|
| `koog-agents` | `1.1.1-kroog.15`      | All stable modules (transitive): recommended starting point |
| `koog-agents-additions` | `1.1.1-beta-kroog.15` | Most beta/experimental modules (except standalone external integrations) |

### Module Versions

This upstream module catalogue includes modules outside Kroog's JVM publication set. The exact publishable JVM modules are listed in `gradle/kroog-jvm-publications.txt` in the repository.

=== "Stable Modules (`1.1.1-kroog.15`)"
    
    | Module | Version |
    |--------|---------|
    | `agents` | `1.1.1-kroog.15` |
    | `agents-core` | `1.1.1-kroog.15` |
    | `agents-features` | `1.1.1-kroog.15` |
    | `agents-features-chat-history-jdbc` | `1.1.1-kroog.15` |
    | `agents-features-chat-memory-sql` | `1.1.1-kroog.15` |
    | `agents-features-event-handler` | `1.1.1-kroog.15` |
    | `agents-features-memory` | `1.1.1-kroog.15` |
    | `agents-features-opentelemetry` | `1.1.1-kroog.15` |
    | `agents-features-persistence-jdbc` | `1.1.1-kroog.15` |
    | `agents-features-snapshot` | `1.1.1-kroog.15` |
    | `agents-features-sql` | `1.1.1-kroog.15` |
    | `agents-features-tokenizer` | `1.1.1-kroog.15` |
    | `agents-features-trace` | `1.1.1-kroog.15` |
    | `agents-mcp-metadata` | `1.1.1-kroog.15` |
    | `agents-test` | `1.1.1-kroog.15` |
    | `agents-tools` | `1.1.1-kroog.15` |
    | `agents-utils` | `1.1.1-kroog.15` |
    | `embeddings` | `1.1.1-kroog.15` |
    | `embeddings-base` | `1.1.1-kroog.15` |
    | `embeddings-llm` | `1.1.1-kroog.15` |
    | `http-client` | `1.1.1-kroog.15` |
    | `http-client-core` | `1.1.1-kroog.15` |
    | `http-client-java` | `1.1.1-kroog.15` |
    | `http-client-ktor` | `1.1.1-kroog.15` |
    | `http-client-okhttp` | `1.1.1-kroog.15` |
    | `http-client-test` | `1.1.1-kroog.15` |
    | `koog-agents` | `1.1.1-kroog.15` |
    | `koog-spring-ai` | `1.1.1-kroog.15` |
    | `prompt` | `1.1.1-kroog.15` |
    | `prompt-cache` | `1.1.1-kroog.15` |
    | `prompt-cache-files` | `1.1.1-kroog.15` |
    | `prompt-cache-model` | `1.1.1-kroog.15` |
    | `prompt-executor` | `1.1.1-kroog.15` |
    | `prompt-executor-anthropic-client` | `1.1.1-kroog.15` |
    | `prompt-executor-bedrock-client` | `1.1.1-kroog.15` |
    | `prompt-executor-cached` | `1.1.1-kroog.15` |
    | `prompt-executor-clients` | `1.1.1-kroog.15` |
    | `prompt-executor-model` | `1.1.1-kroog.15` |
    | `prompt-executor-ollama-client` | `1.1.1-kroog.15` |
    | `prompt-executor-openai-client` | `1.1.1-kroog.15` |
    | `prompt-executor-openai-client-base` | `1.1.1-kroog.15` |
    | `prompt-executor-openrouter-client` | `1.1.1-kroog.15` |
    | `prompt-llm` | `1.1.1-kroog.15` |
    | `prompt-markdown` | `1.1.1-kroog.15` |
    | `prompt-model` | `1.1.1-kroog.15` |
    | `prompt-processor` | `1.1.1-kroog.15` |
    | `prompt-structure` | `1.1.1-kroog.15` |
    | `prompt-tokenizer` | `1.1.1-kroog.15` |
    | `prompt-xml` | `1.1.1-kroog.15` |
    | `rag-base` | `1.1.1-kroog.15` |
    | `serialization` | `1.1.1-kroog.15` |
    | `serialization-core` | `1.1.1-kroog.15` |
    | `serialization-jackson` | `1.1.1-kroog.15` |
    | `serialization-test` | `1.1.1-kroog.15` |
    | `test-tck` | `1.1.1-kroog.15` |
    | `test-utils` | `1.1.1-kroog.15` |
    | `utils` | `1.1.1-kroog.15` |

=== "Beta Modules (`1.1.1-beta-kroog.15`)"
    
    | Module | Version |
    |--------|---------|
    | `a2a-client` | `1.1.1-beta-kroog.15` |
    | `a2a-core` | `1.1.1-beta-kroog.15` |
    | `a2a-server` | `1.1.1-beta-kroog.15` |
    | `a2a-test` | `1.1.1-beta-kroog.15` |
    | `a2a-test-server-tck` | `1.1.1-beta-kroog.15` |
    | `a2a-transport-client-jsonrpc-http` | `1.1.1-beta-kroog.15` |
    | `a2a-transport-core-jsonrpc` | `1.1.1-beta-kroog.15` |
    | `a2a-transport-server-jsonrpc-http` | `1.1.1-beta-kroog.15` |
    | `agents-ext` | `1.1.1-beta-kroog.15` |
    | `agents-features-a2a-client` | `1.1.1-beta-kroog.15` |
    | `agents-features-a2a-core` | `1.1.1-beta-kroog.15` |
    | `agents-features-a2a-server` | `1.1.1-beta-kroog.15` |
    | `agents-features-acp` | `1.1.1-beta-kroog.15` |
    | `agents-features-chat-history-aws` | `1.1.1-beta-kroog.15` |
    | `agents-features-longterm-memory` | `1.1.1-beta-kroog.15` |
    | `agents-features-longterm-memory-aws` | `1.1.1-beta-kroog.15` |
    | `agents-mcp` | `1.1.1-beta-kroog.15` |
    | `agents-mcp-server` | `1.1.1-beta-kroog.15` |
    | `agents-planner` | `1.1.1-beta-kroog.15` |
    | `koog-agents-additions` | `1.1.1-beta-kroog.15` |
    | `koog-ktor` | `1.1.1-beta-kroog.15` |
    | `koog-spring-ai-common` | `1.1.1-beta-kroog.15` |
    | `koog-spring-ai-starter-chat-memory` | `1.1.1-beta-kroog.15` |
    | `koog-spring-ai-starter-model-chat` | `1.1.1-beta-kroog.15` |
    | `koog-spring-ai-starter-model-embedding` | `1.1.1-beta-kroog.15` |
    | `koog-spring-ai-starter-vector-store` | `1.1.1-beta-kroog.15` |
    | `koog-spring-boot-starter` | `1.1.1-beta-kroog.15` |
    | `prompt-cache-redis` | `1.1.1-beta-kroog.15` |
    | `prompt-executor-dashscope-client` | `1.1.1-beta-kroog.15` |
    | `prompt-executor-deepseek-client` | `1.1.1-beta-kroog.15` |
    | `prompt-executor-google-client` | `1.1.1-beta-kroog.15` |
    | `prompt-executor-litert-client` | `1.1.1-beta-kroog.15` |
    | `prompt-executor-llms-all` | `1.1.1-beta-kroog.15` |
    | `prompt-executor-mistralai-client` | `1.1.1-beta-kroog.15` |
    | `rag-vector` | `1.1.1-beta-kroog.15` |

`skills` is a standalone beta module (`1.1.1-beta-kroog.15`), excluded from the stable `koog-agents` umbrella and included in the JVM publication of `koog-agents-additions`. It can also be added explicitly as `com.kreoh.kroog:skills-jvm`.
