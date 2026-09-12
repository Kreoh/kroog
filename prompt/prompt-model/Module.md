# Module prompt-model

A core module that defines data models and parameters for controlling language model behavior.

### Overview

The prompt-model module provides essential data structures for configuring and controlling language model interactions. It includes the `LLMParams` class which encapsulates parameters like temperature, speculation, schema, and tool choice options. The module also defines structured data schemas and tool choice behaviors that can be used to customize language model responses.

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
