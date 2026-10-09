# Empty streamed tool arguments

The native stream builder in `1.3.0-kroog.1` completes a tool call with `{}` when
no argument fragments arrive. If one or more fragments arrive and their combined
content is exactly empty, it instead completes the call with an empty string.
`toMessageResponse()` then throws `JsonDecodingException` while parsing that
content as a JSON object.

The regression is reproducible offline against released commit
`afd487cfbe628ac327b79d26e9023a4c945a175a`:

```kotlin
buildStreamFrameFlow {
    emitToolCallDelta(id = "call_1", name = "list_files", index = 0)
    emitToolCallDelta(args = "", index = 0)
    tryEmitPendingToolCall()
    emitEnd()
}.toList().toMessageResponse()
```

Completion now treats absent and exactly empty accumulated arguments equally.
Argument deltas remain unchanged. Whitespace, incomplete JSON and malformed
non-empty content retain their existing validation failures. Empty fragments
around valid JSON preserve that JSON and each call's identity.

`StreamFrameFlowBuilderEmptyArgumentsTest` covers these cases, including two
interleaved calls. `StreamedArgumentIdentityTest` covers empty `partial_json`
fragments through both Anthropic clients using the existing fake HTTP fixture,
then checks completed frames and the collected message. No live provider stream
was captured, so this reproduction establishes the library defect without
identifying the contents of any particular live response.

## Validation

Before the production change, the three builder tests produced two expected
empty-JSON failures and one passing invalid-input test. The provider regression
also failed at the same collector. After the change, the affected JVM suites
passed: prompt-model had 294 passing tests and three existing skips; the
Anthropic client had 89 passing tests. Both JVM JAR tasks and ktlint checks
restricted to the three changed Kotlin files passed. The full module formatting
checks exposed existing violations in unchanged files, which remain untouched.

Kotlin 2.3.10 `AbiToolsV2` generated identical 3,709-line prompt-model JVM dumps
before and after the fix, using the repository's seven internal-API exclusions.
The released compiled dump already differs from the checked-in JVM API dump,
as documented in the 1.3.0 alignment audit. This change leaves that existing
mismatch unchanged. No aggregate ABI or non-JVM build task ran.

The fix is local and unreleased. Consumer adoption and live verification remain
separate work; no published artefact has been replaced.
