# Kroog correctness imports

These changes adapt open JetBrains Koog pull requests to Kroog's current JVM consumers.
Each head was pinned during inspection. The source links and authors remain recorded here because
Kroog adapts the patches rather than merging the upstream commits unchanged.

## Small imports

| PR | Change | Pinned head | Author |
| --- | --- | --- | --- |
| [2256](https://github.com/JetBrains/koog/pull/2256) | Nested required tool parameters | `94dd3440e16320fbdc7d21a57a23484c2e09cb91` | Aditya Nikam |
| [2147](https://github.com/JetBrains/koog/pull/2147) | Gemini calls without arguments | `e31d2dfef643a207bf5bb98bccf07d537dc98b7f` | Takuya Akamatsu |
| [2135](https://github.com/JetBrains/koog/pull/2135) | Prioritise tool calls in mixed responses | `82407e5422471b07ad3400513d381921e27415e0` | jewoodev |
| [2141](https://github.com/JetBrains/koog/pull/2141) | Redis expiry regression fixture | `ddaf50e6d2847fb2acb20ba886a20c5b070a6ab3` | sktjpg |
| [2237](https://github.com/JetBrains/koog/pull/2237) | Annotated JSON discriminator | `91f797ea3db5e5e72cc65fbac3d8c4314c9ed2be` | Shanto Islam |
| [2276](https://github.com/JetBrains/koog/pull/2276) | Spring finish reason and usage metadata | `f32aa6ff1edb76b87da63ffb7e2b6e7e8850580a` | Ahmed Elgamal |
| [2114](https://github.com/JetBrains/koog/pull/2114) | Gemini safety feedback | `78475bba7f85a95023b71849ce07503037340098` | Ilya Nemtsev |
| [1964](https://github.com/JetBrains/koog/pull/1964) | Google credentials in request headers | `a54d9081022ae7f059cfc463d292ea8b1cd6663b` | Briliantov Vadim |

## Careful adaptations

| PR | Change | Pinned head | Author |
| --- | --- | --- | --- |
| [2251](https://github.com/JetBrains/koog/pull/2251) | Keep tool calls and results paired in memory windows | `7a9a7f87153e7d414afd5ced90700d5cc88a6112` | raseln |
| [1843](https://github.com/JetBrains/koog/pull/1843) | Current system messages take precedence | `4fe08d36b26d2e9df6f5bc52b96dffb8a4b51fd8` | Renanse |
| [2250](https://github.com/JetBrains/koog/pull/2250) | Isolate contexts for parallel tool execution | `b4bec66863c713ef94f8b7b65924ca347955bb61` | larkox |
| [2067](https://github.com/JetBrains/koog/pull/2067) | MCP unions and string constants | `fdd0f3dd6a9c1d67c456d4dc9450dc1d5d98becb` | jewoodev |
| [2215](https://github.com/JetBrains/koog/pull/2215) | Langfuse visible output and separate reasoning | `08cdd64678842a9ce85ea81208d5fa70cebb9a45` | OrdinarySF |
| [2219](https://github.com/JetBrains/koog/pull/2219) | Direct Anthropic model ID fallback | `fe375dd2eaa54f23d7f8075b20dd6cfe05c4108e` | alexcmd |

Kroog retains typed tool-pair matching, coroutine cancellation, managed execution privacy,
OpenTelemetry context propagation and strict Vertex model mapping. MCP unions retain reference
resolution and depth limits, and accept only string constants supported by the tool schema.

## Streamed history, cache serialisation and helpers

| Source | Change | Pinned head | Author |
| --- | --- | --- | --- |
| Kroog contract | Automatically record successfully collected streamed responses in active write sessions | Local adaptation | Kroog |
| Kroog contract | Explicit cache directive serializer modules, preserving concrete types | Local adaptation | Kroog |
| [2124](https://github.com/JetBrains/koog/pull/2124) | Batch embeddings with a compatible sequential default | `fa9b9f3691916462b3897d8063c1f08da1136e7d` | Ilya Nemtsev |
| [2097](https://github.com/JetBrains/koog/pull/2097) | Simple DeepSeek executor helpers | `77b9097c481ae52466f6b173e7d013eaaeca5009` | jewoodev |

The streamed tool-call identity fix and all three batches form one unpublished
`1.3.0-kroog.1` release. The [validation record](../releases/1.3.0-kroog.1-validation.md)
retains the historical checks and records combined release validation. Google emits parsed
function-call argument objects; the changes do not establish incremental argument support. Repeated
Google argument snapshots remain a separate adapter concern. No new model capabilities or limits
are declared by these imports.
