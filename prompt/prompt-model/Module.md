# Module prompt-model

A core module that defines data models and parameters for controlling language model behavior.

### Overview

The prompt-model module provides essential data structures for configuring and controlling language model interactions. It includes the `LLMParams` class which encapsulates parameters like temperature, speculation, schema, and tool choice options. The module also defines structured data schemas and tool choice behaviors that can be used to customize language model responses.

### Maintaining the model catalogue

`ModelCatalogue` defines the semantic profiles consumed by applications for capability and limit discovery.
Its entries are maintained separately from provider model definitions. Adding a provider constant or registering
an `LLModel` does not add a catalogue entry.

Model changes must update the canonical profile, aliases and provider API compatibility together with lookup and
profile regression tests. New deployment names use the existing semantic profile where appropriate.
Verify input and output limits, reasoning, temperature restrictions, MIME types, structured output and hosted
execution against provider evidence. Resolve any profile or route restriction the contract cannot represent
before declaring support complete.

Update `ModelCatalogueTest` and its normalised fixture, then run `:prompt:prompt-model:jvmTest` and the affected
provider JVM tests. Follow the mandatory model support gate in [TESTING.md](../../TESTING.md) and the staged consumer
checks in [PUBLISHING.md](../../PUBLISHING.md) before release.

### Selecting a model profile

Use `ModelCatalogue.find(semanticId, providerApi)` when selecting a provider route. It returns null for an unknown
model or an unsupported or undeclared route, and applies route restrictions such as Astra's unavailable structured
output on Bedrock Converse. The one-argument lookup returns the semantic profile.

The catalogue includes GPT-6 Sol and Luna, GPT-6.1 Sol, Claude Opus and Sonnet 5.5, Gemini 3.8 Flash,
DeepSeek Flash and V4 Pro, the three supported Gemma 4 sizes and Nova 2 Multimodal Embeddings.
DeepSeek's legacy Flash IDs are explicit aliases of `deepseek-flash`.
Bedrock deployment IDs remain separate: `google.gemma-4-31b` selects semantic `gemma-4-31b`, and
`amazon.nova-2-multimodal-embeddings-v1:0` selects semantic `nova-2-multimodal-embeddings`.
Nova uses `ProviderApi.BEDROCK_EMBEDDINGS`; the current Kroog embedding API accepts text only.

For text models, `outputTokenLimit` is null when the independent output ceiling is unknown.
Gemma 4 uses this representation because AWS documents its shared context window without a separate output ceiling.
Its legacy `maxOutputTokens` value is zero; consumers must not turn that sentinel into a zero-token generation budget.
Embedding profiles have zero output tokens because they return vectors. Input ceilings and shared context windows
still require room for generated output.

### Example of usage

```kotlin
// Create parameters for a deterministic response
val deterministicParams = LLMParams(
    temperature = 0.0
)

// Create parameters with a schema for structured output
val structuredParams = LLMParams(
    temperature = 0.7,
    schema = LLMParams.Schema.JSON.Simple(
        name = "PersonInfo",
        schema = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "age" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                "skills" to JsonObject(mapOf(
                    "type" to JsonPrimitive("array"),
                    "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                ))
            ))
        ))
    )
)

// Configure the model to use a specific tool
val toolParams = LLMParams(
    toolChoice = LLMParams.ToolChoice.Named("calculator")
)

// Configure the model to not use any tools
val noToolsParams = LLMParams(
    toolChoice = LLMParams.ToolChoice.None
)
```

### Response token usage

`ResponseMetaInfo` describes one model request. `inputTokensCount` includes cached input and input written to cache. `outputTokensCount` includes reasoning tokens. The optional `cacheReadTokensCount`, `cacheWriteTokensCount` and `reasoningTokensCount` fields are subsets of those totals and must never be added again. Missing counts remain `null`; reported zero values remain zero.

OpenAI Chat Completions and Responses retain their inclusive input and output counts. Anthropic and Bedrock Converse add cache reads and writes to ordinary input. Gemini keeps cached prompt input inclusive, adds tool-result prompt tokens to input when reported, and adds separately reported thinking tokens to candidate output. The tool-result treatment follows Vertex usage metadata, which includes those prompts in request usage.

When both normalised categories are known, total usage is their sum. A conflicting Gemini total remains unknown because its categories cannot be reconciled. When categories are incomplete, OpenAI and Gemini retain a reported total without inferring missing input or output. Anthropic and Bedrock retain their inclusive provider output without inventing a reasoning breakdown. Bedrock's SDK requires ordinary input and output whenever usage is present.

Streaming clients retain earlier usage fields when later events omit them and replace cumulative snapshots. Complete and incomplete OpenAI Responses terminal events retain usable usage. Failed Responses events retain the existing failure handling. A response's usage is separate from accounting accumulated across a tool loop. Provider-managed tool execution may report usage for the whole provider request; it does not expose a separate measurement for each internal model call.

Cache fields previously stored in generic Anthropic and Bedrock metadata have moved to the typed fields. The Kotlin data-class constructor, copy and default-argument signatures change, so consumers must rebuild against the coordinated Kroog release. Existing serialised responses without the new optional fields remain readable; old generic cache keys are not migrated into typed counts.


### Streamed tool arguments

`StreamFrameFlowBuilder` enriches tool-call deltas with the resolved call ID, name, index and provider item ID.
Delta content contains only newly received argument text. Fragments received before both a non-blank ID and
name are known remain queued per call, then emit once in their original order. Identical consecutive fragments
remain distinct. Late provider item IDs enrich future events without replaying prior text.

A call that remains unresolved at an existing completion or flush boundary emits its normal
`ToolCallComplete` frame without anonymous argument deltas or invented IDs. Completion still carries the
assembled arguments, or the existing `{}` default when no argument text was supplied. Consumers must not
append completion content to an argument buffer that already contains the deltas.

Mocked JVM coverage verifies Anthropic, Claude on Vertex, Bedrock Converse, Bedrock's Anthropic adapter,
OpenAI Chat Completions, DeepSeek and DashScope. OpenAI Responses retains its separate canonical identity
handling. Google serialises parsed function-call objects; identity enrichment does not create incremental
argument fragments, and calls without a provider ID remain available through completion.
