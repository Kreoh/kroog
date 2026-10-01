# Versioning

Kroog uses `UPSTREAM_VERSION-kroog.REVISION`. Its major, minor and patch
components match the latest incorporated Koog release. Incorporating a newer
Koog version resets the Kroog revision to `1`; subsequent Kroog releases on
the same upstream baseline increment that revision. With Koog `1.3.0`
incorporated, the current release preparation is `1.3.0-kroog.1`.
Never retain an older base version after an upstream version change.
The configured stable version comes from `gradle.properties`; its annotated
release tag must match exactly, without a `v` prefix. See
[Publishing Kroog](https://github.com/Kreoh/kroog/blob/master/PUBLISHING.md)
for release preparation, tagging, CI dispatch and Central Portal approval.

The configured Kroog release version is `1.3.0-kroog.1`, incorporating upstream Koog 1.3.0. Maven Central publication is a separate release step. Coordinates use `com.kreoh.kroog`; Kotlin JVM publications append `-jvm` to the module name. Pure JVM publications keep the module name.

The beta transform inserts `-beta` before the fork suffix: `1.3.0-beta-kroog.1`. Snapshot builds append `-SNAPSHOT` to both versions. The policies below describe upstream semantic versioning; the module table gives the corresponding Kroog source-line versions. See the repository's `PUBLISHING.md` for the publication inventory and POM-based resolution.

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

Some modules are considered experimental and published with a `-beta` version suffix (e.g., `1.3.0-beta-kroog.1`) rather than the standard `X.Y.Z`. A module may be beta for one of several reasons:

- **External integrations**: the underlying LLM provider API or external framework (e.g., Spring AI) may itself be unstable or subject to frequent or expected change.
- **Experimental functionality**: the feature area is still being explored and the API shape may evolve (e.g., GOAP planning strategies).
- **Experimental protocols**: the module implements a protocol that is not yet stable itself (e.g., A2A, Kotlin MCP).

While every effort is made to keep beta modules stable, some API changes may occur across minor releases. Beta changes will not affect any stable module.

A stable module at version `X.Y.Z` is always compatible with a beta module at version `X.Y.Z-beta` (and vice versa). All modules can be updated in sync.

### Umbrella Modules

| Module | Version      | Contents |
|--------|--------------|----------|
| `koog-agents` | `1.3.0-kroog.1`      | All stable modules (transitive): recommended starting point |
| `koog-agents-additions` | `1.3.0-beta-kroog.1` | Most beta/experimental modules (except standalone external integrations) |

### Module Versions

This upstream module catalogue includes modules outside Kroog's JVM publication set. The exact publishable JVM modules are listed in `gradle/kroog-jvm-publications.txt` in the repository.

=== "Stable Modules (`1.3.0-kroog.1`)"
    
    | Module | Version |
    |--------|---------|
    | `agents` | `1.3.0-kroog.1` |
    | `agents-core` | `1.3.0-kroog.1` |
    | `agents-features` | `1.3.0-kroog.1` |
    | `agents-features-chat-history-jdbc` | `1.3.0-kroog.1` |
    | `agents-features-chat-memory-sql` | `1.3.0-kroog.1` |
    | `agents-features-event-handler` | `1.3.0-kroog.1` |
    | `agents-features-memory` | `1.3.0-kroog.1` |
    | `agents-features-opentelemetry` | `1.3.0-kroog.1` |
    | `agents-features-persistence-jdbc` | `1.3.0-kroog.1` |
    | `agents-features-snapshot` | `1.3.0-kroog.1` |
    | `agents-features-sql` | `1.3.0-kroog.1` |
    | `agents-features-tokenizer` | `1.3.0-kroog.1` |
    | `agents-features-trace` | `1.3.0-kroog.1` |
    | `agents-mcp-metadata` | `1.3.0-kroog.1` |
    | `agents-test` | `1.3.0-kroog.1` |
    | `agents-tools` | `1.3.0-kroog.1` |
    | `agents-utils` | `1.3.0-kroog.1` |
    | `embeddings` | `1.3.0-kroog.1` |
    | `embeddings-base` | `1.3.0-kroog.1` |
    | `embeddings-llm` | `1.3.0-kroog.1` |
    | `http-client` | `1.3.0-kroog.1` |
    | `http-client-core` | `1.3.0-kroog.1` |
    | `http-client-java` | `1.3.0-kroog.1` |
    | `http-client-ktor` | `1.3.0-kroog.1` |
    | `http-client-okhttp` | `1.3.0-kroog.1` |
    | `http-client-test` | `1.3.0-kroog.1` |
    | `koog-agents` | `1.3.0-kroog.1` |
    | `koog-spring-ai` | `1.3.0-kroog.1` |
    | `prompt` | `1.3.0-kroog.1` |
    | `prompt-cache` | `1.3.0-kroog.1` |
    | `prompt-cache-files` | `1.3.0-kroog.1` |
    | `prompt-cache-model` | `1.3.0-kroog.1` |
    | `prompt-executor` | `1.3.0-kroog.1` |
    | `prompt-executor-anthropic-client` | `1.3.0-kroog.1` |
    | `prompt-executor-bedrock-client` | `1.3.0-kroog.1` |
    | `prompt-executor-cached` | `1.3.0-kroog.1` |
    | `prompt-executor-clients` | `1.3.0-kroog.1` |
    | `prompt-executor-model` | `1.3.0-kroog.1` |
    | `prompt-executor-ollama-client` | `1.3.0-kroog.1` |
    | `prompt-executor-openai-client` | `1.3.0-kroog.1` |
    | `prompt-executor-openai-client-base` | `1.3.0-kroog.1` |
    | `prompt-executor-openrouter-client` | `1.3.0-kroog.1` |
    | `prompt-llm` | `1.3.0-kroog.1` |
    | `prompt-markdown` | `1.3.0-kroog.1` |
    | `prompt-model` | `1.3.0-kroog.1` |
    | `prompt-processor` | `1.3.0-kroog.1` |
    | `prompt-structure` | `1.3.0-kroog.1` |
    | `prompt-tokenizer` | `1.3.0-kroog.1` |
    | `prompt-xml` | `1.3.0-kroog.1` |
    | `rag-base` | `1.3.0-kroog.1` |
    | `serialization` | `1.3.0-kroog.1` |
    | `serialization-core` | `1.3.0-kroog.1` |
    | `serialization-jackson` | `1.3.0-kroog.1` |
    | `serialization-test` | `1.3.0-kroog.1` |
    | `test-tck` | `1.3.0-kroog.1` |
    | `test-utils` | `1.3.0-kroog.1` |
    | `utils` | `1.3.0-kroog.1` |

=== "Beta Modules (`1.3.0-beta-kroog.1`)"
    
    | Module | Version |
    |--------|---------|
    | `a2a-client` | `1.3.0-beta-kroog.1` |
    | `a2a-core` | `1.3.0-beta-kroog.1` |
    | `a2a-server` | `1.3.0-beta-kroog.1` |
    | `a2a-test` | `1.3.0-beta-kroog.1` |
    | `a2a-test-server-tck` | `1.3.0-beta-kroog.1` |
    | `a2a-transport-client-jsonrpc-http` | `1.3.0-beta-kroog.1` |
    | `a2a-transport-core-jsonrpc` | `1.3.0-beta-kroog.1` |
    | `a2a-transport-server-jsonrpc-http` | `1.3.0-beta-kroog.1` |
    | `agents-ext` | `1.3.0-beta-kroog.1` |
    | `agents-features-a2a-client` | `1.3.0-beta-kroog.1` |
    | `agents-features-a2a-core` | `1.3.0-beta-kroog.1` |
    | `agents-features-a2a-server` | `1.3.0-beta-kroog.1` |
    | `agents-features-acp` | `1.3.0-beta-kroog.1` |
    | `agents-features-chat-history-aws` | `1.3.0-beta-kroog.1` |
    | `agents-features-longterm-memory` | `1.3.0-beta-kroog.1` |
    | `agents-features-longterm-memory-aws` | `1.3.0-beta-kroog.1` |
    | `agents-mcp` | `1.3.0-beta-kroog.1` |
    | `agents-mcp-server` | `1.3.0-beta-kroog.1` |
    | `agents-planner` | `1.3.0-beta-kroog.1` |
    | `koog-agents-additions` | `1.3.0-beta-kroog.1` |
    | `koog-ktor` | `1.3.0-beta-kroog.1` |
    | `koog-spring-ai-common` | `1.3.0-beta-kroog.1` |
    | `koog-spring-ai-starter-chat-memory` | `1.3.0-beta-kroog.1` |
    | `koog-spring-ai-starter-model-chat` | `1.3.0-beta-kroog.1` |
    | `koog-spring-ai-starter-model-embedding` | `1.3.0-beta-kroog.1` |
    | `koog-spring-ai-starter-vector-store` | `1.3.0-beta-kroog.1` |
    | `koog-spring-boot-starter` | `1.3.0-beta-kroog.1` |
    | `prompt-cache-redis` | `1.3.0-beta-kroog.1` |
    | `prompt-executor-dashscope-client` | `1.3.0-beta-kroog.1` |
    | `prompt-executor-deepseek-client` | `1.3.0-beta-kroog.1` |
    | `prompt-executor-google-client` | `1.3.0-beta-kroog.1` |
    | `prompt-executor-litert-client` | `1.3.0-beta-kroog.1` |
    | `prompt-executor-llms-all` | `1.3.0-beta-kroog.1` |
    | `prompt-executor-mistralai-client` | `1.3.0-beta-kroog.1` |
    | `rag-vector` | `1.3.0-beta-kroog.1` |

`skills` is a standalone beta module (`1.3.0-beta-kroog.1`), excluded from the stable `koog-agents` umbrella and included in the JVM publication of `koog-agents-additions`. It can also be added explicitly as `com.kreoh.kroog:skills-jvm`.
