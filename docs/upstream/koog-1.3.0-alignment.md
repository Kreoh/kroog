# Koog 1.3.0 alignment audit

S0 establishes provenance and implementation boundaries. Product adoption and regression validation remain pending. This audit refines the frozen plan without changing release numbering.

## Immutable source

| Reference | Object |
| --- | --- |
| Incorporated baseline B | 10bba89b67929bab3617047a6a7c8d8cf3ff9185 |
| Starting Kroog H | 324d5d8a0ac93df85cd796e2c19f666422293e74 |
| Pinned upstream U | 3acc88cf8ce70b87d8afbd3cf184844a50aa504e |
| Common ancestor M | d76b12c7fb2bb7b60afa5e51620851861ad9ffb1 |
| Incorporating merge | 6843b0136f0a098c353163dcb8ae1364c53fa326, second parent B |

The controller fetched U and verified official git ls-remote upstream refs/tags/1.3.0 against U on 28 September 2026. The worker independently read its complete local tree and blobs. The immutable future synchronisation reference is [JetBrains Koog commit U](https://github.com/JetBrains/koog/commit/3acc88cf8ce70b87d8afbd3cf184844a50aa504e). Future imports compare against U and retain this audit's residual patches despite slice imports rather than a merge.

Starting HEAD was H, tracked tree clean, version 1.1.1-kroog.11. B is an ancestor of H, but is not an ancestor of U. There are 41 baseline-only commits. M-to-B changed 453 unique paths: 419 retain the exact B blob at U, while 34 overlap the upstream ledger. An absent ancestry commit is therefore not evidence of a reverted feature. Use two-tree B-to-U for adoption and the entire B-to-H delta for preservation. Never replay reverse baseline-only commits.

## Ledger conventions

Both complete ledgers are generated from git diff --raw --full-index --no-abbrev --no-renames B U (or B H), with full blob IDs from git ls-tree -r for B, U and H. Explicit absence is absent. There are no deletions or renames in either delta. Every final path is proposed and currently equals the new path. Upstream: 54 paths, 11 additions and 43 modifications. Fork: 245 paths.

The slice key defines exact membership, with no unassigned paths. Test source sets, resources and http-client-test/src/main are test ownership. Other source and build files are production ownership. The additional proposed skills files are listed below. Shared paths appear in both ledgers but are edited once.

B=U, U=H and B=H denote exact blob identity, not behavioural equivalence. Pending requires review and validation in the assigned slice. Final dispositions must become adopted exactly, adapted with named Kroog contract, superseded with equivalence evidence, upstream deletion applied with preservation evidence, or explicit exemption with reason. Record checkpoint and test evidence upon acceptance. No S0 row claims passing runtime or ABI validation.

X-ABI retains existing Android and KLIB dumps without regeneration or validation, as required by JVM-only scope. X-version retains Kroog coordinates and 1.1.1-kroog.11 without a release bump. X-local preserves repository guidance and ignore rules. Retained fork-only paths with B=U need no upstream import but still require their regression evidence.

## Bounded groups and order

The exact slice-membership table below provides production and test paths by reference to the full ledgers. These refinements must enter derived state before dispatch.

1. S1 preserves JVM publication, signing, BOMs and beta transformation while importing dependency prerequisites. Its exact umbrella paths include koog-agents/build.gradle.kts and koog-agents-additions/build.gradle.kts, plus integration-tests/build.gradle.kts. Retain the skills server convention, tools and JSON API dependencies and SnakeYAML 3.0.1. Add upstream API dependencies on rag-base, coroutines and serialisation core, plus logging and needed test dependencies. No new module root is required. Consumer compilation depends on S6.
2. S2 preserves prompt identities, parallel tool calls, signed reasoning, replay, generated files, inclusive usage and catalogue capabilities. There is zero U delta in prompt-model. Provider model changes stay in their bounded S4 groups so tests and model imports remain coherent.
3. R-http-fixtures precedes S3 and owns exactly the two http-client-test/src/main F rows. S3 preserves binary resource APIs, SSE and retry contracts; core, Java, Ktor and retry have zero U delta. R-openai-base owns the OpenAI base F rows and precedes S4a; its U delta is also empty.
4. S4a OpenAI, S4b Anthropic, S4c Google and S4d Bedrock retain their full fork contracts while adopting U rows. S4e-dashscope, S4e-deepseek, S4e-mistralai, S4e-ollama and S4e-openrouter are separate bounded provider checkpoints.
5. S5a preserves managed execution and environments: no U delta exists there. R-runtime preserves the AgentCore runtime test F row after its S1 build prerequisite; runtime has no U delta. S5b preserves compaction, paired exchanges, recent turns, container resets, handovers and usage. S5c imports Langfuse's two U rows and preserves hosted-execution presentation. R-ktor owns exactly the two model-parser F rows after S2 and has no U delta.
6. S6a imports upstream core and atomically wires strict parsing, secure discovery, snapshots and catalogue delegation. Advance the essential S6b security work and core S6c renderer work into S6a: an accepted checkpoint cannot introduce an unsafe default or competing implementation. S6b then adds further boundary verification. S6c completes typed loading and consumer evidence.
7. R-integration owns U skills integration tests, both fixtures and heavy-tests.yml, retaining F executor integration fixtures. Its build prerequisite belongs to S1. Compile JVM test sources; live-provider runs need credentials. S7-docs adopts exact U documentation paths with Kroog coordinate and version adaptations and retains F documentation and streaming example. S7 closes the JVM publication graph, followed by S8 ledger reconciliation.

No-op means no upstream import, not proof of runtime correctness. R-http-fixtures acceptance covers binary and resource-lifetime fixtures; R-openai-base covers retry, cache and replay primitives; R-ktor covers explicit catalogue resolution; R-runtime covers runtime request handling. Existing regression assertions require inspection before acceptance. Exact pinned input blobs are recorded in each row. No broad directory replacement is authorised.

## Actual release corrections

OpenAI #2220 retains internal OpenAIResponsesAPIResponse.instructions: List<Item>? and adds ItemListOrSingleStringSerializer. It accepts a response scalar string as a singleton list and continues serialising arrays. The planning inference that the property must become String is superseded by this pinned source. Import its scalar, array and null response regressions without an unrelated request-wire or public-type change.

Google adds cachedContentTokenCount metadata to streaming and ordinary responses. Preserve inclusive totals, structured cache subsets and missing-versus-zero semantics; do not add cache counts twice. Provider profiles coexist with later Kroog models and constraints. Langfuse changes belong to its adapter and test U rows and must retain hosted execution presentation.

U contains no source deletion. S0 approves no removal of local code. JVM dumps require reliable JVM-only comparison with compiled output. No ABI check ran and no binary compatibility claim has been accepted.

## Skills signatures and ownership

Pinned U provides ai.koog.skills.model.Skill; ai.koog.skills.discovery.discoverSkills; ai.koog.skills.prompt.generateSkillsPrompt; SkillsPromptFormat.XML, JSON and YML. There is no registry class or public parser hook.

The upstream model has name, description, location, optional license, compatibility, metadata and allowedTools. It has no body. Retain this seven-field constructor and serialised shape. Legacy ai.koog.skills.Skill(name, description, instructions) is a distinct package class and must retain its constructor, components, copy and metadata methods; a typealias would break JVM consumers.

discoverSkills is suspend and generic in Path. It accepts FileSystemProvider.ReadOnly<Path>, root strings, maxDepth=4, maxDirectories=2000, precedenceRule=LAST_FOUND, skillFileName, skippedDirectoryNames, skillNamePattern and warningLogger, returning List<model.Skill>. It traverses breadth-first. Its private line parser overwrites duplicate fields, ignores malformed lines, only warns for invalid names and directory mismatch, and uses replacement decoding. Traversal silently stops at its directory budget. These cannot become Kroog's unsafe fallback.

The formatter returns non-null text and includes location by default, with optional field flags. Legacy rendering returns a nullable compact JSON array containing only name and description. Keep both contracts. Upstream manual JSON escaping misses some U+0000 to U+001F controls and requires a regression-backed adaptation.

### Single canonical parser and captured bodies

Refactor the private parser in upstream discovery/SkillsDiscovery.kt into one internal parser contract returning ParsedSkillDocument(model.Skill, instructions), with structured diagnostics. Add common discovery/SkillDocumentParser.kt and a JVM actual implementation discovery/SkillDocumentParserJvm.kt. The existing server convention configures JVM only, so no non-JVM actual or compilation is required. Move the current strict SnakeYAML parser into that upstream-owned implementation; delete the lenient parser body. Legacy SkillParser.parse becomes a shim. Both public discovery and legacy parsing call the same strict parser with policy parameters, never alternate default parsers.

Use explicit policy profiles: legacy accepts name and description unless UnknownFieldPolicy permits other fields; upstream recognises its seven documented fields and validates optional strings and metadata string values. Preserve exact frontmatter delimiters, CRLF handling and bare-CR rejection, duplicate and recursive-key rejection, all alias rejection including scalar aliases, YAML code-point and nesting budgets, trimmed non-blank required strings, 64-character ASCII names and directory-name equality. Capture the trimmed body during the same read and parse. Legacy loading still rejects an empty body; upstream metadata-only discovery may allow one. Conversion into a loadable legacy value must validate the body.

Return internal records before collisions. Select metadata and body together, avoiding an auxiliary name map that could associate a winner with a losing body's instructions. In-memory legacy sources construct validated records directly. No load operation rereads the filesystem. Keep typed SkillException and SkillError mapping.

### Secure discovery session

FileSystemProvider.ReadOnly has metadata, list and readBytes but no descriptor lifecycle contract. Add a scoped session under upstream-owned discovery, implemented by discovery/SecureSkillFileSystemJvm.kt. It owns SecureDirectoryStream handles throughout traversal and reads, closing them on success, failure and cancellation. Tokens carry session identity and held parent descriptors. Relative attribute reads, child opens and byte channels use NOFOLLOW_LINKS. Refuse filesystems without secure directory streams. Preserve root-opening and race-injection hooks.

The public generic signature remains. Its JVM session factory recognises the standard JVMFileSystemProvider.ReadOnly and secures the configured roots itself, or accepts the internal secure adapter. Arbitrary providers need an explicitly verified session capability or a bounded immutable in-memory snapshot adapter. Unknown host-filesystem providers fail closed with a clear exception; there is no unrestricted readBytes fallback. Do not silently replace a caller's custom provider. Tests must prove stock JVM provider and generic in-memory usage. Document this security restriction on newly imported upstream APIs.

Move traversal, entry and directory budgeting, duplicate resolution and record construction into the upstream discovery engine. JvmFileSystemSkillSource becomes configuration and typed-error translation. Keep its public constructors, default direct-child mode and sorted roots. Upstream uses breadth-first recursive traversal and its root-inclusion rules through explicit traversal policy. Bound listing inside the secure adapter before allocating unbounded queues or child lists. Enforce root, directory, depth, file-byte and pre-deduplication skill budgets, plus description and instruction code-point limits. Retain strict UTF-8 REPORT decoding by default, explicit legacy charset selection and typed decoding failures.

Keep symlink and swap-race rejection against held descriptors, missing-root policy and redacted diagnostics. DISCLOSE remains opt-in. Avoid runCatching swallowing cancellation or security failures. Apply FAIL or SKIP_WITH_DIAGNOSTIC only to documented policy categories. A normalised-path check followed by an unsafe reopen is unacceptable.

### Snapshot, catalogue and compatibility

Add upstream-owned discovery/SkillSnapshot.kt for canonical record collection, ordering and duplicate selection, filling a gap in U. Public upstream discovery projects metadata; legacy SkillRegistry wraps the same snapshot and provides exact lookup and iteration. Preserve sequential source loading, deterministic source-local ordering, case sensitivity, aggregate budgets before deduplication, immutable copied lists and replacement reload. Retain no live source references.

Refactor upstream prompt/SkillsPrompt.kt around one structured catalogue representation. Legacy SkillCatalogueRenderer delegates to its metadata-only compact array projection, with location and optional fields disabled, final encoded code-point budgets and empty null. Remove the independent local object-building algorithm only after equivalence tests. Use kotlinx serialisation for complete JSON escaping. Preserve upstream envelopes, optional fields and non-null empty output.

LoadSkillTool keeps typed arguments, results and errors, exact snapshot lookup, collision policies and agent carry-forward. Public legacy descriptors remain through delegating shims. The new upstream provider capability restriction is an explicit security adaptation, not removal of a legacy feature. If any existing descriptor cannot survive, block that checkpoint for an evidenced migration decision.

### Exact S6a additions and acceptance

S6a owns all S6a ledger rows and these new production paths:

- skills/src/commonMain/kotlin/ai/koog/skills/discovery/SkillDocumentParser.kt
- skills/src/commonMain/kotlin/ai/koog/skills/discovery/SkillSnapshot.kt
- skills/src/jvmMain/kotlin/ai/koog/skills/discovery/SkillDocumentParserJvm.kt
- skills/src/jvmMain/kotlin/ai/koog/skills/discovery/SecureSkillFileSystemJvm.kt

Tests comprise the existing parser, filesystem, in-memory source, registry and catalogue F tests; U discovery and prompt tests; and proposed skills/src/jvmTest/kotlin/ai/koog/skills/discovery/SkillsCompatibilityTest.kt. Extend skills-api-consumer-test/src/main/kotlin/ai/koog/skills/consumer/SkillsApiConsumer.kt during S6a to compile both APIs, then revisit it in S6c.

S6a must prove canonical parser invocation, one-read body capture through collisions, stock JVM and in-memory provider use, unknown provider refusal, strict decoding, secure-session closure and unchanged legacy security tests. S6b adds boundary tests to those exact test files; production corrections require reviewed bounded scope. S6c owns typed loading and consumer rows plus further catalogue and prompt boundary tests. No checkpoint may defer a known security regression. Add skills JVM ABI evidence only through reliable generation and comparison; neither starting skills tree contains a JVM dump.

## Validation and residual risk

S0 runs read-only Git object, ancestry, full-index diff and tree-identity checks, with complete ledger counts. No builds, tests, publication, ABI generation or cross-platform tasks are required for this audit-only slice. The audit is the only authorised changed file.

Execution validation: S1 uses ./gradlew -p convention-plugin-ai test and affected module jvmJar and generatePomFileForJvmPublication. S2 uses :prompt:prompt-model:jvmTest. R-http-fixtures uses :http-client:http-client-test:compileKotlin. S3 uses :http-client:http-client-core:jvmTest, :http-client:http-client-java:jvmTest, :http-client:http-client-ktor:jvmTest and :prompt:prompt-executor:prompt-executor-clients:jvmTest. R-openai-base uses its module-qualified jvmTest. Each S4 provider uses :prompt:prompt-executor:prompt-executor-clients:prompt-executor-<provider>-client:jvmTest. Verify actual task existence and JVM scope before dispatch.

S5a uses managed-execution and agents-core jvmTest; S5b uses agents-core and agents-test jvmTest; S5c uses affected feature modules and prompt-tokenizer jvmTest. R-runtime uses :koog-bedrock-agentcore-runtime:test; R-ktor uses :koog-ktor:jvmTest. S6 uses ./gradlew :skills:jvmTest :skills-api-consumer-test:compileKotlin. R-integration requires inspection of its JVM task registration before compilation. Documentation receives path and reference review. S7 uses the frozen plan's local-only publication checks. Never run root builds, aggregate ABI tasks or Central Portal publication.

Regression names below are evidence anchors, not passing results. Remaining execution risks: secure session implementation and provider migration; body retention through collisions; formatter escaping; public descriptors; cache-token semantics; reliable JVM ABI tooling. No source-access blocker remains at S0.


## Complete upstream path ledger

| ID | Change | Old path | New and proposed final path | B blob | U blob | H blob | Slice | Disposition and evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| U001 | M | .github/workflows/heavy-tests.yml | .github/workflows/heavy-tests.yml | 336c13d0d237ad0509f47c65994b70f712e353b4 | 40c579ea38e94979abd9318c57ceaad346d4358f | 336c13d0d237ad0509f47c65994b70f712e353b4 | R-integration | Pending adoption with named slice contracts and history evidence; B=H |
| U002 | M | CHANGELOG.md | CHANGELOG.md | 10c5f2e32a09a9212c33194f006433675d92226b | 1517d1cbccde4d3865bc719f5d014379faaf32f5 | 10c5f2e32a09a9212c33194f006433675d92226b | S7-docs | Pending adoption with named slice contracts and history evidence; B=H |
| U003 | M | README.md | README.md | ff1aa023d2b26c60c00e04510e146685e6798a1c | e79edb4f58409a069aa3749d58418a413f39a510 | ff1aa023d2b26c60c00e04510e146685e6798a1c | S7-docs | Pending adoption with named slice contracts and history evidence; B=H |
| U004 | M | agents/agents-features/agents-features-opentelemetry/src/commonMain/kotlin/ai/koog/agents/features/opentelemetry/integration/langfuse/LangfuseSpanAdapter.kt | agents/agents-features/agents-features-opentelemetry/src/commonMain/kotlin/ai/koog/agents/features/opentelemetry/integration/langfuse/LangfuseSpanAdapter.kt | 411f156ca8ab20c634b28f478f35344dbc8963ad | 7e57ca1c9f602437e393a02f5c6cd81167005732 | 411f156ca8ab20c634b28f478f35344dbc8963ad | S5c | Adopted exact U blob; empty reasoning and finish_reason regressions passed; see S5c validation |
| U005 | M | agents/agents-features/agents-features-opentelemetry/src/jvmTest/kotlin/ai/koog/agents/features/opentelemetry/integration/langfuse/LangfuseSpanAdapterTest.kt | agents/agents-features/agents-features-opentelemetry/src/jvmTest/kotlin/ai/koog/agents/features/opentelemetry/integration/langfuse/LangfuseSpanAdapterTest.kt | 6bba962abe50abcf71ea24fd526dacabea1cebb5 | 16706418fe70936d19a775266ebd9c9d4bf311e1 | 6bba962abe50abcf71ea24fd526dacabea1cebb5 | S5c | Adopted exact U regression plus populated reasoning coverage; six Langfuse tests passed; see S5c validation |
| U006 | M | docs/docs/features/chat-memory/chat-agent-with-memory.md | docs/docs/features/chat-memory/chat-agent-with-memory.md | c0bdbbf3fe26c08263c358521d73723e3cf33647 | 29ba60eb5fec341c8530b602e99984361421f7fc | c0bdbbf3fe26c08263c358521d73723e3cf33647 | S7-docs | Pending adoption with named slice contracts and history evidence; B=H |
| U007 | M | docs/docs/module-versioning.md | docs/docs/module-versioning.md | 7b427083cb91f56eb989ab92024e65c4cc7e279f | 6157049731d3bcc848c1e54e567ca061dbc7cab3 | 7b427083cb91f56eb989ab92024e65c4cc7e279f | S7-docs | Pending adoption with named slice contracts and history evidence; B=H |
| U008 | A | absent | docs/docs/skills.md | absent | 19ff5ebf31d349416344215d8e1b407a253b3088 | absent | S7-docs | Pending adoption with named slice contracts and history evidence; B=H |
| U009 | M | docs/docs/snippets/quickstart-snippets.md | docs/docs/snippets/quickstart-snippets.md | 15c53138b5806ba3563ea208992bc90211398f38 | d60a48723802b1301011a21974217af4c7815018 | 15c53138b5806ba3563ea208992bc90211398f38 | S7-docs | Pending adoption with named slice contracts and history evidence; B=H |
| U010 | M | docs/docs/snippets/versioning-snippets.md | docs/docs/snippets/versioning-snippets.md | 3f38abfe561c6522f8e5f335c9aa255a2ce31ed5 | da8651749440de485d0a438bcf5803c52caec83b | 3f38abfe561c6522f8e5f335c9aa255a2ce31ed5 | S7-docs | Pending adoption with named slice contracts and history evidence; B=H |
| U011 | M | docs/mkdocs.yml | docs/mkdocs.yml | 84a3970540419f516c0063fdc3291e2667f4fb81 | 0de0f81e7a73395ebd42739bb7f282e0c11e5d28 | 84a3970540419f516c0063fdc3291e2667f4fb81 | S7-docs | Pending adoption with named slice contracts and history evidence; B=H |
| U012 | M | gradle.properties | gradle.properties | 7916e8236aa4119b69fe2fa1564f77b5d6c693a4 | 5c9c232e3b522560a96ed970806ec346b32eca72 | 6199cccbc1158cc63586a6c48abc44cabdd24cad | X-version | Explicit exemption: retain Kroog version and coordinates; diverged |
| U013 | M | integration-tests/build.gradle.kts | integration-tests/build.gradle.kts | 3eee32945a55ed960338c9efe5ec8dd3f6fee269 | 48be9f481449ee4c81b4d034902985e274a413d8 | 3eee32945a55ed960338c9efe5ec8dd3f6fee269 | S1 | Adopted exactly from U; S1 dependency and JVM metadata checks |
| U014 | A | absent | integration-tests/src/jvmTest/kotlin/ai/koog/integration/tests/skills/AIAgentSkillsIntegrationTest.kt | absent | aca9599d5d746d911eebe80ac9fbb3f5da08a6bf | absent | R-integration | Pending adoption with named slice contracts and history evidence; B=H |
| U015 | A | absent | integration-tests/src/jvmTest/resources/skills/arithmetic-evaluator/SKILL.md | absent | 5dfddcd87829f07493c1facfdeeea344b943d2ff | absent | R-integration | Pending adoption with named slice contracts and history evidence; B=H |
| U016 | A | absent | integration-tests/src/jvmTest/resources/skills/weather-retrieval/SKILL.md | absent | 46fc551563ed0038ffc88217c1afb46c775941eb | absent | R-integration | Pending adoption with named slice contracts and history evidence; B=H |
| U017 | M | koog-agents-additions/build.gradle.kts | koog-agents-additions/build.gradle.kts | 1ad4e8c7e888dcbd2ec9ec613c4bc50583787f0a | d9a55abe79f8a11c1c1f0953b7ae502b31845797 | 8c1d865da0d846390fc08a81d859687930c711f8 | S1 | Adapted with Kroog JVM-only skills exposure and retained managed-execution dependency; S1 |
| U018 | M | koog-agents/build.gradle.kts | koog-agents/build.gradle.kts | 478a18424904672e11576114d7a1a21e385cb89e | 59b939d9f0e1c64eae11fa0d8ebcaf50acd38e29 | 5fa96eccdd8d93b1f150c5ef5248eee6a8b7aaa4 | S1 | Adapted with Kroog stable/beta partition and retained managed-execution dependency; S1 |
| U019 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/api/android/prompt-executor-anthropic-client.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/api/android/prompt-executor-anthropic-client.api | 52e93e7f17de415ffcafd38a92a7388853283eb7 | ab3eaf11d4aa3f618108c1142e86d74e4888a274 | d5adcbae0693351c6d59d053da23c42c5feb3416 | X-ABI | Explicit exemption: non-JVM ABI; retain H; diverged |
| U020 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/api/jvm/prompt-executor-anthropic-client.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/api/jvm/prompt-executor-anthropic-client.api | 52e93e7f17de415ffcafd38a92a7388853283eb7 | ab3eaf11d4aa3f618108c1142e86d74e4888a274 | 2dd1f3d634b33d83e012087eba979732b00a7099 | S4b | Adapted: additive Sonnet_5 JVM field; complete compiled ABI matches regenerated JVM dump; see S4b validation |
| U021 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/api/prompt-executor-anthropic-client.klib.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/api/prompt-executor-anthropic-client.klib.api | f6e5a790d75d8c70e58999a57620ce69decd2a0d | 09e055b2375a7fc34e5998ac0656b4c06a82413a | 525a2ca1c8527eb620d6f3664ae8971c774a9c14 | X-ABI | Explicit exemption: non-JVM ABI; retain H; diverged |
| U022 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicModels.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicModels.kt | 0a312dfc3032c2fe84c076cdc660a1b6d43b021f | eff87b0b4ed34829b79d1c2e37b9d52b047a33fe | 10d8f0eb43d683343954218ade284ccefb49b2a7 | S4b | Adapted with named Kroog contract: adopt Sonnet_5 exactly; retain later Fable models and Opus temperature restrictions from bab8fc840; see S4b validation |
| U023 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicModelsTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicModelsTest.kt | 88317dc814bcd3ff9058031b61650f8138f1f8d8 | 4e69669d1d0ddd50673c3571111e28af232d3854 | 929c1dc046fcc20a8e61dc89422b83499b198835 | S4b | Adapted: all upstream schema and thinking assertions retained, with Sonnet_5 profile and request regressions; see S4b validation |
| U024 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/api/jvm/prompt-executor-bedrock-client.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/api/jvm/prompt-executor-bedrock-client.api | ec8e5269d14527c502896a2bd8bfac1c62f0296c | 29de9245957bf9200c0d572d1dcd0c54572c37a1 | bd642355e7e6ce52c6d3a4ba8d873df035534597 | S4d | Adapted with retained Kroog APIs; generated JVM dump, exactly two additive getters versus compiled baseline; pre-existing ordering drift resolved; S4d validation |
| U025 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/BedrockModels.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/BedrockModels.kt | e22e1fcf08d0b742273e5c3721f7e5ba3066e16c | 8049739064957eacad068f88f03fa877d6834551 | 22bb8b06c1fd5ec317244d3970d105c332f1826d | S4d | Adapted with retained Fable 5.1 profile; all U model additions present, Opus 4.8/5 already equivalent; exact new Sonnet 5/Nova 2 Lite definitions and registry; S4d validation |
| U026 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-dashscope-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/dashscope/DashscopeModels.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-dashscope-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/dashscope/DashscopeModels.kt | 38854edcf3f211cc3a112a48539f1dc03ff5ea27 | 71c99ebc13aa9cde5bc6734e7edb55533afb4a9e | 38854edcf3f211cc3a112a48539f1dc03ff5ea27 | S4e-dashscope | Adopted pinned upstream model definitions and catalogue; sole cosmetic KDoc punctuation adaptation; three additive JVM fields and exact profile regressions; S4e-dashscope validation |
| U027 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-deepseek-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/deepseek/DeepSeekModels.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-deepseek-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/deepseek/DeepSeekModels.kt | 08bad5f400598bc6a102061aa15f7313c82ab5bb | 97cfb48ac329f8fe00758bb4d7d56f8ecd9de209 | 08bad5f400598bc6a102061aa15f7313c82ab5bb | S4e-deepseek | Adopted exactly from pinned upstream blob 97cfb48ac329f8fe00758bb4d7d56f8ecd9de209; additive vision model and catalogue regression; S4e-deepseek validation |
| U028 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/GoogleLLMClient.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/GoogleLLMClient.kt | cff3739025a71c1c06e29abd831a7069960762e7 | 989431decee2ffb551b017918c0d46bdf1ff5969 | 0d20b02ce49d2b6e0342bfc628dcb537d6fda9fc | S4c | Adopted pinned cache metadata at shared conversion; retained structured inclusive usage, signatures, hosted execution and request constraints; see S4c validation |
| U029 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/GoogleModels.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/GoogleModels.kt | fdbe670c33fdba787d934e8ae48b55deb9f19e8f | 024a3a5ef0c38f82e4e86cfc24d44b97012f5c29 | bed1f4657d6eaadffd1bdf796b368b8e88fe7fb9 | S4c | All upstream profiles already present; retained evidenced fixed-sampling and Document patches; adopted introductory pricing qualification; see S4c validation |
| U030 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/models/GoogleGenerateContent.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/models/GoogleGenerateContent.kt | 2905490b7e1461b105c15640df82cf0fbdb3bd59 | 31ea6c4838d354794171fbbf236b06e7f1f2ca2d | 8d7dede3c042e1f8acf8071de1055267678ab8d1 | S4c | Upstream optional cache wire field already present with inclusive-subset KDoc; retained fork hosted execution and thinking wire types; see S4c validation |
| U031 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/google/GoogleLLMClientTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/google/GoogleLLMClientTest.kt | f42c6891175c967ad901bb346361f1bcfa53e175 | eb781921ad4e58d5284778f08dfae4394abc849c | 07ce97c022ad5a409337c33883d088bc23366d10 | S4c | Upstream cache assertion covered and extended in GoogleTokenUsageTest across both transports; existing signature and constraint assertions pass; see S4c validation |
| U032 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-mistralai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/mistralai/MistralAIModels.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-mistralai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/mistralai/MistralAIModels.kt | acf41e597b858f743cfb337df85bf4d40b743d54 | 0815e9d5c00813912b359244be76b87f787f5eb8 | acf41e597b858f743cfb337df85bf4d40b743d54 | S4e-mistralai | Adopted complete pinned model and catalogue delta; cosmetic British English and KDoc punctuation adaptation; three additive JVM fields and exact profile regressions; S4e-mistralai validation |
| U033 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-ollama-client/api/android/prompt-executor-ollama-client.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-ollama-client/api/android/prompt-executor-ollama-client.api | 07e68087bc80355616e176c21a476425ceefd5eb | 283fc36ecd21312d66d4d3578a51974c63d49eb9 | 07e68087bc80355616e176c21a476425ceefd5eb | X-ABI | Explicit exemption: non-JVM ABI; retain H; B=H |
| U034 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-ollama-client/api/jvm/prompt-executor-ollama-client.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-ollama-client/api/jvm/prompt-executor-ollama-client.api | 07e68087bc80355616e176c21a476425ceefd5eb | 283fc36ecd21312d66d4d3578a51974c63d49eb9 | 07e68087bc80355616e176c21a476425ceefd5eb | S4e-ollama | Adopted pinned two-field JVM API delta from compiled output; exact signatures, terminal newline normalised; S4e-ollama validation |
| U035 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-ollama-client/api/prompt-executor-ollama-client.klib.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-ollama-client/api/prompt-executor-ollama-client.klib.api | 658700aa53fda45d9f4b5f913573f52eb840241b | 493aae6d0c433fa029e1988f900bbacad1179a97 | 658700aa53fda45d9f4b5f913573f52eb840241b | X-ABI | Explicit exemption: non-JVM ABI; retain H; B=H |
| U036 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-ollama-client/src/commonMain/kotlin/ai/koog/prompt/executor/ollama/client/OllamaModels.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-ollama-client/src/commonMain/kotlin/ai/koog/prompt/executor/ollama/client/OllamaModels.kt | b3839243f215ea3ebafe4b30e93420cfb17bfdca | e7f3316a0f05a283891dbad68095f8568d4de9f6 | b3839243f215ea3ebafe4b30e93420cfb17bfdca | S4e-ollama | Adopted exactly from pinned upstream, including both Qwen profiles and catalogue entries; S4e-ollama validation |
| U037 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/api/android/prompt-executor-openai-client.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/api/android/prompt-executor-openai-client.api | c74f4ff31760c726101e983b47ba4c1b2ee045a5 | 6f277bac1b2bf8e219f8253cecae5d77c2163549 | 6f277bac1b2bf8e219f8253cecae5d77c2163549 | X-ABI | Explicit exemption: non-JVM ABI; retain H; U=H |
| U038 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/api/jvm/prompt-executor-openai-client.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/api/jvm/prompt-executor-openai-client.api | c74f4ff31760c726101e983b47ba4c1b2ee045a5 | 6f277bac1b2bf8e219f8253cecae5d77c2163549 | bc946d26f5ddb436142adc3eb53e71d9b8e1044d | S4a | S4a: retained; compiled before/after JVM ABI identical; pre-existing dump drift documented below |
| U039 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/api/prompt-executor-openai-client.klib.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/api/prompt-executor-openai-client.klib.api | bd242a538fe7cdc285924182a514346f872b20f9 | a2fb0f38336585f84b59206c00fcf80f66f27ac0 | a0dd44fc33b4596442e6806838fc86972ce673d6 | X-ABI | Explicit exemption: non-JVM ABI; retain H; diverged |
| U040 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIModels.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIModels.kt | 36a58638a2c64950047e9f791b248978e2ea1e3f | 52f76e1699bec554dd5e621a4e279fb394daedb7 | 153beefb583bb48e9cb710805b0fab5cc649eded | S4a | S4a: upstream GPT-5.6 capabilities adopted; later models, deployment copies and catalogue order retained |
| U041 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/models/OpenAIResponsesAPI.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/models/OpenAIResponsesAPI.kt | 3cfde38b90bff1109e0b96cc635f36d1f8d1018e | 3500ba6a093a5ff3b5b11eb17935db4e337f07b3 | 71e80d71d19384f2ef40db9c7aab510fe02b6b95 | S4a | S4a: pinned scalar-or-array serializer adopted; List<Item>? and fork response contracts retained |
| U042 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/models/OpenAIResponsesAPIResponseTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/models/OpenAIResponsesAPIResponseTest.kt | cf9fc56ad4d593793e768911128605cd98951176 | 585898a10701cbde62c0572ec34362df3003e673 | 2bd3601e3f4578afe7162e0eba6eecafbfefbc6e | S4a | S4a: upstream scalar/string-array/item-array regressions adopted; null, missing, empty and array-output coverage added |
| U043 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openrouter-client/api/android/prompt-executor-openrouter-client.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openrouter-client/api/android/prompt-executor-openrouter-client.api | 8c81f44302b36e80eacfff9dd4580521dd7c6ba0 | 808772d5f24e192bfa92c3e3fff7b923c340a3ac | 8c81f44302b36e80eacfff9dd4580521dd7c6ba0 | X-ABI | Explicit exemption: non-JVM ABI; retain H; B=H |
| U044 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openrouter-client/api/jvm/prompt-executor-openrouter-client.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openrouter-client/api/jvm/prompt-executor-openrouter-client.api | 8c81f44302b36e80eacfff9dd4580521dd7c6ba0 | 808772d5f24e192bfa92c3e3fff7b923c340a3ac | 8c81f44302b36e80eacfff9dd4580521dd7c6ba0 | S4e-openrouter | Adopted pinned eight-field JVM API delta from compiled output; terminal newline normalised; S4e-openrouter validation |
| U045 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openrouter-client/api/prompt-executor-openrouter-client.klib.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openrouter-client/api/prompt-executor-openrouter-client.klib.api | cd67abc10278f55fba061090b636ca768902d636 | 30b9c24270fceaa5599157024a0826e53da087d6 | cd67abc10278f55fba061090b636ca768902d636 | X-ABI | Explicit exemption: non-JVM ABI; retain H; B=H |
| U046 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openrouter-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openrouter/OpenRouterModels.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openrouter-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openrouter/OpenRouterModels.kt | 935ffccdca86781cac3fd184974a39b529ae1963 | fce36deae57a3d4414212db5415a85104e304ea5 | 935ffccdca86781cac3fd184974a39b529ae1963 | S4e-openrouter | Adopted complete pinned model and catalogue delta; three KDoc punctuation adaptations for repository language rules; S4e-openrouter validation |
| U047 | M | settings.gradle.kts | settings.gradle.kts | 451015ad7f16f75f76bf768cc111799d99901215 | 53638c13fdc85b4de98fe2dd1243421ffd55e7ee | 47256fc64a231c1fe1cddb8d8c7a08d09a615ba8 | S1 | Adapted with Kroog module graph; skills inclusion already present in H, retained byte-for-byte; S1 |
| U048 | A | absent | skills/Module.md | absent | b37fc4f353e81c0d828903c89e9fda0ae6a62ad6 | absent | S7-docs | Pending adoption with named slice contracts and history evidence; B=H |
| U049 | A | absent | skills/build.gradle.kts | absent | 234f2e0d5ccfc02e0f65ac1bfbf4f342665f5dc8 | a40ddc6a83f78c3aaf2d097965ad2cd448da90d5 | S1 | Adapted with Kroog JVM server convention, public tools and JSON APIs, SnakeYAML and existing regressions; upstream dependencies added; S1 |
| U050 | A | absent | skills/src/commonMain/kotlin/ai/koog/skills/discovery/SkillsDiscovery.kt | absent | 719288531379f27bd46ba8d97b1fc4e838bf5030 | absent | S6a | Pending adoption with named slice contracts and history evidence; B=H |
| U051 | A | absent | skills/src/commonMain/kotlin/ai/koog/skills/model/Skill.kt | absent | 665f2ae8be9cc9c22652e0ad90f4655781020a93 | absent | S6a | Pending adoption with named slice contracts and history evidence; B=H |
| U052 | A | absent | skills/src/commonMain/kotlin/ai/koog/skills/prompt/SkillsPrompt.kt | absent | 16d67c70d995904017e216cdee8139fb34f25a3b | absent | S6a | Pending adoption with named slice contracts and history evidence; B=H |
| U053 | A | absent | skills/src/commonTest/kotlin/prompt/SkillsPromptTest.kt | absent | 76ddce66bbfb4ec59818433bfb9eefba2950e4a2 | absent | S6a | Pending adoption with named slice contracts and history evidence; B=H |
| U054 | A | absent | skills/src/jvmTest/kotlin/ai/koog/skills/discovery/SkillsDiscoveryTest.kt | absent | 000da60e4bffb329ab79664c1d6c689d29f143f1 | absent | S6a | Pending adoption with named slice contracts and history evidence; B=H |

## Complete fork path ledger

| ID | Change | Old path | New and proposed final path | B blob | U blob | H blob | Slice | Disposition and evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F001 | A | absent | .github/workflows/publish-maven-release.yml | absent | absent | 2778206931f47d2e0fcbd2567ec7dcece836cc59 | S7 | Pending regression evidence: retain fork extension; B=U |
| F002 | A | absent | .github/workflows/publish-maven-snapshot.yml | absent | absent | dd0b442ff854908efebf371cdca9b18d0ded4cbf | S7 | Pending regression evidence: retain fork extension; B=U |
| F003 | M | .gitignore | .gitignore | 5285c98f106ae52c7447598cad3ca53af01e6beb | 5285c98f106ae52c7447598cad3ca53af01e6beb | 8c8638a960b74fbeab4d7f5942a0c26135bd9901 | X-local | Explicit exemption: retain local repository guidance; B=U |
| F004 | M | AGENTS.md | AGENTS.md | 483589d9aa8d56ef84892c88af9972fa65231869 | 483589d9aa8d56ef84892c88af9972fa65231869 | 7b7f10ea21c924cab24f2b1836af527cb32ab301 | X-local | Explicit exemption: retain local repository guidance; B=U |
| F005 | A | absent | PUBLISHING.md | absent | absent | e6e800df1f0fa880537331bdc9d5b33b240397e6 | S7 | Pending regression evidence: retain fork extension; B=U |
| F006 | M | agents/agents-core/api/jvm/agents-core.api | agents/agents-core/api/jvm/agents-core.api | fc041121a3c36659e1cde90fccf72a58431d1d9d | fc041121a3c36659e1cde90fccf72a58431d1d9d | 652b0e52580bbfe9ac52ad2b142d607ba5529662 | S5a | Retained JVM dump exactly from H; no slice API change; compiled dump comparison not run; see S5a and R-runtime validation |
| F007 | M | agents/agents-core/build.gradle.kts | agents/agents-core/build.gradle.kts | 9e2d621e390481187a089e25dd4a798d0dec7eb8 | 9e2d621e390481187a089e25dd4a798d0dec7eb8 | 9c4ce1031dde859bfba32340a1d79b9ad67aece0 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F008 | A | absent | agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/tools/ManagedExecutionTool.kt | absent | absent | 21e9a9df1970ad247b1cec98ab3456bc7efc7ff1 | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F009 | A | absent | agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/tools/ServiceBackedManagedExecutionTool.kt | absent | absent | 6cba2f86c26b973f43684fc29b053adaa70780b4 | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F010 | M | agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/dsl/extension/AIAgentNodes.kt | agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/dsl/extension/AIAgentNodes.kt | 26393542a611ce601113c8f2948ecab089892493 | 26393542a611ce601113c8f2948ecab089892493 | b1d73d9929a3581220679412e686c9570650479d | S5b | Retained exactly from H; B=U; inspected compaction and usage assertions pass in reused S5a run; see S5b and R-ktor validation |
| F011 | A | absent | agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/dsl/extension/BudgetedHistoryCompressionStrategy.kt | absent | absent | c85779be2b0dd01a543a906675d4ffcfbe894052 | S5b | Retained exactly from H; B=U; inspected compaction and usage assertions pass in reused S5a run; see S5b and R-ktor validation |
| F012 | M | agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/dsl/extension/DefaultHistoryCompressionStrategies.kt | agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/dsl/extension/DefaultHistoryCompressionStrategies.kt | 38585a355081f16925bc3296cd7a8c821561cd18 | 38585a355081f16925bc3296cd7a8c821561cd18 | 57c29e8b83eca7a68345c0ec427a6a8548736c01 | S5b | Retained exactly from H; B=U; inspected compaction and usage assertions pass in reused S5a run; see S5b and R-ktor validation |
| F013 | M | agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/dsl/extension/HistoryCompressionStrategy.kt | agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/dsl/extension/HistoryCompressionStrategy.kt | ad9818b029e842338610c8455093a477072b26b8 | ad9818b029e842338610c8455093a477072b26b8 | a1cabbf14a0d786dd0bae6424faf6c3dd4fe3f5c | S5b | Retained exactly from H; B=U; inspected compaction and usage assertions pass in reused S5a run; see S5b and R-ktor validation |
| F014 | M | agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/environment/ContextualAgentEnvironment.kt | agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/environment/ContextualAgentEnvironment.kt | c3b57f548203e062944514edeb83ce37ae521da6 | c3b57f548203e062944514edeb83ce37ae521da6 | 03774f6901e7c66a6cd073627c6a89ee5328f833 | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F015 | M | agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/environment/GenericAgentEnvironment.kt | agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/environment/GenericAgentEnvironment.kt | c63eb2ad24ba3142cf35bc426dfd9fb700688679 | c63eb2ad24ba3142cf35bc426dfd9fb700688679 | 198fb15e1b65c0bd596f5e6493d67b576e305d9c | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F016 | A | absent | agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/environment/ManagedExecutionEventObserver.kt | absent | absent | ec5acd3621c89dcd8e6ff6c638dcd7cb852ea5d3 | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F017 | M | agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/prompt/Prompts.kt | agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/prompt/Prompts.kt | 4bef19c65a9d07df7601901c6572e2e78adff1f8 | 4bef19c65a9d07df7601901c6572e2e78adff1f8 | 6ea197cf30ae8b9f56d4b94ef1dfdd36323ffad3 | S5b | Retained exactly from H; B=U; inspected compaction and usage assertions pass in reused S5a run; see S5b and R-ktor validation |
| F018 | A | absent | agents/agents-core/src/jvmTest/kotlin/ai/koog/agents/core/dsl/extension/TieredHistoryCompressionStrategyTest.kt | absent | absent | 0cfb4774e288359f4eccbb2e82e1633d35450c59 | S5b | Retained exactly from H; B=U; inspected compaction and usage assertions pass in reused S5a run; see S5b and R-ktor validation |
| F019 | A | absent | agents/agents-core/src/jvmTest/kotlin/ai/koog/agents/core/environment/JvmLogCapture.kt | absent | absent | dec348afa0e820ed288bdcacd22efcc3980b7edd | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F020 | A | absent | agents/agents-core/src/jvmTest/kotlin/ai/koog/agents/core/environment/ManagedExecutionPipelinePrivacyTest.kt | absent | absent | 4b5faa1449c29da59385f68d6f2404d0ed749539 | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F021 | A | absent | agents/agents-core/src/jvmTest/kotlin/ai/koog/agents/core/environment/ManagedExecutionToolTest.kt | absent | absent | 39720e5aa6f294bde9b37eeecb4bb95a5ddbe967 | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F022 | A | absent | agents/agents-core/src/jvmTest/kotlin/ai/koog/agents/core/environment/ServiceBackedManagedExecutionToolTest.kt | absent | absent | 973bec87ef278f32b3a3b647cf3643ce9663bbe8 | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F023 | M | agents/agents-features/agents-features-acp/build.gradle.kts | agents/agents-features/agents-features-acp/build.gradle.kts | 314d18fda44132267e34b1154d5ad8b04d130a76 | 314d18fda44132267e34b1154d5ad8b04d130a76 | 3add872c44bdfdce60c49ca5ed354be949c8ab1e | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F024 | M | agents/agents-features/agents-features-acp/src/jvmMain/kotlin/ai/koog/agents/features/acp/MessageConverters.kt | agents/agents-features/agents-features-acp/src/jvmMain/kotlin/ai/koog/agents/features/acp/MessageConverters.kt | e87f00377aa48424f20e78c3b2f004ea1b0f69df | e87f00377aa48424f20e78c3b2f004ea1b0f69df | bd189d9e6e3a4616d14082e58629e4d71ff213e1 | S5c | Retained H unchanged; assigned JVM presentation regressions passed; see S5c validation; B=U |
| F025 | A | absent | agents/agents-features/agents-features-acp/src/jvmTest/kotlin/ai/koog/agents/features/acp/CodeExecutionMessageConvertersTest.kt | absent | absent | db5ccbb48a9be9a0e821a649555afb0290fa159b | S5c | Retained H unchanged; assigned JVM presentation regressions passed; see S5c validation; B=U |
| F026 | A | absent | agents/agents-features/agents-features-acp/src/jvmTest/kotlin/ai/koog/agents/features/acp/HostedExecutionMessageConvertersTest.kt | absent | absent | 65705683457f99969f046e1bc2da4c0b407a01ad | S5c | Retained H unchanged; assigned JVM presentation regressions passed; see S5c validation; B=U |
| F027 | M | agents/agents-features/agents-features-event-handler/src/commonMain/kotlin/ai/koog/agents/features/eventHandler/messageFormat.kt | agents/agents-features/agents-features-event-handler/src/commonMain/kotlin/ai/koog/agents/features/eventHandler/messageFormat.kt | 44d9e91135213269bdcc18acf0ed71451edca32e | 44d9e91135213269bdcc18acf0ed71451edca32e | d2836528c5299f2a244c4c32ccb517d41fca8a99 | S5c | Retained H unchanged; assigned JVM presentation regressions passed; see S5c validation; B=U |
| F028 | A | absent | agents/agents-features/agents-features-event-handler/src/commonTest/kotlin/ai/koog/agents/features/eventHandler/CodeExecutionMessageFormatTest.kt | absent | absent | 5e8f53324ea620c25a561985ffe7b222c1221d26 | S5c | Retained H unchanged; assigned JVM presentation regressions passed; see S5c validation; B=U |
| F029 | A | absent | agents/agents-features/agents-features-event-handler/src/commonTest/kotlin/ai/koog/agents/features/eventHandler/HostedExecutionMessageFormatTest.kt | absent | absent | ded342305d3c10000846cdbb56fff7229d065130 | S5c | Retained H unchanged; assigned JVM presentation regressions passed; see S5c validation; B=U |
| F030 | M | agents/agents-features/agents-features-event-handler/src/jvmTest/kotlin/ai/koog/agents/features/eventHandler/feature/EventHandlerTest.kt | agents/agents-features/agents-features-event-handler/src/jvmTest/kotlin/ai/koog/agents/features/eventHandler/feature/EventHandlerTest.kt | 3e3100914cd645bd276763d939a8c4cf68648823 | 3e3100914cd645bd276763d939a8c4cf68648823 | 4255ca9cd4b6dc2bcbe052c12f2209008674a0c0 | S5c | Retained H unchanged; assigned JVM presentation regressions passed; see S5c validation; B=U |
| F031 | M | agents/agents-features/agents-features-opentelemetry/src/commonMain/kotlin/ai/koog/agents/features/opentelemetry/attribute/GenAIAttributes.kt | agents/agents-features/agents-features-opentelemetry/src/commonMain/kotlin/ai/koog/agents/features/opentelemetry/attribute/GenAIAttributes.kt | a0c4937dd09688e383f8bfbf61ab72da5bdced3c | a0c4937dd09688e383f8bfbf61ab72da5bdced3c | 957d3d6b1740e950f1cee648f960ad818e1ebff4 | S5c | Retained H unchanged; assigned JVM presentation regressions passed; see S5c validation; B=U |
| F032 | M | agents/agents-features/agents-features-opentelemetry/src/commonTest/kotlin/ai/koog/agents/features/opentelemetry/attribute/GenAIAttributesTest.kt | agents/agents-features/agents-features-opentelemetry/src/commonTest/kotlin/ai/koog/agents/features/opentelemetry/attribute/GenAIAttributesTest.kt | 1b2911be5fb5e4a6704c6c6938e0c5f694302b1b | 1b2911be5fb5e4a6704c6c6938e0c5f694302b1b | 8c84a3b88892c9be83c1dced5d9a262822726301 | S5c | Retained H unchanged; assigned JVM presentation regressions passed; see S5c validation; B=U |
| F033 | M | agents/agents-features/agents-features-trace/src/commonMain/kotlin/ai/koog/agents/features/tracing/messageFormat.kt | agents/agents-features/agents-features-trace/src/commonMain/kotlin/ai/koog/agents/features/tracing/messageFormat.kt | ab2fc0d51d74942803f06d95249f999bc22e535d | ab2fc0d51d74942803f06d95249f999bc22e535d | 90cefe32ee2b15087ce69d0f1a86f31725caae48 | S5c | Retained H unchanged; assigned JVM presentation regressions passed; see S5c validation; B=U |
| F034 | A | absent | agents/agents-features/agents-features-trace/src/commonTest/kotlin/ai/koog/agents/features/tracing/CodeExecutionMessageFormatTest.kt | absent | absent | cb70556375e8c2a69b1c470566dce5ea0c5c77ae | S5c | Retained H unchanged; assigned JVM presentation regressions passed; see S5c validation; B=U |
| F035 | A | absent | agents/agents-features/agents-features-trace/src/commonTest/kotlin/ai/koog/agents/features/tracing/HostedExecutionMessageFormatTest.kt | absent | absent | b3b8d348032c9d0a159cfc70dece6dd06c1ad038 | S5c | Retained H unchanged; assigned JVM presentation regressions passed; see S5c validation; B=U |
| F036 | M | agents/agents-test/src/commonMain/kotlin/ai/koog/agents/testing/tools/MockPromptExecutor.kt | agents/agents-test/src/commonMain/kotlin/ai/koog/agents/testing/tools/MockPromptExecutor.kt | 59d6f3098694294781561ab29fed51ae080adaf5 | 59d6f3098694294781561ab29fed51ae080adaf5 | 61cc2f605f0c7ac52558bd20fe4f6becb0c0a200 | S5b | Retained exactly from H; B=U; inspected compaction and usage assertions pass in reused S5a run; see S5b and R-ktor validation |
| F037 | A | absent | agents/agents-test/src/jvmTest/kotlin/ai/koog/agents/test/TokenUsagePreservationTest.kt | absent | absent | 5ec056a4e15097ea0d0c1dbcbce0dc3a66a43ec9 | S5b | Retained exactly from H; B=U; inspected compaction and usage assertions pass in reused S5a run; see S5b and R-ktor validation |
| F038 | M | build.gradle.kts | build.gradle.kts | baa1464b3f6b71abc508a6e38fd3d4efde23692d | baa1464b3f6b71abc508a6e38fd3d4efde23692d | e474a745bd6222c10132c0ca18848dc6e2c46302 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F039 | M | convention-plugin-ai/build.gradle.kts | convention-plugin-ai/build.gradle.kts | 794f50f9866fd1721cb0d2e7dd884831e202f32b | 794f50f9866fd1721cb0d2e7dd884831e202f32b | 4087bca85a75ef95d02dd6bc59ace4e8081d986d | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F040 | M | convention-plugin-ai/src/main/kotlin/ai.kotlin.jvm.publish.gradle.kts | convention-plugin-ai/src/main/kotlin/ai.kotlin.jvm.publish.gradle.kts | 025d1bb49b539c51ac067e5a869fad57afabaa53 | 025d1bb49b539c51ac067e5a869fad57afabaa53 | 401cea7b2e9c23ee19f4865ca0c60737d3795c37 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F041 | M | convention-plugin-ai/src/main/kotlin/ai.kotlin.multiplatform.gradle.kts | convention-plugin-ai/src/main/kotlin/ai.kotlin.multiplatform.gradle.kts | 682cca36e6b231692b4b3ba23e4ebcae662dc206 | 682cca36e6b231692b4b3ba23e4ebcae662dc206 | 4849ee2459aaac9ce85fed5fc677989fe2ba1e84 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F042 | M | convention-plugin-ai/src/main/kotlin/ai.kotlin.multiplatform.server.gradle.kts | convention-plugin-ai/src/main/kotlin/ai.kotlin.multiplatform.server.gradle.kts | 8f68dc40d69f499af99c95fee54304eb6b3bac98 | 8f68dc40d69f499af99c95fee54304eb6b3bac98 | 9bbf168081b94ecf4ac17f9f6ba7bde3fdc7bbfc | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F043 | M | convention-plugin-ai/src/main/kotlin/ai/koog/gradle/publish/maven/Publishing.kt | convention-plugin-ai/src/main/kotlin/ai/koog/gradle/publish/maven/Publishing.kt | 189070fdd582e10c4b54485678ec535b4a9ba207 | 189070fdd582e10c4b54485678ec535b4a9ba207 | 4b2b5832a59d167891afaa02f6e6555601509005 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F044 | A | absent | convention-plugin-ai/src/main/kotlin/ai/koog/gradle/publish/maven/Signing.kt | absent | absent | 2469f60f18bdb5823bace148e91ccbbe56cdfc75 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F045 | A | absent | convention-plugin-ai/src/test/kotlin/ai/koog/gradle/publish/maven/PublishingTest.kt | absent | absent | d4ba4c242c036a14a6fc48f1d6df7191578a1c90 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F046 | A | absent | convention-plugin-ai/src/test/kotlin/ai/koog/gradle/publish/maven/SigningTest.kt | absent | absent | 6c705fcd51040b71e8b2aa1a59a50ebe8a6762e3 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F047 | M | docs/docs/history-compression.md | docs/docs/history-compression.md | 44a298b67b101e3c2b7ce0c774d2ead8b4e0db64 | 44a298b67b101e3c2b7ce0c774d2ead8b4e0db64 | 25f4ec8e37fb752f595ae8ccca363189b0dd30d2 | S7-docs | Pending regression evidence: retain fork extension; B=U |
| F048 | M | docs/docs/model-capabilities.md | docs/docs/model-capabilities.md | 7033a5f391d5a7542c2ea47cf8a89ca6c0cfeecd | 7033a5f391d5a7542c2ea47cf8a89ca6c0cfeecd | 237049119cc7e6dc1c5ce20e56bc34db9c9d8bb6 | S7-docs | Pending regression evidence: retain fork extension; B=U |
| F049 | M | docs/docs/quickstart.md | docs/docs/quickstart.md | 6c8be0be9e6e23a34704ccce998db3c77bb1bf10 | 6c8be0be9e6e23a34704ccce998db3c77bb1bf10 | 366ed4cb8e52b7064ce4673edca9e79fc3b12873 | S7-docs | Pending regression evidence: retain fork extension; B=U |
| F050 | M | docs/docs/streaming-api.md | docs/docs/streaming-api.md | cd555c36c10fc189bbd68d7d01d03761292d76cc | cd555c36c10fc189bbd68d7d01d03761292d76cc | 2500debaf1b76e8bf580df6da1c47881217c8771 | S7-docs | Pending regression evidence: retain fork extension; B=U |
| F051 | M | examples/simple-examples/src/main/kotlin/ai/koog/agents/example/streaming/StreamingAgentWithTools.kt | examples/simple-examples/src/main/kotlin/ai/koog/agents/example/streaming/StreamingAgentWithTools.kt | 99e125bde1d99ba0337aeaff9501efeac4d0cc5c | 99e125bde1d99ba0337aeaff9501efeac4d0cc5c | f52c3cf7e6c82690e8bd63b244ca3b63f0f8bffc | S7-docs | Pending regression evidence: retain fork extension; B=U |
| F052 | M | gradle.properties | gradle.properties | 7916e8236aa4119b69fe2fa1564f77b5d6c693a4 | 5c9c232e3b522560a96ed970806ec346b32eca72 | 6199cccbc1158cc63586a6c48abc44cabdd24cad | X-version | Explicit exemption: retain Kroog version and coordinates; diverged |
| F053 | A | absent | gradle/kroog-jvm-publications.txt | absent | absent | e7dd7c2758b6644ac20ada6cab2f028767487955 | S7 | Pending regression evidence: retain fork extension; B=U |
| F054 | M | gradle/libs.versions.toml | gradle/libs.versions.toml | db6f11b4aaac12e96878b2d911edfea16d975b6e | db6f11b4aaac12e96878b2d911edfea16d975b6e | 70cca7d7f714c947417630a7967eebfadc058ee0 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F055 | M | http-client/http-client-core/src/commonMain/kotlin/ai/koog/http/client/Exceptions.kt | http-client/http-client-core/src/commonMain/kotlin/ai/koog/http/client/Exceptions.kt | 751ce63de09f4ab7b6bc8cf3594ac21a17de2e41 | 751ce63de09f4ab7b6bc8cf3594ac21a17de2e41 | 27321c8e93bc49218d1f7a24dbf31872cfccb327 | S3 | Retained exactly from H; B=U. HTTP, SSE, retry and replay assertions pass; see S3 validation |
| F056 | M | http-client/http-client-core/src/commonMain/kotlin/ai/koog/http/client/KoogHttpClient.kt | http-client/http-client-core/src/commonMain/kotlin/ai/koog/http/client/KoogHttpClient.kt | c37fb047cbd4b5ec610a1887df92bc0498f677ab | c37fb047cbd4b5ec610a1887df92bc0498f677ab | fcd92fbbc8b49cc6da23a691812aaa4efa17d173 | S3 | Retained exactly from H; B=U. HTTP, SSE, retry and replay assertions pass; see S3 validation |
| F057 | A | absent | http-client/http-client-core/src/jvmTest/kotlin/ai/koog/http/client/KoogHttpClientBinaryTest.kt | absent | absent | 9be79e9077497bdb9be8ca8fc390b55225ec2554 | S3 | Retained exactly from H; B=U. HTTP, SSE, retry and replay assertions pass; see S3 validation |
| F058 | M | http-client/http-client-java/src/main/kotlin/ai/koog/http/client/java/JavaKoogHttpClient.kt | http-client/http-client-java/src/main/kotlin/ai/koog/http/client/java/JavaKoogHttpClient.kt | ff5c5fa6943e6928dcc0d8b177e7eaaf4ed46ada | ff5c5fa6943e6928dcc0d8b177e7eaaf4ed46ada | fce79a2dcdaac1b2498f6fd27328d11b04f88a91 | S3 | Retained exactly from H; B=U. HTTP, SSE, retry and replay assertions pass; see S3 validation |
| F059 | A | absent | http-client/http-client-java/src/test/kotlin/ai/koog/http/client/java/JavaKoogHttpClientSseTest.kt | absent | absent | 3ebb74cc04cd25944a0e3c8d31a049c763a40819 | S3 | Retained exactly from H; B=U. HTTP, SSE, retry and replay assertions pass; see S3 validation |
| F060 | M | http-client/http-client-ktor/src/commonMain/kotlin/ai/koog/http/client/ktor/KtorKoogHttpClient.kt | http-client/http-client-ktor/src/commonMain/kotlin/ai/koog/http/client/ktor/KtorKoogHttpClient.kt | 074e01e89bf40958bf5ff748c940f8324a109f2f | 074e01e89bf40958bf5ff748c940f8324a109f2f | 5b3cdf0e9356adc826f1cfa75a20c24ffe58215c | S3 | Retained exactly from H; B=U. HTTP, SSE, retry and replay assertions pass; see S3 validation |
| F061 | M | http-client/http-client-ktor/src/jvmTest/kotlin/ai/koog/http/client/ktor/KtorKoogHttpClientTestBase.kt | http-client/http-client-ktor/src/jvmTest/kotlin/ai/koog/http/client/ktor/KtorKoogHttpClientTestBase.kt | 96fc8e83fed31ff39fd9272b7b719cf056922597 | 96fc8e83fed31ff39fd9272b7b719cf056922597 | 17eb309ef3a04e4b936fd30fb10546248e8261ca | S3 | Retained exactly from H; B=U. HTTP, SSE, retry and replay assertions pass; see S3 validation |
| F062 | M | http-client/http-client-test/src/main/kotlin/ai/koog/http/client/test/BaseKoogHttpClientTest.kt | http-client/http-client-test/src/main/kotlin/ai/koog/http/client/test/BaseKoogHttpClientTest.kt | 2377ea47958a26f69cd21dba04bfba1618aa1617 | 2377ea47958a26f69cd21dba04bfba1618aa1617 | e7f185b42e8512787d2cbf6df083d66b24053c24 | R-http-fixtures | Retained exactly from H; B=U. Fixture compilation and Ktor binary/resource-lifetime assertions pass; see S3 validation |
| F063 | M | http-client/http-client-test/src/main/kotlin/ai/koog/http/client/test/MockWebServer.kt | http-client/http-client-test/src/main/kotlin/ai/koog/http/client/test/MockWebServer.kt | 7393d738b977f95de3ebe7f17078e0c172fef5f3 | 7393d738b977f95de3ebe7f17078e0c172fef5f3 | 572284cc718c82e96e239ed87f599498dc5b9c15 | R-http-fixtures | Retained exactly from H; B=U. Fixture compilation and Ktor binary/resource-lifetime assertions pass; see S3 validation |
| F064 | M | integration-tests/src/jvmTest/kotlin/ai/koog/integration/tests/executor/AnthropicCacheControlIntegrationTest.kt | integration-tests/src/jvmTest/kotlin/ai/koog/integration/tests/executor/AnthropicCacheControlIntegrationTest.kt | 41449ae0b2616db6a13f45c980314c1e7e6019fb | 41449ae0b2616db6a13f45c980314c1e7e6019fb | c2b08fdf355e1d228a9b366ec55f1d1de2856504 | R-integration | Pending regression evidence: retain fork extension; B=U |
| F065 | M | integration-tests/src/jvmTest/kotlin/ai/koog/integration/tests/executor/BedrockConverseApiIntegrationTest.kt | integration-tests/src/jvmTest/kotlin/ai/koog/integration/tests/executor/BedrockConverseApiIntegrationTest.kt | 6e1623b13d203f9509f0a49815e83fded5f6788b | 6e1623b13d203f9509f0a49815e83fded5f6788b | 8f9f6351b841619dc3d5dc6e7a85c05547292681 | R-integration | Pending regression evidence: retain fork extension; B=U |
| F066 | M | integration-tests/src/jvmTest/kotlin/ai/koog/integration/tests/executor/ExecutorIntegrationTestBase.kt | integration-tests/src/jvmTest/kotlin/ai/koog/integration/tests/executor/ExecutorIntegrationTestBase.kt | 8e1a9b0b0f5d830e2fd6b87071e2b4dd71ebad3d | 8e1a9b0b0f5d830e2fd6b87071e2b4dd71ebad3d | 0c8c9c503479f6f2839790e35366e3a3e92834a5 | R-integration | Pending regression evidence: retain fork extension; B=U |
| F067 | M | koog-agents-additions/build.gradle.kts | koog-agents-additions/build.gradle.kts | 1ad4e8c7e888dcbd2ec9ec613c4bc50583787f0a | d9a55abe79f8a11c1c1f0953b7ae502b31845797 | 8c1d865da0d846390fc08a81d859687930c711f8 | S1 | Adapted with JVM-only skills transitive and retained managed-execution dependency; S1 |
| F068 | M | koog-agents/build.gradle.kts | koog-agents/build.gradle.kts | 478a18424904672e11576114d7a1a21e385cb89e | 59b939d9f0e1c64eae11fa0d8ebcaf50acd38e29 | 5fa96eccdd8d93b1f150c5ef5248eee6a8b7aaa4 | S1 | Adapted with upstream skills beta classification and retained managed-execution dependency; S1 |
| F069 | M | koog-bedrock-agentcore-runtime/build.gradle.kts | koog-bedrock-agentcore-runtime/build.gradle.kts | 53171b8b48e9ac50235c639cf223d51eac0148e2 | 53171b8b48e9ac50235c639cf223d51eac0148e2 | dfc6b00d393c59263c76d2db0d501c1c4a2c9712 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F070 | M | koog-bedrock-agentcore-runtime/src/test/kotlin/ai/koog/agentcore/runtime/AgentCoreRuntimeTest.kt | koog-bedrock-agentcore-runtime/src/test/kotlin/ai/koog/agentcore/runtime/AgentCoreRuntimeTest.kt | 1f3bfcbda16af95eb53a6015ccdb1cb1ce87cd12 | 1f3bfcbda16af95eb53a6015ccdb1cb1ce87cd12 | d9821c050a4511a570a7ccadf88854e2924beb5e | R-runtime | Retained exactly from H; B=U; 30 runtime request tests pass; see S5a and R-runtime validation |
| F071 | M | koog-ktor/src/commonMain/kotlin/ai/koog/ktor/utils/LLMModelParser.kt | koog-ktor/src/commonMain/kotlin/ai/koog/ktor/utils/LLMModelParser.kt | 8f374f73ac1015471357720ac3c4edcca7c5c9d5 | 8f374f73ac1015471357720ac3c4edcca7c5c9d5 | d12b086d44844a14f300ba770b176b2e07d843c4 | R-ktor | Retained exactly from H; B=U; 13 model parser tests pass; see S5b and R-ktor validation |
| F072 | M | koog-ktor/src/commonTest/kotlin/ai/koog/ktor/ModelIdentifierParsingTest.kt | koog-ktor/src/commonTest/kotlin/ai/koog/ktor/ModelIdentifierParsingTest.kt | 23db09a087eb401f24ef076686aca92b0d79eebe | 23db09a087eb401f24ef076686aca92b0d79eebe | 372eeb2ca83f809d143e795663a3aafb251c5f8e | R-ktor | Retained exactly from H; B=U; 13 model parser tests pass; see S5b and R-ktor validation |
| F073 | M | koog-spring-ai-v2/koog-spring-ai-v2-starter-chat-memory/build.gradle.kts | koog-spring-ai-v2/koog-spring-ai-v2-starter-chat-memory/build.gradle.kts | b309782416b2f9b017e33643bb9a1b282f1b68fb | b309782416b2f9b017e33643bb9a1b282f1b68fb | ad688adf35b25b96d83bcb3b9f385282413cf528 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F074 | M | koog-spring-ai-v2/koog-spring-ai-v2-starter-model-chat/build.gradle.kts | koog-spring-ai-v2/koog-spring-ai-v2-starter-model-chat/build.gradle.kts | 0e1f2ff38efbc08d4f4060ac109c85d866e735f3 | 0e1f2ff38efbc08d4f4060ac109c85d866e735f3 | 235b61385bb47dff69c8222877c5e8f2570d8537 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F075 | M | koog-spring-ai-v2/koog-spring-ai-v2-starter-model-embedding/build.gradle.kts | koog-spring-ai-v2/koog-spring-ai-v2-starter-model-embedding/build.gradle.kts | a7b6481c4dfea396a9645c4e8aba6e7ab35990d6 | a7b6481c4dfea396a9645c4e8aba6e7ab35990d6 | 1b1f6c2a7eadd3a440301971196b2a7513debf81 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F076 | M | koog-spring-ai-v2/koog-spring-ai-v2-starter-vector-store/build.gradle.kts | koog-spring-ai-v2/koog-spring-ai-v2-starter-vector-store/build.gradle.kts | 43582e82c2af169346e5a2561e2d2fcc8b9ada61 | 43582e82c2af169346e5a2561e2d2fcc8b9ada61 | 83bc28ab75b9d2d29f3a24de13ee9cc881e69254 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F077 | M | koog-spring-ai/koog-spring-ai-starter-chat-memory/build.gradle.kts | koog-spring-ai/koog-spring-ai-starter-chat-memory/build.gradle.kts | 86de1047720c32e6b58b21a85cc9e0c5651df5d8 | 86de1047720c32e6b58b21a85cc9e0c5651df5d8 | 2f0147625026802f001b838fae789ff6264f2012 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F078 | M | koog-spring-ai/koog-spring-ai-starter-model-chat/build.gradle.kts | koog-spring-ai/koog-spring-ai-starter-model-chat/build.gradle.kts | dc6121aea0c8de433fcdad5b262cde1fdaa44ef3 | dc6121aea0c8de433fcdad5b262cde1fdaa44ef3 | 3608b05e956f03fcea61cb3b5f529cb3f6f1d05d | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F079 | M | koog-spring-ai/koog-spring-ai-starter-model-embedding/build.gradle.kts | koog-spring-ai/koog-spring-ai-starter-model-embedding/build.gradle.kts | b72adf7ac3553d000a034825d644c2cd7ea69fe3 | b72adf7ac3553d000a034825d644c2cd7ea69fe3 | a60445b60e2f5b8f6749bf49c2efcdd6011ab94a | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F080 | M | koog-spring-ai/koog-spring-ai-starter-vector-store/build.gradle.kts | koog-spring-ai/koog-spring-ai-starter-vector-store/build.gradle.kts | 876625d4f46fa703943e7de89a52c0f5c71c0a06 | 876625d4f46fa703943e7de89a52c0f5c71c0a06 | 0b896702cf53d40499d9db04a34de845eb66f811 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F081 | M | prompt/prompt-executor/prompt-executor-clients/api/jvm/prompt-executor-clients.api | prompt/prompt-executor/prompt-executor-clients/api/jvm/prompt-executor-clients.api | 387003abfa9cf325d40553d368f22d340696bbf3 | 387003abfa9cf325d40553d368f22d340696bbf3 | ca9db3dbcf6b6e892553f2c5be462db02560860d | S3 | Retained exactly from H; B=U. HTTP, SSE, retry and replay assertions pass; see S3 validation |
| F082 | M | prompt/prompt-executor/prompt-executor-clients/build.gradle.kts | prompt/prompt-executor/prompt-executor-clients/build.gradle.kts | 10c16b7282509c8f22c4b604749c945a036fb410 | 10c16b7282509c8f22c4b604749c945a036fb410 | d4036aef4b1df8b2219ebbf39e5cb28dd750fb3b | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F083 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/Module.md | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/Module.md | a2ecfc425963a4794275f437634dd1ec7b4b4b2b | a2ecfc425963a4794275f437634dd1ec7b4b4b2b | 9434ec117c8d81747163101440092a22a6db2d6c | S4b | Adapted: retain Vertex documentation, add Sonnet_5 public profile; see S4b validation |
| F084 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/api/android/prompt-executor-anthropic-client.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/api/android/prompt-executor-anthropic-client.api | 52e93e7f17de415ffcafd38a92a7388853283eb7 | ab3eaf11d4aa3f618108c1142e86d74e4888a274 | d5adcbae0693351c6d59d053da23c42c5feb3416 | X-ABI | Explicit exemption: non-JVM ABI; retain H; diverged |
| F085 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/api/jvm/prompt-executor-anthropic-client.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/api/jvm/prompt-executor-anthropic-client.api | 52e93e7f17de415ffcafd38a92a7388853283eb7 | ab3eaf11d4aa3f618108c1142e86d74e4888a274 | 2dd1f3d634b33d83e012087eba979732b00a7099 | S4b | Adapted: additive Sonnet_5 JVM field; complete compiled ABI matches regenerated JVM dump; see S4b validation |
| F086 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/api/prompt-executor-anthropic-client.klib.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/api/prompt-executor-anthropic-client.klib.api | f6e5a790d75d8c70e58999a57620ce69decd2a0d | 09e055b2375a7fc34e5998ac0656b4c06a82413a | 525a2ca1c8527eb620d6f3664ae8971c774a9c14 | X-ABI | Explicit exemption: non-JVM ABI; retain H; diverged |
| F087 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicLLMClient.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicLLMClient.kt | bcb7aa1570c404e5da68fb50d5db9ebc7b0c49f4 | bcb7aa1570c404e5da68fb50d5db9ebc7b0c49f4 | e35d0e10bcc92c1f6d14a8bf1faa783d4b7918e9 | S4b | Superseded with equivalence evidence: B=U; retain H client exactly; Vertex, anyOf, signed replay, managed replay rejection and inclusive usage pass; see S4b validation |
| F088 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicModels.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicModels.kt | 0a312dfc3032c2fe84c076cdc660a1b6d43b021f | eff87b0b4ed34829b79d1c2e37b9d52b047a33fe | 10d8f0eb43d683343954218ade284ccefb49b2a7 | S4b | Adapted with named Kroog contract: adopt Sonnet_5 exactly; retain later Fable models and Opus temperature restrictions from bab8fc840; see S4b validation |
| F089 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicVertexLLMClient.kt | absent | absent | 1cb1cf40d83e520c07b65af783260d073ef8742f | S4b | Superseded with equivalence evidence: B=U absent; retain H Vertex transport exactly; eight Vertex tests pass; see S4b validation |
| F090 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/anthropic/models/AnthropicChatMessages.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/anthropic/models/AnthropicChatMessages.kt | 10e46d4612dd70f234c7b1b64c726e9fc4cf20b2 | 10e46d4612dd70f234c7b1b64c726e9fc4cf20b2 | 95b369431d08b7ebd8012abb5fd05d229123167d | S4b | Superseded with equivalence evidence: B=U; retain H wire models exactly; serialisation and replay tests pass; see S4b validation |
| F091 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/commonTest/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicSerializationTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/commonTest/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicSerializationTest.kt | ceb9fb602f4211bfbc73712e7c28d62e8a0af7c5 | ceb9fb602f4211bfbc73712e7c28d62e8a0af7c5 | f0d8cee1d2cdf0dfcf4ddaa144199ca10f252fb1 | S4b | Superseded with equivalence evidence: B=U; retain H serialisation tests exactly; ten pass; see S4b validation |
| F092 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicCacheControlTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicCacheControlTest.kt | 564460bbb4504b00a81834605a33e98abe345f7a | 564460bbb4504b00a81834605a33e98abe345f7a | 652c35d310a79d7cd48a698ee45d6a4fb67ef430 | S4b | Superseded with equivalence evidence: B=U; retain H cache tests exactly; 23 pass; see S4b validation |
| F093 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicCodeExecutionReplayTest.kt | absent | absent | 9bd834ceeb6e7d0ef4cb5630d0a2c436dc6504fe | S4b | Superseded with equivalence evidence: B=U absent; retain H execution replay tests exactly; three pass; see S4b validation |
| F094 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicModelsTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicModelsTest.kt | 88317dc814bcd3ff9058031b61650f8138f1f8d8 | 4e69669d1d0ddd50673c3571111e28af232d3854 | 929c1dc046fcc20a8e61dc89422b83499b198835 | S4b | Adapted: all upstream schema and thinking assertions retained, with Sonnet_5 profile and request regressions; see S4b validation |
| F095 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicReasoningReplayTest.kt | absent | absent | f19857dddb51b4e23da784ce1c17e23bac349b67 | S4b | Superseded with equivalence evidence: B=U absent; retain H reasoning and inclusive usage tests exactly; four direct and one Vertex test pass; see S4b validation |
| F096 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicReplayIdentityTest.kt | absent | absent | ad1885bf0f88c7778d20b0a3fb528f6b7a10d5ff | S4b | Superseded with equivalence evidence: B=U absent; retain H replay identity test exactly; missing tool ID rejects before request; see S4b validation |
| F097 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicToolSerializationTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicToolSerializationTest.kt | 0783383c4e6be3eb7864c8e4f95ddeeca1bb48ca | 0783383c4e6be3eb7864c8e4f95ddeeca1bb48ca | fe007db7a2fe9bbf9c588ecd34370c635c11985b | S4b | Superseded with equivalence evidence: B=U; retain H tool serialisation tests exactly; eight pass including anyOf; see S4b validation |
| F098 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/anthropic/AnthropicVertexLLMClientTest.kt | absent | absent | 7ac5fdf7b2e29761dee43749980a10b08c818d73 | S4b | Superseded with equivalence evidence: B=U absent; retain H Vertex tests exactly; eight pass; see S4b validation |
| F099 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/api/jvm/prompt-executor-bedrock-client.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/api/jvm/prompt-executor-bedrock-client.api | ec8e5269d14527c502896a2bd8bfac1c62f0296c | 29de9245957bf9200c0d572d1dcd0c54572c37a1 | bd642355e7e6ce52c6d3a4ba8d873df035534597 | S4d | Adapted with retained Kroog APIs; generated JVM dump, exactly two additive getters versus compiled baseline; pre-existing ordering drift resolved; S4d validation |
| F100 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/api/prompt-executor-bedrock-client.klib.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/api/prompt-executor-bedrock-client.klib.api | 2f012287586a52d7845b3ad6bdaac2b47d6a2544 | 2f012287586a52d7845b3ad6bdaac2b47d6a2544 | c0afbb7df4da5c48265ccee70c9d151af9402ed3 | X-ABI | Explicit exemption: non-JVM ABI; retain H; B=U |
| F101 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/BedrockLLMClient.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/BedrockLLMClient.kt | 060ae617eb88e04b6adedffed9ce0e4aadaef558 | 060ae617eb88e04b6adedffed9ce0e4aadaef558 | eb25f321e0294ea5ac62777d4f5c342dda0a2bf0 | S4d | Adapted with retained Kroog runtime, reasoning, replay, caching and usage contracts; B=U, H retained except additive test coverage; full JVM regressions pass; S4d validation |
| F102 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/BedrockModels.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/BedrockModels.kt | e22e1fcf08d0b742273e5c3721f7e5ba3066e16c | 8049739064957eacad068f88f03fa877d6834551 | 22bb8b06c1fd5ec317244d3970d105c332f1826d | S4d | Adapted with retained Fable 5.1 profile; all U model additions present, Opus 4.8/5 already equivalent; exact new Sonnet 5/Nova 2 Lite definitions and registry; S4d validation |
| F103 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/converse/BedrockConverseConverters.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/converse/BedrockConverseConverters.kt | 0b64c7866376497ef711fbecef00fcaa680e7bd5 | 0b64c7866376497ef711fbecef00fcaa680e7bd5 | 3ab4181cf911f9091254c6bf17ae75ca323d1218 | S4d | Adapted with retained Kroog runtime, reasoning, replay, caching and usage contracts; B=U, H retained except additive test coverage; full JVM regressions pass; S4d validation |
| F104 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/converse/BedrockConverseParams.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/converse/BedrockConverseParams.kt | 97cf4936f767df4f7d75a34feaff703c2421d083 | 97cf4936f767df4f7d75a34feaff703c2421d083 | 359ee2a480d4fdb765b09bad4b919c15ea772bf0 | S4d | Adapted with retained Kroog runtime, reasoning, replay, caching and usage contracts; B=U, H retained except additive test coverage; full JVM regressions pass; S4d validation |
| F105 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/converse/BedrockThinkingConfig.kt | absent | absent | b76825059de7b8229b609cb230cb5ecac64ab7fc | S4d | Adapted with retained Kroog runtime, reasoning, replay, caching and usage contracts; B=U, H retained except additive test coverage; full JVM regressions pass; S4d validation |
| F106 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/modelfamilies/BedrockDataClasses.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/modelfamilies/BedrockDataClasses.kt | 57040c0f7791a297f7b8a2a8e2d58e2fae443803 | 57040c0f7791a297f7b8a2a8e2d58e2fae443803 | 85d572c6f4fe0febdea1d19235b0a3d2878b32ce | S4d | Adapted with retained Kroog runtime, reasoning, replay, caching and usage contracts; B=U, H retained except additive test coverage; full JVM regressions pass; S4d validation |
| F107 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/modelfamilies/amazon/BedrockAmazonNovaSerialization.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/modelfamilies/amazon/BedrockAmazonNovaSerialization.kt | 45175566b4dfa9eaa7fe0b0750f0b4f0fd993b6e | 45175566b4dfa9eaa7fe0b0750f0b4f0fd993b6e | 7b923141cb9f9dba592421c8c2bc6b6118fcc439 | S4d | Adapted with retained Kroog runtime, reasoning, replay, caching and usage contracts; B=U, H retained except additive test coverage; full JVM regressions pass; S4d validation |
| F108 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/modelfamilies/anthropic/BedrockAnthropicClaudeSerialization.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmMain/kotlin/ai/koog/prompt/executor/clients/bedrock/modelfamilies/anthropic/BedrockAnthropicClaudeSerialization.kt | 66e0c4882af53e85e490e79a2cd49762cb51b2b1 | 66e0c4882af53e85e490e79a2cd49762cb51b2b1 | b05e0b82d4839dde489941388df51a53b70ea9f0 | S4d | Adapted with retained Kroog runtime, reasoning, replay, caching and usage contracts; B=U, H retained except additive test coverage; full JVM regressions pass; S4d validation |
| F109 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/bedrock/BedrockAnthropicReplayTest.kt | absent | absent | 6adac2f3baf7c6c794482ec9341ad140dcadfe4b | S4d | Adapted with retained Kroog runtime, reasoning, replay, caching and usage contracts; B=U, H retained except additive test coverage; full JVM regressions pass; S4d validation |
| F110 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/bedrock/BedrockLLMClientTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/bedrock/BedrockLLMClientTest.kt | 904b90f02fa09fb42ca47d76675da1961bc2116f | 904b90f02fa09fb42ca47d76675da1961bc2116f | 5d18d2bf9f9a288b5c461873eb7bc4982470a495 | S4d | Adapted with retained Kroog runtime, reasoning, replay, caching and usage contracts; B=U, H retained except additive test coverage; full JVM regressions pass; S4d validation |
| F111 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/bedrock/BedrockModelsTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/bedrock/BedrockModelsTest.kt | 45bc63b154d46039592297a318570c6b27b8f323 | 45bc63b154d46039592297a318570c6b27b8f323 | 55b91a7fbec5e2810e181be80d1cdba6203ec2df | S4d | Adapted with retained Kroog runtime, reasoning, replay, caching and usage contracts; B=U, H retained except additive test coverage; full JVM regressions pass; S4d validation |
| F112 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/bedrock/converse/BedrockCacheControlTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/bedrock/converse/BedrockCacheControlTest.kt | 338db727da00558803c6f2431648426fb521893d | 338db727da00558803c6f2431648426fb521893d | b2366123b8ee2f1a842acc2765bac5e67d90615f | S4d | Adapted with retained Kroog runtime, reasoning, replay, caching and usage contracts; B=U, H retained except additive test coverage; full JVM regressions pass; S4d validation |
| F113 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/bedrock/converse/BedrockConverseReasoningTest.kt | absent | absent | f6d30a5b3511927a6b6bea1224a32d009872a356 | S4d | Adapted with retained Kroog runtime, reasoning, replay, caching and usage contracts; B=U, H retained except additive test coverage; full JVM regressions pass; S4d validation |
| F114 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/bedrock/converse/BedrockReplayIdentityTest.kt | absent | absent | efa26cebf43a895b3f17417f5a293b334b9e4c6a | S4d | Adapted with retained Kroog runtime, reasoning, replay, caching and usage contracts; B=U, H retained except additive test coverage; full JVM regressions pass; S4d validation |
| F115 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/bedrock/converse/BedrockTokenUsageTest.kt | absent | absent | d52070055ad9cc2ecbf45414ab5dbec855862374 | S4d | Adapted with retained Kroog runtime, reasoning, replay, caching and usage contracts; B=U, H retained except additive test coverage; full JVM regressions pass; S4d validation |
| F116 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/bedrock/modelfamilies/amazon/BedrockAmazonNovaSerializationTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/bedrock/modelfamilies/amazon/BedrockAmazonNovaSerializationTest.kt | b25e9faa678284e90a1f0bf666d03c26d6241693 | b25e9faa678284e90a1f0bf666d03c26d6241693 | 72b529200c48c5c7eecbf099fe8f62436c31d33d | S4d | Adapted with retained Kroog runtime, reasoning, replay, caching and usage contracts; B=U, H retained except additive test coverage; full JVM regressions pass; S4d validation |
| F117 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/bedrock/modelfamilies/anthropic/BedrockAnthropicClaudeSerializationTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-bedrock-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/bedrock/modelfamilies/anthropic/BedrockAnthropicClaudeSerializationTest.kt | fe7dc149ecbc8619ccca470dc61538673c1b5727 | fe7dc149ecbc8619ccca470dc61538673c1b5727 | f92eb6fa872a0671e6f680e0410cbce0e1ee0d22 | S4d | Adapted with retained Kroog runtime, reasoning, replay, caching and usage contracts; B=U, H retained except additive test coverage; full JVM regressions pass; S4d validation |
| F118 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-deepseek-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/deepseek/models/DeepSeekChatCompletion.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-deepseek-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/deepseek/models/DeepSeekChatCompletion.kt | 17910c0b3cece94f313b507b571f799ec6397a97 | 17910c0b3cece94f313b507b571f799ec6397a97 | c68acbbb048e2c378f96e854e68c5a19c82d37a8 | S4e-deepseek | Retained unchanged fork cache-read serializer and inclusive token accounting; non-stream and collected-stream regressions pass; S4e-deepseek validation |
| F119 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-deepseek-client/src/jvmTest/kotlin/deepseek/DeepSeekLLMClientTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-deepseek-client/src/jvmTest/kotlin/deepseek/DeepSeekLLMClientTest.kt | a51c9b8c97e8acd70b8b66e74a64f6c1f61f2b97 | a51c9b8c97e8acd70b8b66e74a64f6c1f61f2b97 | b37a8ce1b3ec30698c3397124f4f920bebd4b2b6 | S4e-deepseek | Retained unchanged usage, reasoning emission and provider replay regressions; full JVM suite passes; S4e-deepseek validation |
| F120 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/api/android/prompt-executor-google-client.api | absent | absent | 05cf6b9ad5ae93cf37fe7b8c8dceb1a7c712818f | X-ABI | Explicit exemption: non-JVM ABI; retain H; B=U |
| F121 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/api/jvm/prompt-executor-google-client.api | absent | absent | 14f5585021f39d3b232d5bcc0ade20bc6b257a0e | S4c | Unchanged JVM dump; before/after compiled ABI identical, pre-existing no-arg constructor drift disclosed; see S4c validation |
| F122 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/api/prompt-executor-google-client.klib.api | absent | absent | ea663cbc2f2299bfb833ef15728251480d5eaded | X-ABI | Explicit exemption: non-JVM ABI; retain H; B=U |
| F123 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/GoogleLLMClient.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/GoogleLLMClient.kt | cff3739025a71c1c06e29abd831a7069960762e7 | 989431decee2ffb551b017918c0d46bdf1ff5969 | 0d20b02ce49d2b6e0342bfc628dcb537d6fda9fc | S4c | Adopted pinned cache metadata at shared conversion; retained structured inclusive usage, signatures, hosted execution and request constraints; see S4c validation |
| F124 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/GoogleModels.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/GoogleModels.kt | fdbe670c33fdba787d934e8ae48b55deb9f19e8f | 024a3a5ef0c38f82e4e86cfc24d44b97012f5c29 | bed1f4657d6eaadffd1bdf796b368b8e88fe7fb9 | S4c | All upstream profiles already present; retained evidenced fixed-sampling and Document patches; adopted introductory pricing qualification; see S4c validation |
| F125 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/GoogleParams.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/GoogleParams.kt | 46c26a6fb1aa810114dcb67278742c764d564887 | 46c26a6fb1aa810114dcb67278742c764d564887 | 792b43383974301c0be83a9845e3b2cdfdb24c4e | S4c | Retained hosted execution configuration and copy contract; 65-test Google suite passes; see S4c validation |
| F126 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/models/GoogleGenerateContent.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/models/GoogleGenerateContent.kt | 2905490b7e1461b105c15640df82cf0fbdb3bd59 | 31ea6c4838d354794171fbbf236b06e7f1f2ca2d | 8d7dede3c042e1f8acf8071de1055267678ab8d1 | S4c | Upstream optional cache wire field already present with inclusive-subset KDoc; retained fork hosted execution and thinking wire types; see S4c validation |
| F127 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/google/GeminiCodeExecutionTest.kt | absent | absent | aab633e09dc4166525283c474d21e4c8d77a60a0 | S4c | Retained hosted execution lifecycle, capability checks and tool selection; nine tests pass; see S4c validation |
| F128 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/google/GeminiReplayTest.kt | absent | absent | dcd8fa9689f1dddad06fa82e7cb897a0c56b0c45 | S4c | Retained signed ordered replay, managed-transcript privacy and malformed rejection; four tests pass; see S4c validation |
| F129 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/google/GeminiStreamingTest.kt | absent | absent | 8ef7fd10363477011e9742aefdb24554e3d8b316 | S4c | Retained hosted streaming order, shared identity and arbitrary signature chunks; three tests pass; see S4c validation |
| F130 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/google/GoogleLLMClientTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/google/GoogleLLMClientTest.kt | f42c6891175c967ad901bb346361f1bcfa53e175 | eb781921ad4e58d5284778f08dfae4394abc849c | 07ce97c022ad5a409337c33883d088bc23366d10 | S4c | Upstream cache assertion covered and extended in GoogleTokenUsageTest across both transports; existing signature and constraint assertions pass; see S4c validation |
| F131 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/google/GoogleModelsTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/google/GoogleModelsTest.kt | 1afb72dfc771152e62554116b7046964e7dc54bb | 1afb72dfc771152e62554116b7046964e7dc54bb | ead852f10d7755ea61a341babc745248122a4d3f | S4c | Retained exact model profiles, Document support and evidenced sampling restrictions; six tests pass; see S4c validation |
| F132 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/google/GoogleTokenUsageTest.kt | absent | absent | 89c56fc80b5528df74b5b95a4fd50e5cb7bbc892 | S4c | Extended metadata parity, missing/zero cache and cumulative streaming replacement regressions; three tests pass; see S4c validation |
| F133 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/google/ThinkingConfigTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/google/ThinkingConfigTest.kt | 1f7cbea22f6c3bbd13df0e9f08ceb7cafd08e29c | 1f7cbea22f6c3bbd13df0e9f08ceb7cafd08e29c | 945a068815d2502376eafe3505e207ed2b9e584f | S4c | Retained thinking-level serialisation and budget/level mutual exclusion; six tests pass; see S4c validation |
| F134 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-ollama-client/src/commonMain/kotlin/ai/koog/prompt/executor/ollama/client/OllamaClient.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-ollama-client/src/commonMain/kotlin/ai/koog/prompt/executor/ollama/client/OllamaClient.kt | 808e1751a1c7ca59ae64f7d76ef5dafda43b0d64 | 808e1751a1c7ca59ae64f7d76ef5dafda43b0d64 | c2b260301d8f814fe9257f2e87b2c4d8f09cc712 | S4e-ollama | Retained H byte-identically: total usage requires both counts; null and explicit zero preserved; S4e-ollama validation |
| F135 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-ollama-client/src/commonTest/kotlin/ai/koog/prompt/executor/ollama/client/OllamaClientTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-ollama-client/src/commonTest/kotlin/ai/koog/prompt/executor/ollama/client/OllamaClientTest.kt | 438bf3363f6ae93c5d0f22c64e20664b80f13816 | 438bf3363f6ae93c5d0f22c64e20664b80f13816 | 5db33e38544ae96cb38924ed3c9ac5a029d88eff | S4e-ollama | Retained H byte-identically; all existing client regressions pass, including five token-count combinations; S4e-ollama validation |
| F136 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base/api/android/prompt-executor-openai-client-base.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base/api/android/prompt-executor-openai-client-base.api | 161858fea22c2ac5de56c8832b8911d856512e5c | 161858fea22c2ac5de56c8832b8911d856512e5c | 473eaf708916692db55d37b3e58c741455436e87 | X-ABI | Explicit exemption: non-JVM ABI; retain H; B=U |
| F137 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base/api/jvm/prompt-executor-openai-client-base.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base/api/jvm/prompt-executor-openai-client-base.api | 161858fea22c2ac5de56c8832b8911d856512e5c | 161858fea22c2ac5de56c8832b8911d856512e5c | 473eaf708916692db55d37b3e58c741455436e87 | R-openai-base | Retained exactly from H; B=U. Eight schema/tool conversion tests pass; provider cache/replay/usage validation remains in S4; see S3 validation |
| F138 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base/api/prompt-executor-openai-client-base.klib.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base/api/prompt-executor-openai-client-base.klib.api | 6bc50de2d29a78132c1b67b670fa54e3817963e5 | 6bc50de2d29a78132c1b67b670fa54e3817963e5 | 092cacf4544834c09213d37052063bba9b249200 | X-ABI | Explicit exemption: non-JVM ABI; retain H; B=U |
| F139 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/base/AbstractOpenAILLMClient.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/base/AbstractOpenAILLMClient.kt | a3929143f0188241e177d877b5d19c51ba7b0978 | a3929143f0188241e177d877b5d19c51ba7b0978 | 5a54ab23fb4ff966792509aaf5d07ba61c177610 | R-openai-base | Retained exactly from H; B=U. Eight schema/tool conversion tests pass; provider cache/replay/usage validation remains in S4; see S3 validation |
| F140 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/base/models/OpenAIDataModels.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/base/models/OpenAIDataModels.kt | 741bfad02a1efeae7a8cc8cad740c573d111ce4a | 741bfad02a1efeae7a8cc8cad740c573d111ce4a | 441a3ceb66ff8fc616980c8fb23277fd639e8b6a | R-openai-base | Retained exactly from H; B=U. Eight schema/tool conversion tests pass; provider cache/replay/usage validation remains in S4; see S3 validation |
| F141 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/CONTAINER_RECOVERY.md | absent | absent | 5d4f7d117c4116298f77111b47e53eea0d4e4081 | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F142 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/IMAGES.md | absent | absent | 8316a32e14b80ac0186367be8e01827d33f4e898 | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F143 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/Module.md | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/Module.md | eed0c13091e3f055ff9dde1acffa1e6ea807bdd0 | eed0c13091e3f055ff9dde1acffa1e6ea807bdd0 | 318094a24e517b0a9495efced552e5c393165225 | S4a | S4a: reconciled Document capability expectations/documentation; retained catalogue and deployment contracts |
| F144 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/api/android/prompt-executor-openai-client.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/api/android/prompt-executor-openai-client.api | c74f4ff31760c726101e983b47ba4c1b2ee045a5 | 6f277bac1b2bf8e219f8253cecae5d77c2163549 | 6f277bac1b2bf8e219f8253cecae5d77c2163549 | X-ABI | Explicit exemption: non-JVM ABI; retain H; U=H |
| F145 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/api/jvm/prompt-executor-openai-client.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/api/jvm/prompt-executor-openai-client.api | c74f4ff31760c726101e983b47ba4c1b2ee045a5 | 6f277bac1b2bf8e219f8253cecae5d77c2163549 | bc946d26f5ddb436142adc3eb53e71d9b8e1044d | S4a | S4a: retained; compiled before/after JVM ABI identical; pre-existing dump drift documented below |
| F146 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/api/prompt-executor-openai-client.klib.api | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/api/prompt-executor-openai-client.klib.api | bd242a538fe7cdc285924182a514346f872b20f9 | a2fb0f38336585f84b59206c00fcf80f66f27ac0 | a0dd44fc33b4596442e6806838fc86972ce673d6 | X-ABI | Explicit exemption: non-JVM ABI; retain H; diverged |
| F147 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/build.gradle.kts | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/build.gradle.kts | 31173e2e195970d481c336c90d218915e8287898 | 31173e2e195970d481c336c90d218915e8287898 | 8456a9816d295b8071916f7ea9c7938f395c0234 | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F148 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIContainerUnavailableException.kt | absent | absent | e239a483c6d07fd2a8dd8be70996408702d2bffa | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F149 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIImages.kt | absent | absent | f53dd527adc68a9984bb99b0b2464b0f5f242808 | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F150 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIImagesClient.kt | absent | absent | 97591f85bdb97cf69683e11f78cdd6990aac34d8 | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F151 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/OpenAILLMClient.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/OpenAILLMClient.kt | 5eafcdd7f53ef5e3f0b71e1909d95a978a4e228b | 5eafcdd7f53ef5e3f0b71e1909d95a978a4e228b | 3ef90e41cc324a44b4241efba1aaeb69783dbc47 | S4a | S4a: bounded family-detection correction preserves older sampling and GPT-5.6 deployment copies; JVM suite passes |
| F152 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIModels.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIModels.kt | 36a58638a2c64950047e9f791b248978e2ea1e3f | 52f76e1699bec554dd5e621a4e279fb394daedb7 | 153beefb583bb48e9cb710805b0fab5cc649eded | S4a | S4a: upstream GPT-5.6 capabilities adopted; later models, deployment copies and catalogue order retained |
| F153 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIParams.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIParams.kt | 917ff1ed22e7813592ab04260729ebe0c38d3220 | 917ff1ed22e7813592ab04260729ebe0c38d3220 | dbe502dae6a4b52aed89c38ac63c6823fb5a493c | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F154 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIPromptCacheKey.kt | absent | absent | e9ccb1a60288ffab3f5b5a9f3dd79590f80cfacf | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F155 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIResources.kt | absent | absent | 91061f038d9983f829301d01395a62ade89585e3 | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F156 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/azure/Azure.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/azure/Azure.kt | cfe0e979f0cf26b1439f44d902331151e346b19f | cfe0e979f0cf26b1439f44d902331151e346b19f | fcf8a8c6857d699666ae96f819a076c9e51654cd | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F157 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/models/OpenAIChatCompletion.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/models/OpenAIChatCompletion.kt | ad0d6a8b3599dd6199ff2aa775e9f7d573868008 | ad0d6a8b3599dd6199ff2aa775e9f7d573868008 | a3f2314b94f3f7076d1987626a2d81171d6aeff4 | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F158 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/models/OpenAIResponsesAPI.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/models/OpenAIResponsesAPI.kt | 3cfde38b90bff1109e0b96cc635f36d1f8d1018e | 3500ba6a093a5ff3b5b11eb17935db4e337f07b3 | 71e80d71d19384f2ef40db9c7aab510fe02b6b95 | S4a | S4a: pinned scalar-or-array serializer adopted; List<Item>? and fork response contracts retained |
| F159 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/AzureResponsesTest.kt | absent | absent | a8c2152552e8aae88588536a4cdb0411b14654ad | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F160 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIChatCompletionLLMClientTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIChatCompletionLLMClientTest.kt | ed2905648058513f54a2d7b5929c93dac326eddc | ed2905648058513f54a2d7b5929c93dac326eddc | 09925d16c83e22db804a53264545fe996f300177 | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F161 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIChatParamsTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIChatParamsTest.kt | 77f500081530717c1cb8f1e5dd3a62044b555466 | 77f500081530717c1cb8f1e5dd3a62044b555466 | c037e340ab01be8259c614858ffb26367349116a | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F162 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAICompatibleTest.kt | absent | absent | d4bc69b2557ea0a0936aef7dce1c55e56fb000fc | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F163 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIImagesClientTest.kt | absent | absent | 69f896c077e5622f79edb51870d9e54b84bc43c1 | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F164 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAILLMClientTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAILLMClientTest.kt | 39b3f775d4f68c848af98d44c29e08850ae3ba7d | 39b3f775d4f68c848af98d44c29e08850ae3ba7d | 04cd8330abcc8cecdcacb00f0f92fc821b388caf | S4a | S4a: bounded family-detection correction preserves older sampling and GPT-5.6 deployment copies; JVM suite passes |
| F165 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIModelsTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIModelsTest.kt | 232a10217571627e7ebafaf26e3a56cc3b0ad9ea | 232a10217571627e7ebafaf26e3a56cc3b0ad9ea | 09733a93f0eff85bf16899b0073ff79d65026ea4 | S4a | S4a: reconciled Document capability expectations/documentation; retained catalogue and deployment contracts |
| F166 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIPrimaryConstructorTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIPrimaryConstructorTest.kt | 3d2e0ee5583b9fed820fae7a7736bf33be85b235 | 3d2e0ee5583b9fed820fae7a7736bf33be85b235 | acafd8f43a14ee3389bf1c08d89d15772eccdd4c | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F167 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIPromptCacheKeyTest.kt | absent | absent | 2eacd58932696b41390ac0dc4b810c6d83e8f1d7 | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F168 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIReplayIdentityTest.kt | absent | absent | 2291f154400295d8c9d69fe9b728a237f283958e | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F169 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIResourcesClientTest.kt | absent | absent | c2314ed9af1614f206075625c66d9e077a0a619a | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F170 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIResponsesParamsTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIResponsesParamsTest.kt | b479a3a71ad6413eee28d0473598c379962168e5 | b479a3a71ad6413eee28d0473598c379962168e5 | 29816de4693c70ac4cd70724f1a4ae0e99fbde0d | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F171 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIResponsesParityTest.kt | absent | absent | 290225af65bbdc07021efb8844bef87927de3363 | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F172 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/OpenAITokenUsageTest.kt | absent | absent | 472490c21815596c6680702dd1887c1d31fe0180 | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F173 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/models/OpenAIRequestSnakeCaseSerializationTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/models/OpenAIRequestSnakeCaseSerializationTest.kt | 3db1d45d7fd42e2ad5d09d0126b7e9f56a11cc9b | 3db1d45d7fd42e2ad5d09d0126b7e9f56a11cc9b | 76f74aae1913750ed7b4f8a4f923d7e8d026f3c7 | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F174 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/models/OpenAIResponsesAPIRequestSerializationTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/models/OpenAIResponsesAPIRequestSerializationTest.kt | 5cc024c7882d5062fea9dd7c54c889d7b7877ea4 | 5cc024c7882d5062fea9dd7c54c889d7b7877ea4 | 2b5b36a8cb8b8248427057b7bb3e72abaeaad302 | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F175 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/models/OpenAIResponsesAPIResponseTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/models/OpenAIResponsesAPIResponseTest.kt | cf9fc56ad4d593793e768911128605cd98951176 | 585898a10701cbde62c0572ec34362df3003e673 | 2bd3601e3f4578afe7162e0eba6eecafbfefbc6e | S4a | S4a: upstream scalar/string-array/item-array regressions adopted; null, missing, empty and array-output coverage added |
| F176 | M | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/models/OpenAIToolsTest.kt | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/kotlin/ai/koog/prompt/executor/clients/openai/models/OpenAIToolsTest.kt | db90039dafbc18ad0cf9c5939de6a3e54e1027e0 | db90039dafbc18ad0cf9c5939de6a3e54e1027e0 | d7f5ebf942493846cd37b6b4b4ae35c218b7eb5d | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F177 | A | absent | prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/jvmTest/resources/ai/koog/prompt/executor/clients/openai/azure-responses-dated-preview-request.json | absent | absent | b548328eb58cc696a33d46f4b36c750525976b37 | S4a | S4a: source equals H; retained contract assertions inspected and full OpenAI JVM suite passes (evidence below) |
| F178 | M | prompt/prompt-executor/prompt-executor-clients/src/commonMain/kotlin/ai/koog/prompt/executor/clients/retry/RetryConfig.kt | prompt/prompt-executor/prompt-executor-clients/src/commonMain/kotlin/ai/koog/prompt/executor/clients/retry/RetryConfig.kt | f57c83fe7ffbd28caf27d4cafe98dc97ff39ae89 | f57c83fe7ffbd28caf27d4cafe98dc97ff39ae89 | 1eae71e64a6d917d3a597fcf53030d67124d1b9e | S3 | Retained exactly from H; B=U. HTTP, SSE, retry and replay assertions pass; see S3 validation |
| F179 | M | prompt/prompt-executor/prompt-executor-clients/src/commonMain/kotlin/ai/koog/prompt/executor/clients/retry/RetryingLLMClient.kt | prompt/prompt-executor/prompt-executor-clients/src/commonMain/kotlin/ai/koog/prompt/executor/clients/retry/RetryingLLMClient.kt | c940690cce3ae96c92395e82c26f9045f7bd7a6c | c940690cce3ae96c92395e82c26f9045f7bd7a6c | 99545ecf6cbc532596a1ce417d0032bcf60dae88 | S3 | Retained exactly from H; B=U. HTTP, SSE, retry and replay assertions pass; see S3 validation |
| F180 | A | absent | prompt/prompt-executor/prompt-executor-clients/src/commonTest/kotlin/ai/koog/prompt/executor/clients/StreamIdentityReplayTest.kt | absent | absent | 2bac6e22d420c1645ed08ada835d76b71532a7a2 | S3 | Retained exactly from H; B=U. HTTP, SSE, retry and replay assertions pass; see S3 validation |
| F181 | M | prompt/prompt-executor/prompt-executor-clients/src/commonTest/kotlin/ai/koog/prompt/executor/clients/retry/RetryConfigTest.kt | prompt/prompt-executor/prompt-executor-clients/src/commonTest/kotlin/ai/koog/prompt/executor/clients/retry/RetryConfigTest.kt | 36c4521fab4eaa71787701ee71b80fcc9f37a274 | 36c4521fab4eaa71787701ee71b80fcc9f37a274 | e6ffe41f8444b84762e1a60c0dcf10f6dd026650 | S3 | Retained exactly from H; B=U. HTTP, SSE, retry and replay assertions pass; see S3 validation |
| F182 | M | prompt/prompt-executor/prompt-executor-clients/src/commonTest/kotlin/ai/koog/prompt/executor/clients/retry/RetryingLLMClientTest.kt | prompt/prompt-executor/prompt-executor-clients/src/commonTest/kotlin/ai/koog/prompt/executor/clients/retry/RetryingLLMClientTest.kt | daffef7aa99c2a2a248efa3b250c800aedcf7c37 | daffef7aa99c2a2a248efa3b250c800aedcf7c37 | 95bc6d5761085796d0e0cdfcfd1b2fee431af4d0 | S3 | Retained exactly from H; B=U. HTTP, SSE, retry and replay assertions pass; see S3 validation |
| F183 | A | absent | prompt/prompt-executor/prompt-executor-managed-execution/api/jvm/prompt-executor-managed-execution.api | absent | absent | e0993a1994e4d7d1ae3ee63c0d9fce109e61b155 | S5a | Retained JVM dump exactly from H; no slice API change; compiled dump comparison not run; see S5a and R-runtime validation |
| F184 | A | absent | prompt/prompt-executor/prompt-executor-managed-execution/build.gradle.kts | absent | absent | fe50d2b41d98d4f0e827bc525276c9c52cd49fda | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F185 | A | absent | prompt/prompt-executor/prompt-executor-managed-execution/src/commonMain/kotlin/ai/koog/prompt/executor/managed/ManagedExecution.kt | absent | absent | c7851850a9c42646c321f62a3754890d15d4a1d5 | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F186 | A | absent | prompt/prompt-executor/prompt-executor-managed-execution/src/commonMain/kotlin/ai/koog/prompt/executor/managed/ManagedExecutionPresentation.kt | absent | absent | 5a2d2b7b213dfbac6cf552b00a873fa8415fa6ff | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F187 | A | absent | prompt/prompt-executor/prompt-executor-managed-execution/src/commonMain/kotlin/ai/koog/prompt/executor/managed/Sha256.kt | absent | absent | 5452fa1ed2fa66c134f8afe5ba0886d068ffb75f | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F188 | A | absent | prompt/prompt-executor/prompt-executor-managed-execution/src/commonMain/kotlin/ai/koog/prompt/executor/managed/VertexAgentEngineManagedExecutionService.kt | absent | absent | 4672d53014209afd031e06d7d988ac8a3df425c0 | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F189 | A | absent | prompt/prompt-executor/prompt-executor-managed-execution/src/commonMain/kotlin/ai/koog/prompt/executor/managed/VertexAgentEngineModels.kt | absent | absent | 4fdb2e56b5d2edda0da6c9989388a2116cd75c8e | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F190 | A | absent | prompt/prompt-executor/prompt-executor-managed-execution/src/commonTest/kotlin/ai/koog/prompt/executor/managed/ManagedExecutionPresentationTest.kt | absent | absent | f50b22533cb16fd446f5efa7dd8e2a045160e66c | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F191 | A | absent | prompt/prompt-executor/prompt-executor-managed-execution/src/commonTest/kotlin/ai/koog/prompt/executor/managed/ManagedExecutionTest.kt | absent | absent | 28272eee5b8d3c5fc152e79b5f14870ba33b88f4 | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F192 | A | absent | prompt/prompt-executor/prompt-executor-managed-execution/src/commonTest/kotlin/ai/koog/prompt/executor/managed/VertexAgentEngineManagedExecutionServiceTest.kt | absent | absent | ed8e9daa2e9693de30f4316f153e611167283e57 | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F193 | A | absent | prompt/prompt-executor/prompt-executor-managed-execution/src/jvmMain/kotlin/ai/koog/prompt/executor/managed/BedrockAgentCoreManagedExecutionService.kt | absent | absent | b1f9d90e6f1f9f1986a4b6b745507b6b1748e239 | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F194 | A | absent | prompt/prompt-executor/prompt-executor-managed-execution/src/jvmMain/kotlin/ai/koog/prompt/executor/managed/BedrockAgentCoreModels.kt | absent | absent | c19c4d50a158bbaba8ab58d0419a98a744a3a987 | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F195 | A | absent | prompt/prompt-executor/prompt-executor-managed-execution/src/jvmTest/kotlin/ai/koog/prompt/executor/managed/BedrockAgentCoreManagedExecutionServiceTest.kt | absent | absent | 77d55bfd03a755632a989ac3df77561fe53f8df8 | S5a | Retained exactly from H; B=U; managed execution, privacy and replay regressions pass; see S5a and R-runtime validation |
| F196 | M | prompt/prompt-model/Module.md | prompt/prompt-model/Module.md | 2cdcd7c868b540bede5b5a8b5e14bea5c9f9be53 | 2cdcd7c868b540bede5b5a8b5e14bea5c9f9be53 | e48053330402ae448f0c70d8fc48220d34738c93 | S2 | Retained exactly from H; B=U. Inclusive usage documentation retained; TokenUsageMetadataTest and TokenUsageBuilderTest pass; see S2 validation |
| F197 | M | prompt/prompt-model/api/android/prompt-model.api | prompt/prompt-model/api/android/prompt-model.api | 1f562d91a662236d327e2894af76d5249890db15 | 1f562d91a662236d327e2894af76d5249890db15 | 66f926703d0e9f11ed9e17eddf1431ff96e6c695 | X-ABI | Explicit exemption: non-JVM ABI; retain H; B=U |
| F198 | M | prompt/prompt-model/api/jvm/prompt-model.api | prompt/prompt-model/api/jvm/prompt-model.api | f468603112d8ffd80c608558b5e699d82ee307a1 | f468603112d8ffd80c608558b5e699d82ee307a1 | 48ef5fc305663f0a0a74e64fe42c18ca037db6e3 | S2 | Retained exactly from H; B=U. Checked-in JVM dump retained; reliable JVM comparison exposes pre-existing drift; legacy descriptor regressions pass; see S2 validation |
| F199 | M | prompt/prompt-model/api/prompt-model.klib.api | prompt/prompt-model/api/prompt-model.klib.api | e22b54e746ddafb3941486ddf64d8394f1fa67bd | e22b54e746ddafb3941486ddf64d8394f1fa67bd | 42793cb61c4375da0b0f324ef381a01be7b1da61 | X-ABI | Explicit exemption: non-JVM ABI; retain H; B=U |
| F200 | A | absent | prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/cache/PromptCachePolicy.kt | absent | absent | d7dce559801daa5e969b998a25c2f61ea97f435d | S2 | Retained exactly from H; B=U. PromptCachePolicyTest: 12 pass, request-view copies, TTL ordering and breakpoint limits; see S2 validation |
| F201 | M | prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/message/CacheControl.kt | prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/message/CacheControl.kt | 290ffba8586056124192c4936172a718328af10e | 290ffba8586056124192c4936172a718328af10e | 21be1c04577fdfe04334045010dc0e342e52c901 | S2 | Retained exactly from H; B=U. PromptCachePolicyTest: 12 pass, cache control and TTL rules; see S2 validation |
| F202 | A | absent | prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/message/ManagedExecutionPresentation.kt | absent | absent | eddaa1db76ee5d16ee7ec105f2557fbd75ba33d7 | S2 | Retained exactly from H; B=U. ManagedExecutionPresentationReplayTest: 5 pass, ordered transcripts, pairing, origins and descriptors; see S2 validation |
| F203 | M | prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/message/Message.kt | prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/message/Message.kt | 99e562dd9c7ba1e7b81f0a01701bb6af1fe51ad5 | 99e562dd9c7ba1e7b81f0a01701bb6af1fe51ad5 | b0d4d2fc3068ff1debcad9146e0b5c328898cbb3 | S2 | Retained exactly from H; B=U. HostedExecutionPromptTest: 9 pass; usage metadata: 3 pass; replay validation: 5 pass; see S2 validation |
| F204 | A | absent | prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/models/ModelCatalogue.kt | absent | absent | 7203a52cffa539254e18f1e10da69f9fa3f761a7 | S2 | Retained exactly from H; B=U. ModelCatalogueTest: 12 pass, exact 38-entry golden, identities, capabilities and invalid entries; see S2 validation |
| F205 | A | absent | prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/provider/ProviderCapabilities.kt | absent | absent | 3481eb88ff3a9b66adb1d794842c356ac5ce8ddf | S2 | Retained exactly from H; B=U. ProviderCapabilityMatrixTest: 7 pass; LegacyDefaultArgumentAbiTest: 3 pass; see S2 validation |
| F206 | M | prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/streaming/StreamFrame.kt | prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/streaming/StreamFrame.kt | 6b824070d9a8a44865afb74aa96f7f70ad43adc2 | 6b824070d9a8a44865afb74aa96f7f70ad43adc2 | 22bf3eeebbaeecaa15f81f7b5e26567947bb816c | S2 | Retained exactly from H; B=U. HostedExecutionPromptTest: 9 pass; ManagedGeneratedFileFrameTest: 11 pass; usage metadata: 3 pass; see S2 validation |
| F207 | M | prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/streaming/StreamFrameExt.kt | prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/streaming/StreamFrameExt.kt | fb2fa38781817600b6fb8b304308c20a4c5287a3 | fb2fa38781817600b6fb8b304308c20a4c5287a3 | 0f34ce6fddfb7c33e2066f39856d900a09368f92 | S2 | Retained exactly from H; B=U. StreamFrameExtTest: 14 pass; hosted execution: 9 pass; managed generated files: 11 pass; see S2 validation |
| F208 | M | prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/streaming/StreamFrameFlowBuilder.kt | prompt/prompt-model/src/commonMain/kotlin/ai/koog/prompt/streaming/StreamFrameFlowBuilder.kt | 6f8ec00b9d76fef82e3eada448c9197d80be1cc3 | 6f8ec00b9d76fef82e3eada448c9197d80be1cc3 | bdf7767e3622375b5f4e2076666e3d3a508ad6b9 | S2 | Retained exactly from H; B=U. StreamFrameFlowBuilderTest: 33 pass, parallel calls, signed reasoning and identity ordering; see S2 validation |
| F209 | A | absent | prompt/prompt-model/src/commonTest/kotlin/ai/koog/prompt/HostedExecutionPromptTest.kt | absent | absent | 96b5b828bcc205a60799824f44d3385b013db5bd | S2 | Retained exactly from H; B=U. HostedExecutionPromptTest: all 9 pass; assertions inspected; see S2 validation |
| F210 | A | absent | prompt/prompt-model/src/commonTest/kotlin/ai/koog/prompt/ProviderItemIdTest.kt | absent | absent | 5e9ea40c7caf2a659152442e192d12df8c78d224 | S2 | Retained exactly from H; B=U. ProviderItemIdTest: 1 pass, equal call IDs retain distinct provider identities; see S2 validation |
| F211 | A | absent | prompt/prompt-model/src/commonTest/kotlin/ai/koog/prompt/cache/PromptCachePolicyTest.kt | absent | absent | bfafc2b91a88e3cd65314356c7fb32428aa6cf0f | S2 | Retained exactly from H; B=U. PromptCachePolicyTest: all 12 pass; assertions inspected; see S2 validation |
| F212 | M | prompt/prompt-model/src/commonTest/kotlin/ai/koog/prompt/streaming/StreamFrameExtTest.kt | prompt/prompt-model/src/commonTest/kotlin/ai/koog/prompt/streaming/StreamFrameExtTest.kt | fcd2a8f3054504f7525e646b35a12eedf26ee386 | fcd2a8f3054504f7525e646b35a12eedf26ee386 | 6c9130f0da64a45b52dd1546b917176fd8e35055 | S2 | Retained exactly from H; B=U. StreamFrameExtTest: all 14 pass, including successful and failed code-execution round trips; see S2 validation |
| F213 | M | prompt/prompt-model/src/commonTest/kotlin/ai/koog/prompt/streaming/StreamFrameFlowBuilderTest.kt | prompt/prompt-model/src/commonTest/kotlin/ai/koog/prompt/streaming/StreamFrameFlowBuilderTest.kt | 8df7df289bf5093e5d6ce5f18bccf689afd9df90 | 8df7df289bf5093e5d6ce5f18bccf689afd9df90 | a3765fede1b8045525c895e3140b9af01c139ac0 | S2 | Retained exactly from H; B=U. StreamFrameFlowBuilderTest: all 33 pass; assertions inspected; see S2 validation |
| F214 | A | absent | prompt/prompt-model/src/commonTest/kotlin/ai/koog/prompt/streaming/TokenUsageMetadataTest.kt | absent | absent | 48b0b8f12bbf2fe4bd07ed8cfbcac4739d442a7a | S2 | Retained exactly from H; B=U. TokenUsageMetadataTest: all 3 pass, inclusive totals, null versus zero and serialisation; see S2 validation |
| F215 | M | prompt/prompt-model/src/jvmMain/kotlin/ai/koog/prompt/message/ResponseMetaInfoBuilder.kt | prompt/prompt-model/src/jvmMain/kotlin/ai/koog/prompt/message/ResponseMetaInfoBuilder.kt | 4a269923d7f4728b4b0c463428c2f8c0c4682520 | 4a269923d7f4728b4b0c463428c2f8c0c4682520 | 7ef138d53011da3e0add98ec38a668071c6ee869 | S2 | Retained exactly from H; B=U. TokenUsageBuilderTest: 3 pass, old Java signature, typed breakdowns and unknown counts; see S2 validation |
| F216 | A | absent | prompt/prompt-model/src/jvmTest/kotlin/ai/koog/prompt/LegacyDefaultArgumentAbiTest.kt | absent | absent | 9427181e2e98b110fdfea203c4640e10c59d8e62 | S2 | Retained exactly from H; B=U. LegacyDefaultArgumentAbiTest: all 3 pass, constructor references, defaults and exact copy descriptors; see S2 validation |
| F217 | A | absent | prompt/prompt-model/src/jvmTest/kotlin/ai/koog/prompt/message/ManagedExecutionPresentationReplayTest.kt | absent | absent | ef136cfa746903001ed612e3597949b113b25d71 | S2 | Retained exactly from H; B=U. ManagedExecutionPresentationReplayTest: all 5 pass; assertions inspected; see S2 validation |
| F218 | A | absent | prompt/prompt-model/src/jvmTest/kotlin/ai/koog/prompt/message/TokenUsageBuilderTest.kt | absent | absent | ff36e3f5280e452ae5fb6fe40ec14aac5774c30f | S2 | Retained exactly from H; B=U. TokenUsageBuilderTest: all 3 pass; assertions inspected; see S2 validation |
| F219 | A | absent | prompt/prompt-model/src/jvmTest/kotlin/ai/koog/prompt/models/ModelCatalogueTest.kt | absent | absent | 09d209f5f52bdcc9d6abd53ffd7c16f8266de09d | S2 | Retained exactly from H; B=U. ModelCatalogueTest: all 12 pass; assertions inspected; see S2 validation |
| F220 | A | absent | prompt/prompt-model/src/jvmTest/kotlin/ai/koog/prompt/provider/ProviderCapabilityMatrixTest.kt | absent | absent | 67a609c4aa2085da8323ea35870b9f0bd51e53ec | S2 | Retained exactly from H; B=U. ProviderCapabilityMatrixTest: all 7 pass; assertions inspected; see S2 validation |
| F221 | A | absent | prompt/prompt-model/src/jvmTest/kotlin/ai/koog/prompt/streaming/ManagedGeneratedFileFrameTest.kt | absent | absent | 10f538c01bf188334b03a6ec704dc7fb874daf1f | S2 | Retained exactly from H; B=U. ManagedGeneratedFileFrameTest: all 11 pass; assertions inspected; see S2 validation |
| F222 | A | absent | prompt/prompt-model/src/jvmTest/resources/model-catalogue/krellm-model-catalogue.txt | absent | absent | 6859a90a49d7e1ad95fa695953d0c56679fc8a69 | S2 | Retained exactly from H; B=U. ModelCatalogueTest exact normalised golden comparison passes; 38 semantic IDs retained; see S2 validation |
| F223 | M | prompt/prompt-tokenizer/src/commonMain/kotlin/ai/koog/prompt/tokenizer/PromptTokenizer.kt | prompt/prompt-tokenizer/src/commonMain/kotlin/ai/koog/prompt/tokenizer/PromptTokenizer.kt | 2c8aeaee0cdc5aadbe2335edcaabf31243fb8410 | 2c8aeaee0cdc5aadbe2335edcaabf31243fb8410 | 7d7f692b5c2c615581bbb666653bf2980f82b745 | S5c | Retained H unchanged; assigned JVM presentation regressions passed; see S5c validation; B=U |
| F224 | M | prompt/prompt-tokenizer/src/commonTest/kotlin/ai/koog/prompt/tokenizer/PromptTokenizerTest.kt | prompt/prompt-tokenizer/src/commonTest/kotlin/ai/koog/prompt/tokenizer/PromptTokenizerTest.kt | c77a34e68cc331c4f8f3da98544cdc2219442360 | c77a34e68cc331c4f8f3da98544cdc2219442360 | e442082adb31c7229bfb243ddb76c9338e7609b1 | S5c | Retained H unchanged; assigned JVM presentation regressions passed; see S5c validation; B=U |
| F225 | M | settings.gradle.kts | settings.gradle.kts | 451015ad7f16f75f76bf768cc111799d99901215 | 53638c13fdc85b4de98fe2dd1243421ffd55e7ee | 47256fc64a231c1fe1cddb8d8c7a08d09a615ba8 | S1 | Adapted with Kroog module graph; upstream skills inclusion already present, retained H; S1 |
| F226 | A | absent | skills-api-consumer-test/build.gradle.kts | absent | absent | 054fcc7a08fabb4bfd4e088062ff36b7d161cc9d | S1 | Adapted with retained Kroog build contract; B=U, H preserved byte-for-byte; S1 source evidence, runtime regression gate in owning slice |
| F227 | A | absent | skills-api-consumer-test/src/main/kotlin/ai/koog/skills/consumer/SkillsApiConsumer.kt | absent | absent | 2f60934704e3b2a216ec23643e85fb0e1eb503b7 | S6c | Pending regression evidence: retain fork extension; B=U |
| F228 | A | absent | skills/README.md | absent | absent | 6f4b2a776e04ecbe046d5cdf02c190b4fcaff53a | S7-docs | Pending regression evidence: retain fork extension; B=U |
| F229 | A | absent | skills/build.gradle.kts | absent | 234f2e0d5ccfc02e0f65ac1bfbf4f342665f5dc8 | a40ddc6a83f78c3aaf2d097965ad2cd448da90d5 | S1 | Adapted with Kroog JVM server convention, tools and JSON APIs, SnakeYAML and upstream dependencies; S1 |
| F230 | A | absent | skills/src/commonMain/kotlin/ai/koog/skills/LoadSkillTool.kt | absent | absent | c29d00c7e63135c314de6ef909236c3d9d531cb9 | S6c | Pending regression evidence: retain fork extension; B=U |
| F231 | A | absent | skills/src/commonMain/kotlin/ai/koog/skills/Skill.kt | absent | absent | 395ef49722b5ea6d0a6a55df0a31f795f3e7c8a7 | S6a | Pending regression evidence: retain fork extension; B=U |
| F232 | A | absent | skills/src/commonMain/kotlin/ai/koog/skills/SkillCatalogue.kt | absent | absent | c127233501b83352c8b811e7d73565456974c76f | S6a | Pending regression evidence: retain fork extension; B=U |
| F233 | A | absent | skills/src/commonMain/kotlin/ai/koog/skills/SkillError.kt | absent | absent | 991bb8afd55587a9a9adf390c654cfce13083ab6 | S6a | Pending regression evidence: retain fork extension; B=U |
| F234 | A | absent | skills/src/commonMain/kotlin/ai/koog/skills/SkillPolicy.kt | absent | absent | fd4b981bc1a1076dd8a9931daf260aa6c4db5e0d | S6a | Pending regression evidence: retain fork extension; B=U |
| F235 | A | absent | skills/src/commonMain/kotlin/ai/koog/skills/SkillRegistry.kt | absent | absent | 1b36e01b1487442358aac024045f00d415f530dd | S6a | Pending regression evidence: retain fork extension; B=U |
| F236 | A | absent | skills/src/commonMain/kotlin/ai/koog/skills/SkillSource.kt | absent | absent | f0fe01ed1b509ec8472f931006fb44a24d6da2e2 | S6a | Pending regression evidence: retain fork extension; B=U |
| F237 | A | absent | skills/src/commonTest/kotlin/ai/koog/skills/InMemorySkillSourceTest.kt | absent | absent | 822d7ccd3144b73d8485edcc5872ab5ab5cca4cf | S6a | Pending regression evidence: retain fork extension; B=U |
| F238 | A | absent | skills/src/commonTest/kotlin/ai/koog/skills/LoadSkillToolTest.kt | absent | absent | 132bfdca9b46de5210c4f309ddef3bc6884702a6 | S6c | Pending regression evidence: retain fork extension; B=U |
| F239 | A | absent | skills/src/commonTest/kotlin/ai/koog/skills/SkillCatalogueTest.kt | absent | absent | d7d9a058d0d424fbc49c43557d97a9f59a6793ce | S6a | Pending regression evidence: retain fork extension; B=U |
| F240 | A | absent | skills/src/commonTest/kotlin/ai/koog/skills/SkillRegistryTest.kt | absent | absent | be93e41588b9b331fcd7ae5c73a194bcd815221a | S6a | Pending regression evidence: retain fork extension; B=U |
| F241 | A | absent | skills/src/jvmMain/kotlin/ai/koog/skills/JvmFileSystemSkillSource.kt | absent | absent | 4681c773d79f21b3f1047694aad91aefc00ba429 | S6a | Pending regression evidence: retain fork extension; B=U |
| F242 | A | absent | skills/src/jvmMain/kotlin/ai/koog/skills/SkillParser.kt | absent | absent | 590243e2f11edf725e4e2105ebb95d180e4ddda9 | S6a | Pending regression evidence: retain fork extension; B=U |
| F243 | A | absent | skills/src/jvmTest/kotlin/ai/koog/skills/JvmFileSystemSkillSourceTest.kt | absent | absent | 5b7558d02ac3654c38356c483ad8f758ed8de4b4 | S6a | Pending regression evidence: retain fork extension; B=U |
| F244 | A | absent | skills/src/jvmTest/kotlin/ai/koog/skills/LoadSkillToolAgentIntegrationTest.kt | absent | absent | a3d02b3cdf3e328f133ccc6039404bb86a44fa08 | S6c | Pending regression evidence: retain fork extension; B=U |
| F245 | A | absent | skills/src/jvmTest/kotlin/ai/koog/skills/SkillParserTest.kt | absent | absent | 4afb3836dcc4d107f4abcb90e0ca654ba1e9f7d8 | S6a | Pending regression evidence: retain fork extension; B=U |

## Exact slice memberships

A group with no U rows requires retained-contract validation and no upstream import unless a dependency demands an evidenced adaptation. The explicit S6a consumer advance above is a shared-path exception.

| Slice | Upstream rows | Fork rows |
| --- | --- | --- |
| R-http-fixtures | none | F062, F063 |
| R-integration | U001, U014, U015, U016 | F064, F065, F066 |
| R-ktor | none | F071, F072 |
| R-openai-base | none | F137, F139, F140 |
| R-runtime | none | F070 |
| S1 | U013, U017, U018, U047, U049 | F007, F023, F038, F039, F040, F041, F042, F043, F044, F045, F046, F054, F067, F068, F069, F073, F074, F075, F076, F077, F078, F079, F080, F082, F147, F184, F225, F226, F229 |
| S2 | none | F196, F198, F200, F201, F202, F203, F204, F205, F206, F207, F208, F209, F210, F211, F212, F213, F214, F215, F216, F217, F218, F219, F220, F221, F222 |
| S3 | none | F055, F056, F057, F058, F059, F060, F061, F081, F178, F179, F180, F181, F182 |
| S4a | U038, U040, U041, U042 | F141, F142, F143, F145, F148, F149, F150, F151, F152, F153, F154, F155, F156, F157, F158, F159, F160, F161, F162, F163, F164, F165, F166, F167, F168, F169, F170, F171, F172, F173, F174, F175, F176, F177 |
| S4b | U020, U022, U023 | F083, F085, F087, F088, F089, F090, F091, F092, F093, F094, F095, F096, F097, F098 |
| S4c | U028, U029, U030, U031 | F121, F123, F124, F125, F126, F127, F128, F129, F130, F131, F132, F133 |
| S4d | U024, U025 | F099, F101, F102, F103, F104, F105, F106, F107, F108, F109, F110, F111, F112, F113, F114, F115, F116, F117 |
| S4e-dashscope | U026 | none |
| S4e-deepseek | U027 | F118, F119 |
| S4e-mistralai | U032 | none |
| S4e-ollama | U034, U036 | F134, F135 |
| S4e-openrouter | U044, U046 | none |
| S5a | none | F006, F008, F009, F014, F015, F016, F019, F020, F021, F022, F183, F185, F186, F187, F188, F189, F190, F191, F192, F193, F194, F195 |
| S5b | none | F010, F011, F012, F013, F017, F018, F036, F037 |
| S5c | U004, U005 | F024, F025, F026, F027, F028, F029, F030, F031, F032, F033, F034, F035, F223, F224 |
| S6a | U050, U051, U052, U053, U054 | F231, F232, F233, F234, F235, F236, F237, F239, F240, F241, F242, F243, F245 |
| S6c | none | F227, F230, F238, F244 |
| S7 | none | F001, F002, F005, F053 |
| S7-docs | U002, U003, U006, U007, U008, U009, U010, U011, U048 | F047, F048, F049, F050, F051, F228 |
| X-ABI | U019, U021, U033, U035, U037, U039, U043, U045 | F084, F086, F100, F120, F122, F136, F138, F144, F146, F197, F199 |
| X-local | none | F003, F004 |
| X-version | U012 | F052 |

## Complete fork behavioural history

Enumerated from git log --reverse B..H: all 70 ancestry commits, including pre-merge work. Recorded subjects identify features and fixes. Each row maps surviving changed paths to exact ledger code and blobs. Regression entries identify surviving tests changed in that commit; otherwise the assigned slice tests need assertion review. Public API effects require the assigned JVM ABI gate. Checkpoints are pending. Merge rows preserve provenance; release rows preserve controls without authorising a bump.

| Commit | Recorded behaviour or maintenance change | Retained code evidence | Regression evidence | Public API gate and checkpoint |
| --- | --- | --- | --- | --- |
| 6dc856d99389602a3f6340647c965b106f564927 | fix(openai): preserve streaming tool call identity | F151, F166 | F166 | S4a; pending |
| 250ad21fc9ce7022e84799d6f756c594051ed067 | Merge pull request #1 from Kreoh/feat/tool-call-ids | F151, F166 | F166 | S4a; pending |
| 37ddaf65f38c2851a3ff1148783ad21b14a77252 | build(publishing): configure Central snapshots | F005, F038, F039, F041, F043, F045, F049, U012, U047 | F045 | S1, S7, S7-docs; pending |
| 586e38a084ae184172abc5048980d9186c939e66 | ci(publishing): add manual snapshot workflow | F002, F005 | Slice regressions; assertion review pending | S7; pending |
| 2c56ea2fb6fabfa01f3e08760698af1543a2c816 | perf(publishing): optimise JVM snapshot workflow | F002 | Slice regressions; assertion review pending | S7; pending |
| 62039470c0ff15e85dfccb3be8fa6ed0ce085f82 | fix(openai): emit completed streaming text | F151, F166 | F166 | S4a; pending |
| 5c1924af32b7ecab00ea78a5369e7c99c837a12a | feat(openai): support xhigh reasoning effort | F140, F173 | F173 | R-openai-base, S4a; pending; S3 transport/retry and unchanged prerequisites verified, other named slices remain pending; see S3 validation |
| 4ef675dc1d7941f13d7f3014854af27e92ead387 | fix(streaming): preserve parallel tool calls | F208, F213 | F213 | S2; pending |
| 139d5e6d54e5009efc6910aeae5418e4ea8d4bb2 | feat(bedrock): expose runtime client injection | F101, F110 | F110 | S4d; pending; S4d verified, see S4d validation |
| 3b49937cfe1540e646da00b0443ed9fb5c29cf73 | feat(bedrock): stream signed reasoning | U024, F103, F104, F105, F110, F113, F197, F198, F199, F208, F213 | F110, F113, F213 | S2, S4d; pending; S4d verified, see S4d validation |
| 804d8f6c2185bc847229a70d445a96cf4dc0bf79 | fix(google): preserve streaming tool signatures | U028, U031 | U031 | S4c; pending; S4c verified, see S4c validation |
| 497725844dae84ccda2abcbafa2ed736f3018cfb | feat(google): add thinking levels | F120, F121, F122, U030, F133 | F133 | S4c; pending; S4c verified, see S4c validation |
| 733a328b7b1b082812af09853b6947cdb02c99ca | fix(google): preserve thought signatures | U028, U031 | U031 | S4c; pending; S4c verified, see S4c validation |
| 0587101f0931d647106f7fec4b0aaaeec9907d89 | feat(anthropic): add Vertex client | F083, U019, U020, U021, F087, F089, F090, F091, F098 | F091, F098 | S4b; pending; S4b verified, see S4b validation |
| 3d8ab81907187bfe638a3c46cf473a303e829873 | feat(openai): add GPT-5.6 models | F136, F137, F138, F140, F143, U037, U038, U039, F151, U040, F160, F164, F165, F173, F174 | F160, F164, F165, F173, F174 | R-openai-base, S4a; pending; S3 transport/retry and unchanged prerequisites verified, other named slices remain pending; see S3 validation |
| 967531a8cf6ea97be8030c1cafea3182605e98d1 | fix(anthropic): support anyOf tool schemas | F087, F097 | F097 | S4b; pending; S4b verified, see S4b validation |
| 41d849f79aa90336c44f54c79dbac4a93c7b0c2e | fix(openai): accept streaming choices without delta | F140, F166 | F166 | R-openai-base, S4a; pending; S3 transport/retry and unchanged prerequisites verified, other named slices remain pending; see S3 validation |
| 4a1c37b1914b1529be96b4752807ca14ef8f591e | feat(models): add Claude Opus 4.8 | U019, U020, U021, U022, U023, U024, F100, U025, F111 | U023, F111 | S4b, S4d; pending; S4b verified, see S4b validation; S4d verified, see S4d validation |
| 171c8a220f0913fc456b68071d9abc27a8e60880 | feat(openai): stream code interpreter responses | F004, F023, F024, F025, F027, F028, F031, F032, F033, F034, F051, F087, F093, F103, F107, F108, F112, F116, F117, U028, U038, F151, F153, U041, F166, F170, F176, F198, F203, F206, F207, F212, F223, F224 | F025, F028, F032, F034, F093, F112, F116, F117, F166, F170, F176, F212, F224 | S1, S2, S4a, S4b, S4c, S4d, S5c, S7-docs; pending; S4b verified, see S4b validation; S4c verified, see S4c validation; S4d verified, see S4d validation; S5c verified only F024, F025, F027, F028, F031, F032, F033, F034, F223, F224, see S5c validation |
| 2d5dbec84e11beff22533e47a30a70d178df2044 | feat(prompt): add provider replay primitives | F050, F180, F203, F206, F207, F208, F209 | F180, F209 | S2, S3, S7-docs; pending; S3 transport/retry and unchanged prerequisites verified, other named slices remain pending; see S3 validation |
| 250069ec648f647694ebfda9984bc589f98a3447 | feat(http): add provider resource APIs | F055, F056, F057, F060, F061, F062, F063, F151, F155, F166, F169 | F057, F061, F062, F166, F169 | R-http-fixtures, S3, S4a; pending; S3 transport/retry and unchanged prerequisites verified, other named slices remain pending; see S3 validation |
| 8483123ee8ea6ccc7450a5de0213a171fdf405fa | feat(openai): unify responses execution | U038, F151, F153, F156, F159, F162, F166, F170, F171, F177 | F159, F162, F166, F170, F171, F177 | S4a; pending |
| 4f955e20778361b4998195cf0b43d149b81d82b5 | feat(anthropic): preserve portable reasoning | U020, F087, F090, F095, F098, U024, F103, F106, F107, F108, F109, F113 | F095, F098, F109, F113 | S4b, S4d; pending; S4b verified, see S4b validation; S4d verified, see S4d validation |
| 27150c03e269f5b71b947fd3528e441700d29a6c | feat(google): add Gemini hosted execution | F121, U028, F125, U030, F127, F128, F129 | F127, F128, F129 | S4c; pending; S4c verified, see S4c validation |
| 2575f1fafcb0acd3cb0a9271f6d7616cbfc72805 | feat(prompt): add caching and replay identity | F087, F092, F096, F098, F103, F112, F114, U038, F151, F153, F154, F167, F168, F180, F198, F200, F201, F210, F211 | F092, F096, F098, F112, F114, F167, F168, F180, F210, F211 | S2, S3, S4a, S4b, S4d; pending; S3 transport/retry and unchanged prerequisites verified, other named slices remain pending; see S3 validation; S4b verified, see S4b validation; S4d verified, see S4d validation |
| 727d293b5c8735c8623c71805483d1a0e1e173fb | fix(openai): correct code interpreter replay | F151, U041, F166, F171, F176 | F166, F171, F176 | S4a; pending |
| faf94802d957ab99388c76f78dafdbc067b99a2f | fix(tokeniser): support hosted execution content | F223, F224 | F224 | S5c; pending; S5c verified only F223, F224, see S5c validation |
| 1a2f16e7d52f846bbe3aec2846701b030a03663e | fix(events): format hosted execution content | F027, F029, F030 | F029, F030 | S5c; pending; S5c verified only F027, F029, F030, see S5c validation |
| 74f7b656e89c1507a6eb73b16bffb185b82c1979 | fix(telemetry): map hosted execution content | F031, F032 | F032 | S5c; pending; S5c verified only F031, F032, see S5c validation |
| 2ba1976f9454ac9291d58968a33cb897b638ca1f | fix(trace): format hosted execution content | F033, F035 | F035 | S5c; pending; S5c verified only F033, F035, see S5c validation |
| ca97c8a99370a617da24cc4061228e81c9d79b3c | feat(prompt): add model capability catalogue | F198, F204, F205, F219, F220, F222 | F219, F220, F222 | S2; pending |
| 9a118180cd514be277058221a01014d735da675b | feat(agents): add managed execution framework | F006, F007, F008, F014, F015, F016, F019, F020, F021, U017, U018, F183, F184, F185, F191, F198, F205, F206, F207, F208, F220, F221, U047 | F019, F020, F021, F191, F220, F221 | S1, S2, S5a; pending; S5a and R-runtime verified where assigned, see S5a and R-runtime validation |
| 0d87359d9c36f72cce3a4df588dc04d7180e3710 | feat(vertex): add Agent Engine execution | F183, F184, F185, F187, F188, F189, F191, F192, F205, F220 | F191, F192, F220 | S1, S2, S5a; pending; S5a and R-runtime verified where assigned, see S5a and R-runtime validation |
| 4de26452cd162a9b42ef62aa298819a589ccfe60 | feat(bedrock): add AgentCore code execution | F183, F184, F185, F191, F193, F194, F195, F205, F220 | F191, F195, F220 | S1, S2, S5a; pending; S5a and R-runtime verified where assigned, see S5a and R-runtime validation |
| d4ef32ef661c15a0a49189677bed7d25247e2e4f | feat(agents): integrate managed execution replay | F006, F009, F014, F016, F021, F022, F087, F093, F101, F103, F108, F109, U028, F128, F151, F166, F183, F185, F186, F188, F190, F191, F193, F198, F202, F203, F204, F205, F206, F207, F216, F217, F220, F221 | F021, F022, F093, F109, F128, F166, F190, F191, F216, F217, F220, F221 | S2, S4a, S4b, S4c, S4d, S5a; pending; S4b verified, see S4b validation; S4c verified, see S4c validation; S4d verified, see S4d validation; S5a and R-runtime verified where assigned, see S5a and R-runtime validation |
| a9b1ecdbfca6656b71ca54188fa29c9e27798326 | feat(prompt): harden caching and retries | F081, F082, F087, F092, F098, F101, F103, F108, F110, F112, F117, F151, F153, F154, F166, F167, F178, F179, F181, F182, F198, F200, F211 | F092, F098, F110, F112, F117, F166, F167, F181, F182, F211 | S1, S2, S3, S4a, S4b, S4d; pending; S3 transport/retry and unchanged prerequisites verified, other named slices remain pending; see S3 validation; S4b verified, see S4b validation; S4d verified, see S4d validation |
| d9a3074bdf459695d37823ea77ca597cf89ef4ba | docs: document jvm snapshot closure | F005 | Slice regressions; assertion review pending | S7; pending |
| f46bc813b8b7c8f2cb5886a448ec6f5feba5bfc8 | ci: add Maven Central release workflow | F001, F005, F040, F041, F042, F044, F046 | F046 | S1, S7; pending |
| 00ab8bd70ebb9a0a0666b9cf2fdbde70589b487c | fix: publish complete Kroog JVM catalogue | F001, F002, F005, F024, F025, F026, U012, F053 | F025, F026 | S5c, S7; pending; S5c verified only F024, F025, F026, see S5c validation |
| 70435302d4371aa4f5b2e7ff880191e42faca63c | fix: publish Spring AI BOMs in starter POMs | F005, U012, F073, F074, F075, F076, F077, F078, F079, F080 | Slice regressions; assertion review pending | S1, S7; pending |
| ba7899a0e37df7d6cb7c9cf8d4fe777cd58983ed | feat(openai): pass through chat template kwargs | F003, F143, U038, F151, F153, F157, F161, F164, F173 | F161, F164, F173 | S4a; pending |
| 68b632c3cdf6a1cc4bbff046b0dd23fdb1db84b8 | chore(release): prepare 1.0.0-kroog.5 | U012 | Slice regressions; assertion review pending | Provenance only; pending |
| 6843b0136f0a098c353163dcb8ae1364c53fa326 | chore(kroog): merge Koog 1.1.1 | F001, F002, F003, F004, U002, F005, U003, F006, F007, F008, F009, F014, F015, F016, F019, F020, F021, F022, F023, F024, F025, F026, F027, F028, F029, F030, F031, F032, F033, F034, F035, F038, F039, F040, F041, F042, F043, F044, F045, F046, U006, U007, F049, U009, U010, F050, U011, F051, U012, F053, F054, F055, F056, F057, F060, F061, F062, F063, U017, U018, F069, F070, F073, F074, F075, F076, F077, F078, F079, F080, F081, F082, F083, U019, U020, U021, F087, U022, F089, F090, F091, F092, F093, U023, F095, F096, F097, F098, U024, F100, F101, U025, F103, F104, F105, F106, F107, F108, F109, F110, F111, F112, F113, F114, F116, F117, F119, F120, F121, F122, U028, F125, U030, F127, F128, F129, U031, F133, F136, F137, F138, F140, F143, U037, U038, U039, F151, U040, F153, F154, F155, F156, F157, U041, F159, F160, F161, F162, F164, F165, F166, F167, F168, F169, F170, F171, F173, F174, F176, F177, F178, F179, F180, F181, F182, F183, F184, F185, F186, F187, F188, F189, F190, F191, F192, F193, F194, F195, F197, F198, F199, F200, F201, F202, F203, F204, F205, F206, F207, F208, F209, F210, F211, F212, F213, F216, F217, F219, F220, F221, F222, F223, F224, U047 | F019, F020, F021, F022, F025, F026, F028, F029, F030, F032, F034, F035, F045, F046, F057, F061, F062, F070, F091, F092, F093, U023, F095, F096, F097, F098, F109, F110, F111, F112, F113, F114, F116, F117, F119, F127, F128, F129, U031, F133, F159, F160, F161, F162, F164, F165, F166, F167, F168, F169, F170, F171, F173, F174, F176, F177, F180, F181, F182, F190, F191, F192, F195, F209, F210, F211, F212, F213, F216, F217, F219, F220, F221, F222, F224 | R-http-fixtures, R-openai-base, R-runtime, S1, S2, S3, S4a, S4b, S4c, S4d, S4e-deepseek, S5a, S5c, S7, S7-docs; pending; S3 transport/retry and unchanged prerequisites verified, other named slices remain pending; see S3 validation; S4b verified, see S4b validation; S4c verified, see S4c validation; S4d verified, see S4d validation; S4e-deepseek verified, see S4e-deepseek validation; S5a and R-runtime verified where assigned, see S5a and R-runtime validation; S5c verified only F024, F025, F026, F027, F028, F029, F030, F031, F032, F033, F034, F035, F223, F224, see S5c validation |
| db3a94f08879eb7c5bf77ef283ef9b95ace74707 | feat(skills): add validated skill sources | F054, U017, U018, U047, U049, F231, F233, F234, F236, F237, F241, F242, F243, F245 | F237, F243, F245 | S1, S6a; pending |
| f3c304b0076d70fa8726f2a3ac125f9976b0ea88 | feat(skills): add catalogue and load tool | U049, F230, F232, F235, F238, F239, F240 | F238, F239, F240 | S1, S6a, S6c; pending |
| ac2b5cfa17b749bdf135c5d9e2209e259197110d | test(skills): cover agent history carry-forward | U049, F244 | F244 | S1, S6c; pending |
| 1e822f6267f8e9735aaad9ac72db77cc8f7f1a77 | docs(skills): document beta module publication | F001, F002, F005, F053, F228 | Slice regressions; assertion review pending | S7, S7-docs; pending |
| 2605311ac14b669d694d791059526f750ab55dec | fix(skills): expose public API dependencies | U017, U018, U047, F226, F227, F228, U049, F234, F241, F243 | F243 | S1, S6a, S6c, S7-docs; pending |
| 1e1316b98f93429b19489a350273e4b91abce953 | fix(release): support verified browser tag dispatch | F001, F005 | Slice regressions; assertion review pending | S7; pending |
| d0af9baee80d5caf3f62f03079f79a2a7689a39e | fix(release): encode Central upload query directly | F001 | Slice regressions; assertion review pending | S7; pending |
| bab8fc8403b507ad6a84cdc146902d927f8203dd | feat(models): add Gemini and Claude model profiles | F048, F071, F072, U020, F087, U022, U023, U024, F101, U025, F108, F111, F117, F121, U028, U029, U031, F131, F204, F219, F222 | F072, U023, F111, F117, U031, F131, F219, F222 | R-ktor, S2, S4b, S4c, S4d, S7-docs; pending; S4b verified, see S4b validation; S4c verified, see S4c validation; S4d verified, see S4d validation; R-ktor verified, see S5b and R-ktor validation |
| 43f2ddd6cc72609ad1262377ac28e9bfdd61bfd3 | chore(release): prepare 1.1.1-kroog.2 | F005, U012, F228 | Slice regressions; assertion review pending | S7, S7-docs; pending |
| a9c11138657920b042a68e0b3f2276736ae14a02 | feat(bedrock): support xhigh reasoning effort | F005, U012, U024, F105, F113, F228 | F113 | S4d, S7, S7-docs; pending; S4d verified, see S4d validation |
| bbd81cf927b175dabd924db30fcdfcef4ae5eaf8 | feat: add gemini 3.7 flash | F048, F071, F072, F121, U029, U031, F131, F204, F219, F222 | F072, U031, F131, F219, F222 | R-ktor, S2, S4c, S7-docs; pending; S4c verified, see S4c validation; R-ktor verified, see S5b and R-ktor validation |
| 0569c91d7bd6d270ab553d264e7aeb89ce5ddc87 | fix(google): apply Gemini 3.7 request constraints | U028 | Slice regressions; assertion review pending | S4c; pending; S4c verified, see S4c validation |
| e454dabb356b2f22c44a8909b9c0fa30c60addea | chore(release): prepare 1.1.1-kroog.4 | U012 | Slice regressions; assertion review pending | Provenance only; pending |
| e8a4f0037e0fe1d57a1651c1c22fd4dd8f1aca6e | feat(models): add Claude Fable 5.1 | F005, F048, U012, F083, U020, U022, U023, U024, U025, F111, F117, F204, F219, F222, F228 | U023, F111, F117, F219, F222 | S2, S4b, S4d, S7, S7-docs; pending; S4b verified, see S4b validation; S4d verified, see S4d validation |
| 6bddf26334f0f918a91beab1544e363b324ab9ee | feat(agents): add portable tiered history compaction | F006, F012, F013, F018, F047 | F018 | S5a, S5b, S7-docs; pending; S5a and R-runtime verified where assigned, see S5a and R-runtime validation; S5b verified where assigned, see S5b and R-ktor validation |
| dd0363f08074cfbe2f23ceab9475d3f562bd6c71 | chore(release): prepare 1.1.1-kroog.6 | U012 | Slice regressions; assertion review pending | Provenance only; pending |
| 1f5e900e24af2384a87b6821a32e1a9502a3fe87 | chore(release): prepare 1.1.1-kroog.7 | U012 | Slice regressions; assertion review pending | Provenance only; pending |
| 21157b96cf3e9f4fb10e7d3606c25b467920d8b3 | fix(agents): retain exact recent turns in tiered compaction | F012, F013, F018, U012 | F018 | S5b verified; see S5b and R-ktor validation |
| a7bad9498655af865f6677a76b33b67e3f9a17ce | feat(openai): add GPT-6 Astra support | U038, F151, U040, F160, F164, F165, F171, F204, F219, F222 | F160, F164, F165, F171, F219, F222 | S2, S4a; pending |
| fb086e6da9fa9bed234589d67795693d594c51b5 | fix(agents): reset execution containers during tiered compaction | F012, F018, U012 | F018 | S5b verified; see S5b and R-ktor validation |
| 639a244c57ca563b9e4b5015680ff445b16c05d9 | feat(agents): improve tiered compaction handovers | F007, F012, F013, F017, F018, F047 | F018 | S1, S5b, S7-docs; pending; S5b verified where assigned, see S5b and R-ktor validation |
| f263bd05af057032c46afef6001597cd7ec2a619 | feat(agents): bound history compaction and tool-result requests | F006, F010, F011, F012, F013, F018, F047 | F018 | S5a, S5b, S7-docs; pending; S5a and R-runtime verified where assigned, see S5a and R-runtime validation; S5b verified where assigned, see S5b and R-ktor validation |
| 16861174d4371218d39f9fd8d14eb7d146e1ad4e | chore(release): prepare 1.1.1-kroog.10 | U012 | Slice regressions; assertion review pending | Provenance only; pending |
| 99abf884244cb3783fba54127997339128435812 | fix(prompt)!: normalise provider token usage (#3) | F030, F036, F037, F064, F065, F066, F087, F095, F103, F115, F118, F119, U028, U030, U031, F132, F134, F135, F139, F151, U041, F171, F172, U042, F196, F198, F203, F211, F214, F215, F218 | F030, F037, F064, F065, F066, F095, F115, F119, U031, F132, F135, F171, F172, U042, F211, F214, F218 | R-integration, R-openai-base, S2, S4a, S4b, S4c, S4d, S4e-deepseek, S4e-ollama, S5b, S5c; pending; S3 transport/retry and unchanged prerequisites verified, other named slices remain pending; see S3 validation; S4b verified, see S4b validation; S4c verified, see S4c validation; S4d verified, see S4d validation; S4e-deepseek verified, see S4e-deepseek validation; S4e-ollama verified, see S4e-ollama validation; S5b verified where assigned, see S5b and R-ktor validation; S5c verified only F030, see S5c validation |
| fc2a2f29c37296476dcef2866364374c7cec4a1f | fix(prompt): recover unavailable OpenAI containers with projected history (#4) | F141, U038, F148, F151, F153, F171 | F171 | S4a; pending |
| e5114814b4c5c98eaad47a1017b2cfef95acbc03 | feat(openai): add standalone image generation and editing (#5) | F058, F059, F142, U038, F147, F149, F150, F155, F156, F163, F169 | F059, F163, F169 | S1, S3, S4a; pending; S3 transport/retry and unchanged prerequisites verified, other named slices remain pending; see S3 validation |
| 324d5d8a0ac93df85cd796e2c19f666422293e74 | chore(release): prepare 1.1.1-kroog.11 (#6) | U012 | Slice regressions; assertion review pending | Provenance only; pending |

## Baseline-only ancestry effects

All 41 commits absent from U ancestry are listed. Counts cover touched paths whose final B blob equals U, and can overlap between commits. Final M-to-B tree evidence contains 453 unique paths, 419 unchanged at U and 34 overlapping the U ledger. The final tree takes precedence over ancestry. Entries without U overlap require no reversal; overlap is handled by the assigned row contracts.

| Baseline-only commit | Recorded change | Touched paths equal B at U | U ledger overlap |
| --- | --- | --- | --- |
| 050087d5a2accf1837fe276efabaa3ce7f20feb7 | build: 1.0.0 update (#2057) | 4 | U002, U003, U006, U009 |
| 0a705578353ab4860fd392f8ff7f8cc6190cbe2b | feat(agents): Introduce wrapper for cli agents (#1413) | 34 | U001, U013, U017, U018, U047 |
| c31b17290af3aef689d644235daa4ea184e3c937 | build: decrease Android minSdk from 35 to 23 (#2053) | 1 | none |
| 396f000f3160c819ab1a469a7001df155d6202e4 | refactor(agents)!: remove PromptAugmenter.SECTION_SEPARATOR (#2074) | 3 | none |
| 2689548f70beeee3266ac3f609f2bcf5fa0c9bc5 | test: remove Gemini 3 Pro preview and add new profiles (#2076) | 6 | U029, U031 |
| 54b8ecb1d78dafa0500480378b7aa504cbcf35a5 | fix(prompt): update OpenAI JSON schema generation for serialization of nullable collections (#1943) | 2 | none |
| 921f7c1ca8df6e5df4d5a9fb6330dff078b647d7 | fix(prompt): support multiple tool calls per response in LiteRTLLMClient (#2073) | 5 | none |
| 2ffea4110acbe5308009736baead09aeadc952a4 | feat(agents): add auto-discovery for Amazon Bedrock AgentCore Memory (#2003) | 13 | none |
| 75f1351fe59be13913e75efb8472d5a9f7be422b | test: add provider round-trip coverage for redesigned message parts (#2085) | 5 | none |
| 384473a69eae3d8058d07f091dce022fce6de51f | docs: add koog-agents-additions dependency to Readme and Quickstart (#2091) | 0 | U003, U009 |
| 7cee8b8d40af03cc32b0e888506519cdd855b3fd | ci: breaking changes check (#2082) | 122 | U019, U020, U021, U024, U033, U034, U035, U037, U038, U039, U043, U044, U045 |
| 4f3a14b455c3c91d0801fe3cdd6993c7d1d1274b | fix: #2089 added check guard for Bedrcok Nova empty system prompt array (#2090) | 2 | none |
| 0cea34fd96e8fa9317ec4b264e83662ac9f633b4 | fix(agents): AIAgentGraphContext.fork (#2083) | 2 | none |
| 39c84a2fac010db2299547e60018462f87e545f8 | feat(http-client): add Spring WebClient support (#2065) | 18 | U011, U017, U018, U047 |
| e4391218cff4fe113e9f6ef584e4b7e464a76ae4 | test: add new integration checks and fix failing ones (#2075) | 15 | none |
| 13cd127241c7c249aea8b12841f38ce22628aec8 | feat: #920 Added auto-discovery strategies test (#2092) | 1 | none |
| 581d6af6771ea04015392012e55b0cf9bdf73928 | fix(prompt): Don't start a new tool call on repeated tool call id (#2052) | 3 | none |
| c779c0483c18a5930871a6fa775fd12f6b7dddc1 | fix: drop .lowercase() call in AnthropicLLMClient enum serialization (#2099) | 1 | none |
| 6befd8bb157ef5bf91bd5e690911bebcbd343a27 | feat(prompt): explicit model resolution via DynamicPromptExecutor (#2081) | 20 | none |
| 43424c772d2fc71da57bf2187239b1c45ca47dd1 | docs: Add beta modules description, update after 1.0.0 (#2077) | 38 | U003, U007, U009, U010, U011 |
| 2270dfa00a23126c1be883f4e909253647dc6260 | docs: Fix model names in quickstart guide to make switcher work (#2101) | 1 | none |
| 482a434413531c7de63ec5c9e68979efaaecc0f8 | fix(agents): support sealed type outputs in subgraphWithTask (#2013) | 6 | none |
| 6ee3b63549be4b54da9d5ad818ae80cfd6c2de3c | fix(prompt): resolve @Contextual properties in GenericJsonSchemaGenerator (#1875) | 2 | none |
| 45e37303f37485896519bd064c1ba5a4bc94389a | example: unify DataStore settings implementation across platforms (#1608) | 17 | none |
| 485e294d2d71b99b11ec6e718e93f9cd2d0b8188 | fix(prompt): stop double-encoding tool-call arguments in OpenAI-compatible requests (#2096) | 3 | none |
| 6a35c2d84bac7ca1c0612b36ebd80867c1dfdc67 | feat(prompt): support non-text content in tool results (#1852) | 25 | U019, U020, U024, U028, U030, U041 |
| 7b604f997c619f0a6d363e96c3b12c1c9a5afefe | build: set version to 1.0 in gradle.properties (#2122) | 0 | U012 |
| 19d8d9790f6c08d1576e102ba13786e854a0760b | docs: update built-in tools docs to include dependency location (#2123) | 1 | none |
| 58a8168fac22234b95cfaaa1969df3ac99098707 | fix(serialization): use full JavaType in decodeFromJSONElement to preserve generics (#2116) | 2 | none |
| e4e9e4079fa69696c5662b5637467f253d1b52f2 | feat: Add support for Anthropic Claude Fable 5 model (#2130) | 5 | U019, U020, U021, U022, U023, U024, U025 |
| a720371d795b54fc064eef5a7addf2294a97432c | feat(agents): Carry strategy input on StrategyStartingContext (#2128) | 8 | none |
| d97a6e4dec1513e7d75bc19cfa2453199dcead2f | fix(agents): MessageTokenizer storage key conflict (#2143) (#2144) | 1 | none |
| a457663830fed30d914db74bfddb9eb7e6539d93 | feat(agents): Add freshHistory parameter to subgraph for optimization support (#1889) | 11 | none |
| dfa6efa24224c25687e795d9f9b683f9bd50ba80 | feat(spring-boot): Add integration with Spring Boot 2.0 (#2149) | 61 | U017, U018, U047 |
| 46bb6ef41e256e14f5038674e867598c4e21c6d0 | fix(agents): Add cache contrl support for all message parts (#2152) | 14 | none |
| 164f57a71ca63fafd1a7dedd2d579c80accf79ca | fix(spring-boot): use `chatModel.options.mutate()` in `SpringAiLLMClient` v2 to avoid `ClassCastException`. Add integration tests for `SpringAiLLMClient` V2 (#2155) | 5 | U013 |
| 90c2939bea6c084c82cd7b3af301fc889dae3189 | fix(agents): Release 1.1.1  updates (#2182) | 5 | U002, U003, U006, U007, U009, U010, U011, U012 |
| 89bb932ab0c1a1dd2d812e3cbb575748969bd436 | fix(agents): Update version (#2188) | 0 | U002, U003, U006, U007, U009, U010, U011 |
| 082c6783f56143eb63ff2f8b1eca2f865142141f | feat(koog-ktor): Add integration with Amazon Bedrock AgentCore Runtime (#2154) | 8 | U011, U017, U018, U047 |
| 08c20a4089d077293903257665270debe3ea0451 | fix(prompt): KG-866 Support reasoning_content in Delta for OpenAI (#2190) | 8 | none |
| 10bba89b67929bab3617047a6a7c8d8cf3ff9185 | docs: example illustrating Koog agent deployment to Bedrock AgentCore (#2189) | 13 | none |

The 34 unique final-tree overlap rows are: U001, U002, U003, U006, U007, U009, U010, U011, U012, U013, U017, U018, U019, U020, U021, U022, U023, U024, U025, U028, U029, U030, U031, U033, U034, U035, U037, U038, U039, U041, U043, U044, U045, U047.

Reproduce using git diff --name-only M B and compare each B and U blob from git ls-tree -r; absence is explicit. Both ledgers deliberately use incorporated B rather than M.

Raw full-index SHA-256 (UTF-8 without final newline): upstream cc9f41e99b9c25b2c9be0068e7d1908edacb5522198d135508bf50b0452f2ee6; fork fd552adabe318732f0c2e0becdd70bf8b10e61c6caa8ccb4ef2a8ba88b21d4ab.

## S1 build prerequisites

Pinned U changes only five build files. U013 is imported exactly. U047 already has skills inclusion in H; the fork module graph remains intact. U018 and F068 classify skills as beta. U017 and F067 include skills in the additions umbrella, with its API dependency on jvmMain because Kroog's skills server convention publishes JVM artefacts only. The stable umbrella has no skills dependency. U049 and F229 import upstream coroutine, serialisation-core, rag-base, logging and test dependencies while retaining public tools and JSON dependencies, SnakeYAML, existing tests and the JVM server convention. No module root, version, coordinate, signing or BOM change is required.

All other S1 paths retain H byte-for-byte. Source comparison against B and U preserves: managed execution and tokeniser API dependencies (F007); ACP JVM tests (F023); repository-scoped distribution (F038); convention test configuration, lazy signing, snapshot guard, fork POM metadata and Android namespace compatibility (F039 to F046); SnakeYAML catalogue entry (F054); AgentCore JUnit configuration (F069); all eight Spring AI BOM imports (F073 to F080); retry HTTP dependencies (F082); OpenAI HTTP test dependencies (F147); managed-execution module dependencies (F184); and the external skills consumer build (F226). Their source retention is established here; behavioural regressions remain assigned to their owning slices. Convention sources and tests are unchanged, so the conditional S1 convention-test rerun is unnecessary.

Validation uses installed JDK 21 through JAVA_HOME. The default JDK 27 failed before configuration with Gradle reporting `27`; JDK 21 successfully configures the scoped JVM graph. Command: `./gradlew :skills:jvmTest :skills:generatePomFileForJvmPublication :koog-agents-additions:generatePomFileForJvmPublication :koog-agents:generatePomFileForJvmPublication :skills-api-consumer-test:compileKotlin --console=plain`, preceded by the same command with `--dry-run`. Only JVM compilation, JVM publication-coordinate tasks and JVM tests enter this graph. Generated POM inspection confirms public coroutine, serialisation-core, rag-base, tools and JSON dependencies; runtime SnakeYAML and logging; and the additions dependency `com.kreoh.kroog:skills-jvm:1.1.1-beta-kroog.11-SNAPSHOT`. The stable umbrella has no skills dependency. Existing version transformation is unchanged.

The existing skills consumer compiles with these prerequisites. S6 must extend consumer source coverage to the upstream Skill, discovery and formatter API and rerun it after adaptation; that evidence remains pending. Integration-test dependency resolution and full umbrella artefact checks remain in R-integration and S7 respectively. No ABI dump changes are made by this dependency-only slice. Cached Kotlin 2.3.10 `abi-tools-api` exposes `AbiToolsV2.printJvmDump(Appendable, Iterable<File>, AbiFilters)` and the matching `abi-tools` implementation is present. Later source slices can use this JVM-only API against compiled classes and checked-in JVM dumps, with filters matched to the convention; it has not yet been exercised as a compatibility check.

S1 result: the inspected command passed in 1 minute 25 seconds (192 actionable tasks). Skills JVM tests: 73 passed, zero failures, errors or skipped tests across 7 suites. Existing consumer compilation and all three POM tasks passed. `git diff --check` passed. Compiler warnings came from unchanged prompt and agent sources; no warnings were suppressed. No S1 blocker remains.

## S2 validation

All 25 S2 ledger paths retain their H blob exactly, verified with `git hash-object` against the historical fork column. `git diff B U -- prompt/prompt-model` and `git diff H -- prompt/prompt-model` are empty. There are no upstream prompt-model APIs to import or delete. The catalogue and capability matrix retain their fork contracts; provider-specific model adoption remains in the coherent S4 slices. No production source, test, resource or ABI dump is edited in S2.

The module and its relevant build inputs remain identical to H: convention-plugin-ai, gradle, root build and settings, gradle.properties, prompt-model, prompt-llm, utils, agents-utils, http-client-core, test-utils and prompt-markdown. The S1 prerequisite edits affect other modules and umbrella dependencies. The verified pre-S2 checkpoint is `73476b99cf37bbe4a8a397e41bc3384aac6280a8`.

Command: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :prompt:prompt-model:jvmTest :prompt:prompt-model:jvmJar --console=plain`. Result: BUILD SUCCESSFUL in 44 seconds, 34 actionable tasks, with 9 executed and 25 up-to-date. The JVM XML reports contain 277 tests across 21 suites: 274 passed, zero failures or errors, and three existing skips in PromptBuilderTest for KG-504 markdown line-break handling. S2 introduces no ignores or suppressed warnings. Existing compiler warnings concern an always-false type check, an unnecessary safe call and an unnecessary cast in unchanged tests. Only JVM compilation and testing were run.

The four named regressions all ran without skips: HostedExecutionPromptTest (9), StreamFrameFlowBuilderTest (33), ManagedGeneratedFileFrameTest (11) and LegacyDefaultArgumentAbiTest (3). Assertion review confirms:

- HostedExecutionPromptTest preserves separate UI, call and provider identities through copy, serialisation and streaming; signed and opaque reasoning replay remains lossless; hosted execution and generated files retain order; legacy serialised fields remain optional.
- StreamFrameFlowBuilderTest covers interleaved parallel calls in first-seen order, repeated IDs, late identities, ambiguous anonymous calls, conflicting identity rejection before mutation, cross-context emission and encrypted reasoning before, after or without text. ProviderItemIdTest separately retains distinct provider items with equal call IDs.
- ManagedGeneratedFileFrameTest preserves full managed identity and Long.MAX_VALUE sequence values, byte-content equality and redacted rendering. It tests execution-scoped file identity, chunk and completion correlation, distinct repeated provider file IDs and safe local fallbacks.
- ManagedExecutionPresentationReplayTest (5) requires matching ordinary transcripts, rejects invalid origins, malformed order, cross-call pairing and mixed origins with precise reasons, and checks legacy JVM descriptors. StreamFrameExtTest (14) covers successful and failed code-execution round trips.
- TokenUsageMetadataTest (3) preserves inclusive totals without adding cache or reasoning subsets again, independent successive-request measurements, null versus reported zero, copies, serialisation and stream frames. TokenUsageBuilderTest (3) retains the previous Java factory signature and typed breakdowns. Module.md's inclusive usage contract is unchanged; provider normalisation remains owned by S4.
- PromptCachePolicyTest (12) covers non-mutating request views, provider-emittable breakpoints, capacity limits and TTL ordering, including nested, leading and trailing breakpoints. ModelCatalogueTest (12) checks the exact 38-entry golden, semantic identities, model capabilities and invalid entries. ProviderCapabilityMatrixTest (7) checks every provider API, inline and managed execution restrictions, compatibility-first rejection, transport selection and legacy copy semantics.

### JVM ABI outcome

The reliable JVM-only comparison ran and reported a mismatch against `prompt/prompt-model/api/jvm/prompt-model.api`. This is pre-existing dump drift, not an S2 API change: the complete module and relevant build inputs are H-identical. The comparison generated 3,704 lines against the checked-in 3,389 lines. Comparing class blocks independently of ordering found 308 identical blocks, 19 changed blocks, 17 added blocks and zero removed blocks. For example, compiled MessagePart.CodeExecution includes providerItemId in constructor and copy descriptors while the dump retains the older descriptors; compiled reasoning replay and generated-file citation classes are absent from the dump. Class ordering also differs. No checked-in dump was regenerated.

Reproduction uses cached Kotlin 2.3.10 `abi-tools`, `abi-tools-api`, `kotlin-stdlib` and `kotlin-metadata-jvm` JARs on a JDK 21 Java classpath. A temporary Java runner loads `AbiToolsFactory` with ServiceLoader, obtains `get().getV2()`, and calls `printJvmDump` with a StringBuilder and the recursive list of `.class` files from `prompt/prompt-model/build/classes/kotlin/jvm/main`. Pass individual class files: passing only the directory yields an empty dump. The AbiFilters constructor receives empty included classes, excluded classes and included annotations, then the seven excluded annotation names from `convention-plugin-ai/src/main/kotlin/ai.kotlin.multiplatform.gradle.kts`: InternalAgentsApi, InternalPromptAPI, InternalAgentToolsApi, InternalLLMClientApi, InternalStructuredOutputApi, InternalKoogSerializationApi and InternalA2AApi, each with its fully qualified repository name. Compare the generated text with the checked-in JVM dump. The initial attempt without kotlin-metadata-jvm failed with NoClassDefFoundError; adding its matching cached JAR produced the complete comparison above. Temporary output stayed outside the repository. No aggregate legacy ABI task or non-JVM target ran.

The ABI comparison does not pass and is not claimed as a binary compatibility guarantee. The narrower LegacyDefaultArgumentAbiTest passes exact constructor and copy/default descriptors for HostedExecutionConfiguration and GeneratedFileBytes, together with Kotlin constructor references and default-argument calls. The existing token-usage documentation already requires consumers to rebuild for its coordinated signature changes. Residual risk: the checked-in JVM dump is stale and does not certify the full retained API; this must remain visible in later reviews. No new S2 compatibility regression is introduced. Source identity, the passing descriptor regressions and the full prompt JVM suite support retaining the unchanged S2 implementation. `git diff --check` passes.

## S3 validation

S3, R-http-fixtures and R-openai-base retain all 18 ledger files exactly from H, checked with `git hash-object`. The historical baseline, upstream and starting-fork blob columns are unchanged. The complete HTTP tree, OpenAI-base module, client source and client JVM API tree have empty B-to-U and H-to-working-tree diffs. The convention plugin, Gradle configuration, root build and settings also remain H-identical. The pre-S3 checkpoint is `e810bf8463223f3d670d26c277717980426fee4a`. No production, test, fixture or ABI dump changes were required.

The initial dry-run selected only JVM tasks. Prerequisites ran first:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :http-client:http-client-test:compileKotlin :prompt:prompt-executor:prompt-executor-clients:prompt-executor-openai-client-base:jvmTest --console=plain
```

Result: BUILD SUCCESSFUL in 26 seconds, 49 actionable tasks, 16 executed and 33 up-to-date. OpenAI-base reports eight passing tests across three suites, with no failures, errors or skips. These tests exercise schema generation and tool descriptor conversion. Source inspection retains the base model's optional cache and reasoning counts, default empty streaming delta, inclusive token totals and unencoded tool argument replay. This run does not exercise provider cache keys or full replay requests; those remain S4 provider obligations. R-openai-base is accepted as an unchanged prerequisite, with those provider assertions still pending.

The HTTP and retry command was:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :http-client:http-client-core:jvmTest :http-client:http-client-java:test :http-client:http-client-ktor:jvmTest :prompt:prompt-executor:prompt-executor-clients:jvmTest --console=plain
```

Result: BUILD SUCCESSFUL in 39 seconds, 63 actionable tasks, 29 executed and 34 up-to-date. Core reports eight passing tests in three suites; Ktor reports 25 in two suites; clients report 64 in six suites. All have zero failures, errors and skips. The default Java `test` task reported zero tests, so its initial success provides no Java coverage. The existing pure-JVM convention registers `jvmTest` with JUnit Platform and the correct Java and Kotlin test class directories. Use `:http-client:http-client-java:jvmTest` for future checks.

Java verification used the following command while diagnosing the task distinction:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -I /tmp/kroog-s3-validation/java-tests.init.gradle :http-client:http-client-java:jvmTest :http-client:http-client-java:test --tests 'ai.koog.http.client.java.*' --console=plain
```

The temporary external init script uses `gradle.projectsEvaluated`, finds the Java project with `findProject`, and applies `useJUnitPlatform()` only to its `test` task. It adds a diagnostic `doFirst` to `jvmTest` which prints its class directories; it does not alter that task's engine, filters, sources or assertions. The first script attempt used `project` rather than `findProject` and failed while configuring the included convention build; guarding the absent project corrected the diagnostic script. The final command passed in 45 seconds, with 27 actionable tasks, five executed and 22 up-to-date. Both Java tasks independently report 44 tests across four suites, all passing without skips; count these once. The total is 149 distinct passing tests across 18 suites, including all ten JavaKoogHttpClientSseTest cases and all 36 RetryingLLMClientTest cases.

Assertion and implementation review confirms:

- Core binary tests preserve byte-content equality and multipart metadata, and require explicit UnsupportedOperationException defaults for legacy implementations. Java retains those default binary-operation limits; Ktor supplies the resource operations. No new Java binary support is claimed.
- The shared fixtures retain raw bytes including zeros, request and response headers, request IDs, multipart filenames and media types, deletion status and structured error bodies. Ktor exercises those assertions, raw request cancellation and serialised binary POST responses. The lines fixture checks three exact received lines, observes server-side closure and requires fewer than all configured lines to be written.
- Java SSE assertions cover complete event boundaries, whitespace and UTF-8, filtering, null processed chunks, rendezvous backpressure without dropped events, 429 diagnostics, decode failures, cancellation in each callback, cancellation while waiting for headers or an idle body, first-event termination and consumer exception type and message preservation. Body ownership remains in the retained atomic response reference, with closure in both the reader's finally block and awaitClose. Ktor retains scoped SSE collection and cancellation propagation.
- Retry assertions exercise wrapped 408, 409, 429 and 5xx statuses; non-retryable structured statuses override deceptive text and transport causes. Recognised transport causes retry, while malformed protocol and serialisation failures do not. Two-node and three-node cause cycles terminate, including a cycle containing a recognised transport failure. Wrapped cancellation returns the original cancellation exception without retry.
- Deterministic observers record bounded 100, 200 and 300 millisecond delays, exact attempt numbers and typed reasons while excluding the sentinel prompt and credential text. Observer failures cannot change the result. Jitter endpoints and overflow stay bounded; invalid jitter fails before a second call. Incomplete streams retry before a first frame and propagate after it, with exactly one delegate call after emitted output.
- StreamIdentityReplayTest passes all three assertions: UI, function-call and provider-item identities remain separate; internal indices never become provider identities; distinct provider items remain distinct despite equal function-call IDs. Retry source constructor references and legacy config copy usage remain exercised.

No JVM ABI dump comparison was rerun for these unchanged modules. Source and relevant build-input identity establish that S3 introduces no new API change; they do not certify the accuracy of existing dumps or resolve S2's recorded dump drift. No aggregate ABI or non-JVM task ran. All tests use local fixtures or mocks; live-provider behaviour remains untested here. Existing Gradle deprecation and missing SLF4J-provider notices are retained. The Java default-task discovery trap and pending S4 provider assertions remain explicit residual risks. `git diff --check` passes. No source or test edits, ignored tests or warning suppressions were introduced.

## S4a OpenAI adoption and preservation

The 38 S4a ledger rows cover 34 unique paths. Seven module paths changed; the other 27 still match their H blobs. Historical B, U and H columns remain unchanged. No new files, JVM dump edits or non-JVM work were required.

U041 is adopted as the exact pinned production delta: response `instructions` remains `List<Item>?`, with `ItemListOrSingleStringSerializer` accepting scalar strings or arrays and emitting arrays. Existing fork usage/replay additions remain intact. U042 scalar, string-array and item-array assertions are imported into the existing response test (new test names follow local conventions), with additional null, missing, empty-array and scalar-to-array output checks under both JSON configurations. U040's three GPT-5.6 entries already existed locally. Their identifiers, limits and capabilities now agree with U, including Document; shared list construction, MAX reasoning behaviour, GPT6Astra and local catalogue ordering remain. The Module table reflects Document support. U038's new GPT-5.6 public fields already exist locally; the internal serializer adds no public JVM signature.

Adding Document exposed a concrete fork interaction: `isGpt56()` previously matched capabilities and token limits structurally, which then matched GPT-5.5 and removed its configured temperature. The first full run failed the existing older-model assertion (expected 0.4, actual null). The bounded correction identifies canonical GPT-5.6 IDs directly and deployment copies by their retained shared capability-list reference. Existing `copy(id = ...)` tests and Module documentation define the supported alias contract; no reconstructed-alias contract was found. New assertions retain GPT-5.5 deployment temperature on both endpoints and recognise canonical GPT-5.6 IDs with rebuilt capability lists. Arbitrary aliases reconstructed from serialised metadata cannot identify their original family once metadata values coincide; Module documentation states this limit explicitly. No sampling assertion was weakened.

Retained assertions inspected: Responses parity checks provider item IDs and function call IDs, opaque encrypted reasoning, ordered stateless history, one replay item per code execution, streaming/non-streaming convergence, one recovery before the first provider frame, no recovery after a keepalive or output frame, no second recovery, callback failure/cancellation and stateful refusal. Replay identity checks reject missing reasoning/code provider IDs before transport. Usage checks preserve inclusive totals, optional/zero cache and reasoning details, and invalidate stale totals. Cache-key tests assert SHA-256 isolation, typed precedence, reserved-key rejection and no raw identifiers; chat kwargs tests assert preserved JSON. Azure assertions check deployment, dialect, path and API version. File/container tests check binary bytes, multipart upload, listing, retrieval, deletion and error/request IDs. Images assertions cover generation/editing wire shapes, cold streams, previews/completion, malformed/error responses, cancellation and early-close behaviour, actual Ktor/Java SSE and OkHttp suspended-consumer completion. The Java Images connection test observes completion without waiting for the idle server connection; S3 transport resource-lifetime evidence remains applicable.

Validation used Java 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`). Before production edits, `./gradlew :prompt:prompt-executor:prompt-executor-clients:prompt-executor-openai-client:jvmJar --offline --console=plain` passed. The focused `jvmTest --tests '*OpenAIResponsesAPIResponseTest' --offline --console=plain` initially could not resolve uncached okhttp-sse 5.3.2; repeating without `--offline` resolved it and ran 17 tests with exactly two expected scalar failures before the fix. After the detector correction, `./gradlew :prompt:prompt-executor:prompt-executor-clients:prompt-executor-openai-client:jvmTest :prompt:prompt-executor:prompt-executor-clients:prompt-executor-openai-client:jvmJar --offline --console=plain` passed: 319 tests across 26 XML suites, zero failures, errors or skips. This includes response instructions 17, models 6, LLM client 18, parity 22, Images 21, cache keys 10, replay identity 2, usage 2, Azure 2 and resource lifecycles 4. No new suppression or skipped test was introduced. No live-provider integration tests or non-JVM checks ran.

JVM ABI validation used the S2 cached Kotlin 2.3.10 AbiToolsV2 runner and annotation filters, against this module's JVM main class files. The unchanged pre-slice source generated 1,289 lines versus the checked-in 942 lines: existing dump drift, not introduced here. The post-slice generated dump equals that pre-slice dump byte-for-byte (113,778 characters), proving no public JVM ABI delta for this slice. The runner's success message refers to its supplied comparison file, which was the temporary pre-slice dump, not the repository dump. The repository dump remains stale; no passing checked-in ABI claim or regeneration is made. Remaining risks are that pre-existing dump drift and the explicitly documented reconstructed-alias limitation. `git diff --check` passes; JVM tasks compiled no non-JVM target.

## S4b validation

The pre-S4b checkpoint is `cdcc00d13827c19ba98f6fd01ea8201baf6a8334`. The complete pinned Anthropic B-to-U source delta consists of model profiles and their tests: Sonnet 5, Opus 4.8 and Opus 5. Sonnet 5 is adopted with the exact upstream ID, capabilities (including Temperature), 1,000,000-token context, 128,000-token output limit, public field, catalogue entry and default-version mapping. Its KDoc is adopted and the module table documents the new profile. Upstream schema and thinking assertions for all three models are present.

Opus 4.8 and Opus 5 were already implemented by `4a1c37b1914b1529be96b4752807ca14ef8f591e` and `bab8fc8403b507ad6a84cdc146902d927f8203dd`. Their IDs, token limits, catalogue entries, default versions and all non-temperature capabilities equal U. The latter commit explicitly removes Temperature from Opus 4.7, Opus 4.8 and Fable 5, adds Opus 5 without Temperature, and filters both typed and raw temperature request properties in AnthropicLLMClient. This production history and the current request filter justify retaining the named Kroog restriction instead of restoring U's Temperature on Opus 4.8 and Opus 5. Existing request regressions assert both input forms are omitted. Sonnet 5 retains its additive upstream capability; no Kroog restriction is inferred for that new model. Fable 5.1 retains its no-Temperature and no-ToolChoice profile from `e8a4f0037e0fe1d57a1651c1c22fd4dd8f1aca6e`, alongside the other later Fable profile.

All ten unedited S4b ledger entries match their historical H blobs (the source client, Vertex client, wire models and seven regression files). Every historical baseline, upstream and starting-fork column remains unchanged. The current implementation retains these contracts:

- `AnthropicModelsTest` has 16 passing tests. New `testSonnet5ExposesUpstreamProfileAndDefaultVersion` asserts the exact pinned profile, catalogue membership and version mapping. New `testSonnet5RequestUsesDefaultVersionAndExplicitAdaptiveThinking` asserts the outgoing model ID, maximum token limit and stream flag; ordinary requests retain typed temperature, while explicit adaptive requests omit it and place high effort in `output_config`. The existing tests retain Opus 4.8, Opus 5 and Fable 5.1 profiles and raw-temperature filtering.
- `AnthropicVertexLLMClientTest` has eight passing tests. It asserts rawPredict and streamRawPredict request routing, absence of client-added authentication headers, typed output-config precedence, literal SSE signatures, exact second-request thinking and tool order, signature-only thinking, cache breakpoints, and explicit rejection of unsafe paths, citations and malformed or unsupported stream content. The Vertex settings and transport remain H-identical.
- `AnthropicToolSerializationTest` has eight passing tests, including exact anyOf string-or-number and number-or-reference JSON schemas, tool-result error flags and image blocks. This retains `967531a8cf6ea97be8030c1cafea3182605e98d1` without replacing the schema path.
- `AnthropicReasoningReplayTest` has four passing direct tests and `VertexAnthropicReplayTest` has one. Signed and opaque redacted blocks survive response conversion and replay with provider ordering; malformed signatures fail explicitly. The usage matrix checks seven cases in streaming and non-streaming requests, including missing counts, explicit zero, cache reads, cache writes, both cache subsets, and cache values without a base input count. For base input 10, output 5, cache read 30 and cache write 20, totals remain 60 input and 65 overall, with cache subsets 30 and 20. A subsequent output-only delta retains those values.
- `AnthropicCodeExecutionReplayTest` has three passing tests. Client-managed presentation emits only the ordinary tool transcript without presentation output or sandbox identity. Malformed managed transcripts and unsupported provider-hosted code execution fail before transport. `AnthropicReplayIdentityTest` rejects missing function-call IDs before request construction.
- `AnthropicCacheControlTest` has 23 passing tests for provider-neutral metadata, root and block markers, TTL ordering and the four-breakpoint limit. `AnthropicSerializationTest` has ten passing tests, including adaptive display serialisation, transient signature deltas and retained wire models. The factory and both schema-generator tests also pass.

Validation command:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :prompt:prompt-executor:prompt-executor-clients:prompt-executor-anthropic-client:jvmTest :prompt:prompt-executor:prompt-executor-clients:prompt-executor-anthropic-client:jvmJar --console=plain
```

Result: BUILD SUCCESSFUL in 36 seconds, 52 actionable tasks, 17 executed and 35 up-to-date. The 12 XML suites report 77 tests, zero failures, errors or skips. No new suppressions or ignored tests were added. Validation uses local fixtures and does not require provider credentials.

JVM ABI evidence: build the unchanged baseline with the module `jvmJar` task (passed in 13 seconds), then use Kotlin 2.3.10 `AbiToolsV2.printJvmDump` with the repository's seven internal-API exclusions, matching abi-tools, abi-tools-api, kotlin-stdlib and kotlin-metadata-jvm JARs. The baseline text comparison initially differs solely because the checked-in RedactedThinking class blocks are out of generated order and there is a blank-line difference. Parsing every class header and its member multiset proves complete equivalence before editing. The compiled before-and-after dumps differ by exactly one line, `public static final field Sonnet_5 Lai/koog/prompt/llm/LLModel;`. The JVM dump is refreshed from this verified generated output for the intentional additive field, also normalising that existing block ordering. After normalising the generated trailing separator to one final newline, a final exact compiled-versus-checked-in comparison passes (84,863 characters). No existing signature changes or removals occur.

No Android, KLIB or other non-JVM dump was edited, and no non-JVM task ran. Live-provider behaviour is not exercised; the deterministic JVM request, stream and replay tests pass. No S4b blocker remains. Cross-provider model profiles and unrelated catalogue changes remain assigned to their own slices.

## S4c validation

The pre-slice checkpoint is `fbe99e6b518123742c6571f857dd66f12435b7df`. All four pinned Google B-to-U paths are reconciled. U028's additive `cachedContentTokenCount` metadata is adopted in the shared `GoogleUsageMetadata.toMetaInfo()` conversion used by ordinary and streaming responses. The same provider value populates structured `cacheReadTokensCount`; it is a subset of inclusive input and is never added again. U030's nullable wire field already exists locally, with KDoc explicitly describing this subset. Its local constructor order and hosted execution extensions remain unchanged. U031's positive cache assertion is covered by the stronger existing transport matrix, extended here to check the metadata entry alongside every structured count.

U029's Gemini 3.5 Flash-Lite, Gemini 3.6 Flash and Gemini 3.7 Flash already exist with the pinned identifiers, provider, 1,048,576-token contexts, 65,536-token output limits and catalogue entries. The complete source comparison identifies two intentional capability differences: local Document support is additive, while Temperature and MultipleChoices are omitted for these profiles. Commit `bab8fc8403b507ad6a84cdc146902d927f8203dd` introduced the first two profiles with explicit fixed-sampling KDoc and request filtering; `bbd81cf927b175dabd924db30fcdfcef4ae5eaf8` adds Gemini 3.7 using the same profile, and `0569c91d7bd6d270ab553d264e7aeb89ce5ddc87` adds its ID to the request constraint family. These production changes justify preserving the restrictions against upstream's general capability list. Current source strips typed and raw temperature, top-p, top-k and candidate-count properties, rejects final model content, permits tool results and rejects multiple-choice execution before transport. Existing tests assert each behaviour for all three profiles. The local pricing entries and more precise constraint descriptions remain; upstream's introductory Gemini 3.7 pricing qualification is adopted in British English. No upstream model field or catalogue entry is omitted.

The 16 S4c ledger rows cover 12 unique paths. Nine unedited paths still match their historical H blobs. All 299 historical ledger rows retain their original status, paths, B, U, H and slice columns. No new file, public signature, dump edit or warning suppression is introduced.

Assertions inspected and exercised:

- `GoogleTokenUsageTest` checks seven cases through ordinary HTTP and duplicate streaming snapshots: missing counts, ordinary totals, cache and reasoning subsets, explicit zeros, tool-use input, inconsistent provider totals and subsets without base counts. For input 100, candidates 10, thoughts 20 and cache 40, both transports yield input 100, output 30, total 130, cache 40 and reasoning 20, plus metadata cache 40. Missing cache remains absent and zero remains explicit. Partial snapshots retain input, cache and reasoning while cumulative candidate counts replace earlier values; a final chunk without usage retains them. New `testCumulativeCacheSnapshotsReplaceAndPreserveExplicitZero` checks replacement from 40 to 25 and from 40 to zero, duplicate snapshots and subsequent empty or absent usage, without changing inclusive total 110.
- `GoogleLLMClientTest` retains separate thought and tool signatures across chunks, exact parallel call identities and ordering, terminal usage, signed reasoning replay, configurable fallback signatures and grouped parallel tool results. The 24 tests also exercise the request constraints described above.
- Nine `GeminiCodeExecutionTest` cases preserve hosted lifecycle mapping, missing optional output, ordinary tools, mixed-tool selection, capability checks before transport and configuration copying. Four `GeminiReplayTest` cases preserve provider-ordered code and result signatures, legacy logs and managed-execution replay privacy; malformed managed transcripts fail before mapping. Three `GeminiStreamingTest` cases compare ordinary and streamed output with displaced code/results and arbitrary signature chunks, including shared generated execution identity.
- Six model tests retain exact profiles and Document support; six thinking tests retain level serialisation and rejection of mixed budget/level configuration. Six serialisation tests, the constructor and factory tests, and both schema-generator tests pass.

Validation used Java 21. The focused regression command was:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :prompt:prompt-executor:prompt-executor-clients:prompt-executor-google-client:jvmTest --tests '*GoogleTokenUsageTest' :prompt:prompt-executor:prompt-executor-clients:prompt-executor-google-client:jvmJar --no-parallel --no-daemon
```

Before the production fix, all three usage tests failed on missing cache metadata. The final command was:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :prompt:prompt-executor:prompt-executor-clients:prompt-executor-google-client:jvmTest :prompt:prompt-executor:prompt-executor-clients:prompt-executor-google-client:jvmJar --offline --console=plain --no-parallel --no-daemon
```

Result: BUILD SUCCESSFUL in 1 minute 5 seconds, 52 actionable tasks, 16 executed and 36 up-to-date. Twelve XML suites report 65 tests, zero failures, errors or skips. The module's current JVM JAR is `build/libs/prompt-executor-google-client-jvm-1.1.1-beta-kroog.11-SNAPSHOT.jar`; XML and HTML reports are under its `build/test-results/jvmTest` and `build/reports/tests/jvmTest` directories. Host temporary logs are `/tmp/kroog-s4c-red.log` and `/tmp/kroog-s4c-tests.log`.

JVM ABI validation reused `/tmp/kroog-s4b-CheckAbi.java` with `/tmp/kroog-s4a-abi-classpath`, Kotlin 2.3.10 AbiToolsV2 and the seven repository internal-API exclusions. After the red run compiled unchanged production sources, the runner generated `/tmp/kroog-s4c-before.api`; after the final build it generated `/tmp/kroog-s4c-after.api`. Both dumps are byte-identical (18,664 characters, 204 lines). The runner's passing comparison uses the temporary baseline file, not the repository dump. The repository dump already differs by constructor ordering and a listed `GoogleParams()` no-argument constructor absent from compiled baseline output. This pre-existing compatibility discrepancy remains explicit; no passing checked-in ABI claim is made and no dump is regenerated. The slice introduces no public JVM signature delta.

`git diff --check` passes. No live-provider, non-JVM or aggregate ABI task ran. Existing Gradle warnings remain. Residual risks are the pre-existing JVM dump discrepancy and untested live-provider behaviour; deterministic cache, signature, hosted execution and constraint checks pass. No new S4c blocker remains.

## S4d validation

Pinned B-to-U changes only BedrockModels.kt and its JVM dump. Adopted the exact upstream
Sonnet 5 and Nova 2 Lite model expressions and registry entries; existing Opus 4.8 and
Opus 5 expressions already match U. The remaining model-source difference from U is the
retained Fable 5.1 profile and registry entry, with concise existing Opus documentation.
Fable 5.1 provenance is e8a4f0037e0fe1d57a1651c1c22fd4dd8f1aca6e; its exact US and global
profiles remain covered by BedrockModelsTest. New APIs are documented in KDoc and Module.md.

BedrockModelsTest adds testClaudeSonnet5BedrockModelExposesExactEffectiveProfile and
testAmazonNova2LiteExposesExactEffectiveProfile. Both fail before adoption because their
registry entries are absent, then pass with exact capability, limit and inference-ID checks.
BedrockLLMClientTest adds testInjectedRuntimePropagatesConverseCancellation, checking regular
and streaming cancellation type and message with the existing injected runtime fixture.
Existing assertions cover injected runtime ownership and closure, captured cache payloads,
xhigh request documents, signed and redacted reasoning, cross-provider replay, malformed
replay rejection and normalised usage across transports. Their production implementations
and remaining S4d tests retain H byte-for-byte.

Java 21 validation: ./gradlew
:prompt:prompt-executor:prompt-executor-clients:prompt-executor-bedrock-client:jvmTest
:prompt:prompt-executor:prompt-executor-clients:prompt-executor-bedrock-client:jvmJar
passes 209 tests in 18 suites, with zero failures, errors or skips. A JVM-only Kotlin ABI
Tools 2.3.10 comparison of compiled classes before and after gives exactly two additive
BedrockModels getters: getAnthropicClaude5Sonnet and getAmazonNova2Lite. No signatures change
or disappear. The pre-existing checked-in dump differed only in ordering of RedactedThinking
classes; its line multiset matches the compiled baseline. The refreshed JVM dump matches
final generated output exactly. No non-JVM dumps or targets were touched. git diff --check
passes. All 299 ledger rows retain their historical columns. Live AWS access was not tested;
the pinned profiles and injected transport regressions define this slice's evidence.

## S4e-dashscope validation

U026 adopts all three model definitions and supported-model entries from pinned upstream
3acc88cf8ce70b87d8afbd3cf184844a50aa504e: QWEN3_5_PLUS, QWEN3_7_MAX and QWEN3_8_MAX.
B and H were identical (38854edcf3f211cc3a112a48539f1dc03ff5ea27). The sole difference
from upstream blob 71c99ebc13aa9cde5bc6734e7edb55533afb4a9e is replacing an em dash
with a comma in Qwen 3.8 KDoc to follow repository prose rules. No runtime source changes
or fork-only Dashscope ledger rows exist in this slice.

DashscopeModelsTest adds testNewQwenVersionsExposeExactProfiles, asserting exact IDs,
Alibaba provider identity, catalogue and reflected-field identity, million-token context,
output limits, and complete capability sets. Text-only 3.7, image-only 3.5 and image/video
3.8 capabilities are distinguished. The existing catalogue completeness test now uses
assertEquals instead of a JVM assertion. Existing parameter, serialisation, HTTP request,
reasoning/tool-call and token-usage assertions remain unchanged. Streaming coverage creates
a flow only; it does not establish end-to-end provider streaming.

Java 21 validation command:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :prompt:prompt-executor:prompt-executor-clients:prompt-executor-dashscope-client:jvmTest :prompt:prompt-executor:prompt-executor-clients:prompt-executor-dashscope-client:jvmJar --offline --console=plain --no-parallel --no-daemon
```

Pass: 30 tests in four XML suites, zero failures, errors or skips; BUILD SUCCESSFUL in
1 minute 4 seconds. The earlier test compilation caught nullable capabilities and numeric
assertion types; both were corrected before the passing run. Logs are
/tmp/kroog-s4e-dashscope-before.log, /tmp/kroog-s4e-dashscope-tests.log (initial compile
failure) and /tmp/kroog-s4e-dashscope-tests-final.log. XML and HTML reports reside in the
module's build/test-results/jvmTest and build/reports/tests/jvmTest directories.

JVM-only ABI proof uses Kotlin 2.3.10 AbiToolsV2 via /tmp/kroog-s4b-CheckAbi.java and
/tmp/kroog-s4a-abi-classpath with seven internal-API exclusions. The unchanged JVM JAR
baseline passed first. Generated before/after dumps are /tmp/kroog-s4e-dashscope-before.api
(13,249 characters, 125 lines) and /tmp/kroog-s4e-dashscope-after.api (13,454 characters,
128 lines). Removing exactly the three new public static model-field lines makes the
final dump byte-identical to the baseline. Final compiled output independently matches
/tmp/kroog-s4e-dashscope-verified.api. Initial runner comparisons deliberately report a
difference when generating against /dev/null and then the pre-change baseline; these
are generation steps, not claims of unchanged ABI. Dashscope sets isBeta=true and the
multiplatform convention disables ABI tasks for beta modules. It has no checked-in dump;
none was introduced. This evidence establishes relative JVM compatibility only.

All 299 ledger rows retain their historical columns. git diff --check passes. No aggregate
ABI task, non-JVM target or live-provider request ran. Residual risk is untested live-provider
behaviour; no slice blocker remains.

## S4e-deepseek validation

U027 adopts pinned upstream blob 97cfb48ac329f8fe00758bb4d7d56f8ecd9de209 exactly:
DeepSeekV4FlashVisionExp, its supported-model entry and upstream pricing documentation.
The prior model source matched B (08bad5f400598bc6a102061aa15f7313c82ab5bb).
The new testExperimentalVisionModelExposesExactProfile verifies the exact ID, provider,
catalogue and reflected-field identity, complete capabilities, million-token context and
384,000-token output limit. It distinguishes the vision model from text-only Flash.
The existing catalogue completeness test now uses assertEquals instead of a JVM assertion.

F118 and F119 remain byte-identical to the pre-slice tree. The existing
testNativeCacheUsagePreservesInclusiveCounts checks native cache values 3, 0 and absent,
inclusive input/output totals, reasoning as a subset and absent cache writes.
testStreamingReasoningIsEmittedAndReplayedToProvider collects the stream and checks cache
reads, computed total usage, reasoning deltas/completion and replay in the provider request.
Existing tool-call reasoning and response-usage tests also pass. The serializer remains
installed for both ordinary and streamed responses. This slice makes no transport change.

Java 21 validation command:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :prompt:prompt-executor:prompt-executor-clients:prompt-executor-deepseek-client:jvmTest :prompt:prompt-executor:prompt-executor-clients:prompt-executor-deepseek-client:jvmJar --offline --console=plain --no-parallel --no-daemon
```

Pass: 27 tests across four XML suites, zero failures, errors or skips; BUILD SUCCESSFUL
in 54 seconds. The baseline JVM JAR also passed before edits. Logs are
/tmp/kroog-s4e-deepseek-before.log and /tmp/kroog-s4e-deepseek-tests.log. Module reports
are under build/test-results/jvmTest and build/reports/tests/jvmTest.

Relative JVM ABI validation uses Kotlin 2.3.10 AbiToolsV2 through
/tmp/kroog-s4b-CheckAbi.java and /tmp/kroog-s4a-abi-classpath with the seven repository
internal-API exclusions. Temporary baseline /tmp/kroog-s4e-deepseek-before.api contains
12,570 characters and 119 lines. Final /tmp/kroog-s4e-deepseek-after.api contains 12,651
characters and 120 lines. Removing exactly the new public static DeepSeekV4FlashVisionExp
field makes the final dump byte-identical to the baseline. An independent runner comparison
against /tmp/kroog-s4e-deepseek-expected.api passes and writes
/tmp/kroog-s4e-deepseek-verified.api. Initial generation comparisons against /dev/null
and the pre-change dump intentionally report differences. The passing comparison uses a
temporary expected dump, despite the runner's generic checked-in-dump success message.
DeepSeek sets isBeta=true; the multiplatform convention disables beta ABI tasks. There is
no checked-in DeepSeek dump and none was introduced. This proves relative JVM compatibility.

All 299 ledger rows retain their historical columns. No non-JVM task, aggregate ABI task,
publication or live-provider request ran. Residual risk is untested live-provider behaviour;
no slice blocker remains. git diff --check passes.

## S4e-mistralai validation

U032 adopts the complete pinned upstream model and catalogue delta from blob
0815e9d5c00813912b359244be76b87f787f5eb8. The pre-slice source matched baseline blob
acf41e597b858f743cfb337df85bf4d40b743d54. Sole upstream adaptations are KDoc British
English spelling and punctuation. Ministral3_3B, Ministral3_8B and Ministral3_14B join
the catalogue with their pinned latest aliases, six capabilities and 128,000-token
contexts. MistralLarge21 retains its public field name, latest alias and capabilities,
while its context grows to 256,000 tokens. All remaining model profiles are unchanged.

MistralAIModelsTest now uses unconditional assertions and testXxx names. Its four tests
verify every provider, all twelve catalogue entries and reflected-field identities,
unique IDs, exact new aliases and complete capability lists, context lengths and
unspecified output limits. The existing Large field regression verifies its alias,
capabilities and expanded context. No transport source changed.

Java 21 validation command:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :prompt:prompt-executor:prompt-executor-clients:prompt-executor-mistralai-client:jvmTest :prompt:prompt-executor:prompt-executor-clients:prompt-executor-mistralai-client:jvmJar --offline --console=plain --no-parallel --no-daemon
```

Pass: 21 tests across four XML suites, zero failures, errors or skips; BUILD SUCCESSFUL
in 1 minute 14 seconds. The pre-change JVM JAR passed in 45 seconds. Logs are
/tmp/kroog-s4e-mistralai-before.log and /tmp/kroog-s4e-mistralai-tests.log.
Module reports are under build/test-results/jvmTest and build/reports/tests/jvmTest.

Relative JVM ABI validation uses Kotlin 2.3.10 AbiToolsV2 through
/tmp/kroog-s4b-CheckAbi.java and /tmp/kroog-s4a-abi-classpath with the seven repository
internal-API exclusions. Baseline /tmp/kroog-s4e-mistralai-before.api contains 16,305
characters and 170 lines. Final /tmp/kroog-s4e-mistralai-after.api contains 16,516
characters and 173 lines. Removing exactly the three Ministral3 public static fields
makes the final dump byte-identical to the baseline. The independent runner comparison
against /tmp/kroog-s4e-mistralai-expected.api passes and writes
/tmp/kroog-s4e-mistralai-verified.api. Initial generation comparisons against /dev/null
and the pre-change dump intentionally report differences. The passing comparison uses
a temporary expected dump, despite the runner's generic checked-in-dump success message.
The module sets isBeta=true; the convention disables beta ABI tasks. Its existing
absence of checked-in API dumps is preserved. No existing JVM signature changed.

All 299 ledger rows retain their historical columns. git diff --check passes.
No non-JVM task, aggregate ABI task, publication or live-provider request ran.
Residual risk is untested live-provider behaviour; no slice blocker remains.

## S4e-ollama validation

U036 adopts the complete pinned upstream source byte-identically: QWEN_3_6_27B and
QWEN_3_8_27B, their KDoc and both catalogue entries. B and starting H were identical.
The existing JVM OllamaModelsTest now checks both identifiers, provider, 256,000-token
context, exact capabilities and unique catalogue registration. Qwen 3.8 has image
support; Qwen 3.6 does not. The stale OpenAI test names were corrected and catalogue
cardinality now uses an always-active matcher.

F134 and F135 remain byte-identical to H. OllamaClientTest verifies input and output
counts and totals for (5,3), (0,0), (5,null), (null,3) and (null,null). Totals remain
null unless both counts are present. Existing Ollama streaming emits no usage metadata;
this slice makes no streaming-usage claim or change. Shared cumulative snapshot and
omitted-field semantics retain the S2 evidence. Other retained module tests cover
thinking, tools, embeddings, content types, schemas and context windows.

Java 21 validation commands:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :prompt:prompt-executor:prompt-executor-clients:prompt-executor-ollama-client:jvmJar --no-parallel --no-daemon
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :prompt:prompt-executor:prompt-executor-clients:prompt-executor-ollama-client:jvmTest :prompt:prompt-executor:prompt-executor-clients:prompt-executor-ollama-client:jvmJar --offline --console=plain --no-parallel --no-daemon
```

Both pass: baseline JAR in 57 seconds; final tests and JAR in 1 minute 20 seconds.
The final XML reports contain 48 tests in ten suites, zero failures, errors or skips.
OllamaModelsTest and unchanged OllamaClientTest each pass three tests.

U034 was updated from compiled JVM output using Kotlin 2.3.10 AbiToolsV2 through
/tmp/kroog-s4b-CheckAbi.java and /tmp/kroog-s4a-abi-classpath, with the seven repository
internal-API exclusions. Generated ABI grows from 15,883 characters and 192 lines to
16,021 characters and 194 lines. Removing the two new public static fields yields a
byte-identical baseline. No existing signature changes or removals occur. The old
checked-in dump differs from the baseline only by one terminal blank line; the new
dump matches pinned upstream after terminal whitespace normalisation and matches
compiled output exactly. The runner's final comparison against the updated checked-in
dump passes. Initial generation comparisons intentionally report the old terminal
newline difference and then the two new fields.

Host artefacts use /tmp/kroog-s4e-ollama- with suffixes before.log, tests.log,
abi-before.log, abi-after.log, before.api, after.api and verified.api. JVM test reports
remain under the module's build/test-results/jvmTest and build/reports/tests/jvmTest.
All 299 ledger rows retain their historical columns; git diff --check passes.
No non-JVM or aggregate ABI task, live-provider request or publication ran.
Residual risk: live Ollama behaviour is untested. No slice blocker remains.

## S4e-openrouter validation

U046 adopts all eight pinned upstream profiles and catalogue entries: Claude4_7Opus,
Claude4_8Opus, Claude5Opus, Claude5Sonnet, GPT5_6Sol, GPT5_6Terra, GPT5_6Luna and
Gemini3ProPreview. Baseline and starting H model source were identical. The final
source differs from pinned upstream only by three KDoc em-dash-to-comma substitutions
for the repository language rules. Existing profiles, embedding definitions, identifiers,
custom-model registration and client implementation remain unchanged.

The existing JVM OpenRouterModelsTest now verifies the exact ordered catalogue of
46 supported models, distinct identifiers and reflected object identities. Its new
profile regression checks all eight exact identifiers, OpenRouter provider, complete
capability lists, context limits and output limits, with unique catalogue lookup.
Claude profiles have JSON Basic and Standard capabilities; the new GPT and Gemini
profiles have Standard. The previous provider regression remains active under a
conventional test function name. No version or alias override is introduced.

Java 21 validation commands:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :prompt:prompt-executor:prompt-executor-clients:prompt-executor-openrouter-client:jvmJar --offline --console=plain --no-parallel --no-daemon
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :prompt:prompt-executor:prompt-executor-clients:prompt-executor-openrouter-client:jvmTest :prompt:prompt-executor:prompt-executor-clients:prompt-executor-openrouter-client:jvmJar --offline --console=plain --no-parallel --no-daemon
```

Both pass: baseline JAR in 38 seconds; final suite and JAR in 54 seconds. The XML
reports contain 50 tests in six suites with zero failures, errors or skips, including
three model regressions. Existing embedding, serialisation, parameter-validation and
reasoning-message preservation tests pass unchanged.

U044 was generated from compiled JVM classes using Kotlin 2.3.10 AbiToolsV2 through
/tmp/kroog-s4b-CheckAbi.java and /tmp/kroog-s4a-abi-classpath, retaining the seven
repository internal-API exclusions. Generated ABI grows from 30,538 characters and
347 lines to 31,091 characters and 355 lines. Removing exactly the eight new public
static model fields produces a byte-identical baseline. No previous signature changes
or removals occur. The old checked-in dump differs from compiled baseline only by a
terminal blank line. The new dump matches pinned upstream after terminal whitespace
normalisation and compiled output exactly; the final runner comparison passes.
Initial generation comparisons report the expected old newline and new-field differences.

Host artefacts use /tmp/kroog-s4e-openrouter- with suffixes before.log, tests.log,
abi-before.log, abi-after.log, before.api, after.api and verified.api. JVM test reports
remain under the module's build/test-results/jvmTest and build/reports/tests/jvmTest.
All 299 ledger rows retain their historical columns; git diff --check passes.
No non-JVM target, aggregate ABI task, live-provider call or publication ran.
Residual risk: live OpenRouter behaviour is untested. No slice blocker remains.

## S5a and R-runtime validation

This preservation slice changes only this audit. All 22 S5a paths and R-runtime F070
match their recorded H blobs byte-for-byte. Their B and U blobs were checked directly,
including explicit absence, and have no upstream delta. The managed-execution module,
AgentCore runtime and core and agents-test source trees also retain H exactly. The
validation input was checkpoint 77263bec31c561660d691f078e431860733a359b with no product
changes. Existing S1 build prerequisites remain in place.

Inspected assertions establish the following retained contracts:

- VertexAgentEngineManagedExecutionServiceTest (27 tests) checks exact v1 request paths,
  authentication and payloads, bounded polling, language validation, ordered output,
  file identity and deduplication, cancellation, owned release and borrowed preservation.
  Typed failures and cause chains redact provider bodies and resource identifiers.
- BedrockAgentCoreManagedExecutionServiceTest (38 tests) checks SDK start, invoke and stop
  shapes, token identity, streamed files and backpressure, malformed terminal rejection,
  cancellation and release ownership. Its reflection regression checks legacy constructor
  and copy descriptors. ManagedExecutionTest (9) checks serialisation, redaction and flow
  semantics; ManagedExecutionPresentationTest (3) checks complete borrowable session
  restoration and independent Long sequence and presentation indices.
- ManagedExecutionToolTest (18) checks one final ordinary result, ordered observations,
  protocol rejection, cancellation and coexistence with ordinary tools.
  ServiceBackedManagedExecutionToolTest (8) checks exactly one acquisition under concurrent
  collection, ownership, cancellation cleanup, persisted-session reuse and route validation.
- ManagedExecutionPipelinePrivacyTest (9) checks contextual callbacks and logs on success,
  validation, execution, provider, protocol and encoding failure. It actively attempts
  mutation of detached prompt collections, tools, configuration, state, storage and pipeline;
  live state remains intact and callback services cannot execute prompts or tools.
  Ordinary tools retain their contextual callbacks and diagnostics. JvmLogCapture remains
  the correlation-aware fixture for these assertions.
- AgentCoreRuntimeTest (30) checks request headers and bodies, typed defaults, content
  negotiation, exact SSE chunks, binary responses, health and task tracking, rate limits,
  payload rejection before handler execution, timeouts and structured errors.

Replay evidence in this run includes complete presentation/session round trips and reuse
of the persisted session on the next execution. Provider wire replay and malformed
managed-transcript rejection retain the earlier S4b, S4c and S4d evidence; those provider
suites were not rerun in this slice. No new regression or product edit was necessary.

Java 21 task discovery used the following command with --dry-run first; its graph contains
only JVM compilation and tests. The final command was:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :prompt:prompt-executor:prompt-executor-managed-execution:jvmTest :koog-bedrock-agentcore-runtime:test :agents:agents-core:jvmTest :agents:agents-test:jvmTest --console=plain --no-parallel --no-daemon
```

BUILD SUCCESSFUL in 1 minute 51 seconds, 130 actionable tasks, 53 executed and 77
up-to-date. Fresh XML reports from 28 September 2026, 16:56 to 16:57 UTC, contain:

| Module | Suites | Tests | Passed | Skipped | Failures and errors |
| --- | ---: | ---: | ---: | ---: | ---: |
| managed-execution | 4 | 77 | 77 | 0 | 0 |
| AgentCore runtime | 1 | 30 | 30 | 0 | 0 |
| agents-core | 65 | 488 | 465 | 23 | 0 |
| agents-test | 6 | 29 | 28 | 1 | 0 |

The 24 skips are existing disabled core pipeline, debugger, session, parallel-node and
remote-server tests plus one agents-test token-count case. Every S5a test ran. The full
core suite also ran TieredHistoryCompressionStrategyTest (38), and agents-test ran
TokenUsagePreservationTest (1), for potential S5b reuse if their inputs remain unchanged;
S5b assertion review and acceptance remain separate.

The first offline attempt stopped before tests at runtime compilation because the Ktor
3.3.3 rate-limit JAR was uncached. The final run fetched it from the configured repositories
and completed. Host logs are /tmp/kroog-s5a-graph.log, /tmp/kroog-s5a-tests.log and
/tmp/kroog-s5a-tests-online.log. Fresh XML and HTML reports remain in each module's
build/test-results and build/reports/tests directories.

No source, dependency input, public API or JVM dump changed in this slice, so it introduces
no source API delta. No compiled-versus-checked-in ABI comparison ran; the legacy descriptor
regression is narrower evidence and does not establish whole-module binary compatibility.
Existing warnings remain. No non-JVM target, aggregate ABI task, publication or live-provider
request ran. Residual limits are the existing skipped tests, untested live services and
unverified whole-module ABI dumps. All 299 ledger rows retain their historical columns;
git diff --check passes. No S5a or R-runtime blocker remains.

## S5b and R-ktor validation

This preservation slice changes only this audit. S5b F010 to F013, F017, F018, F036
and F037 and R-ktor F071 and F072 match their recorded H blobs byte-for-byte.
Direct Git object checks confirm B=U for all ten paths, including explicit absence.
There is no upstream import or source API delta. The input checkpoint is
1ce34406d355bcc503b026288a5e9c7b35ce757c; only this audit changed since the S5a
validation input 77263bec31c561660d691f078e431860733a359b. The working tree was clean
before this audit edit, so tracked source and dependency inputs for the reused run
remain identical.

Inspected source and actual TieredHistoryCompressionStrategyTest assertions establish:

- Exact recent user-led turns: the eight-turn regression compares the complete retained
  tail, excludes it from the summary request and retains paired call/result identifiers.
  Repeated compression folds the prior handover into one replacement and preserves memory.
- Portable projection: provider fixtures produce equal history shapes. Both tiers and the
  generated summary remove reasoning replay, hosted progress, response IDs and raw responses.
  Saved OpenAI containers are cleared in the summary request and final prompt; execution
  settings and input file IDs survive. Disabled execution and Google parameters stay intact.
- Attributed handovers: summaries remain assistant history, with source and authority
  framing; reconstructed handovers occur once. Budgeted fragments preserve user and
  assistant attribution, tool name and call ID, and failed or succeeded result status,
  while reconstructing the full source text.
- Bounded summaries: all captured requests stay within the 3,000-token test budget,
  including huge tool results and summary-only retention. Oversized fixed system content
  fails before any provider call. Summary settings replace answer settings temporarily;
  failure, cancellation, empty summaries and metric failures restore the original prompt.
- Callback ordering: testStreamingPreparationSeesToolResultsAndCanStopTheRequest asserts
  the complete tool result is present when preparation runs, exactly one callback occurs,
  the successful request sees the callback's changed prompt, and callback failure sends
  no streaming request. AIAgentNodes appends results, invokes beforeRequest, then streams.

TokenUsagePreservationTest checks separate ordinary and streaming requests, inclusive
input counts for both and the streaming total count, and unchanged cache-read, cache-write and reasoning
subsets for null, zero and positive values, plus model identity. MockPromptExecutor copies
only estimated totals, retaining the breakdown metadata.

The following evidence is explicitly reused from S5a, not rerun in S5b: its Java 21
agents-core and agents-test jvmTest execution recorded above, with XML timestamps
28 September 2026 at 16:57:30 UTC and 16:57:06 UTC for the two named suites.
TieredHistoryCompressionStrategyTest passed all 38 tests; TokenUsagePreservationTest
passed its one test, with no skips or failures in either. The containing reports have
65 core suites, 465 passed and 23 existing skips, and six agents-test suites, 28 passed
and one existing skip. The successful S5a log and unchanged tracked inputs were checked.
No new regression or product edit was necessary.

R-ktor's explicit maps and ModelIdentifierParsingTest compare resolved models with named
catalogue constants and provider identities, including the retained Gemini 3.7 Flash,
Claude Fable 5 and Opus 5 entries. Invalid identifiers return null. This is deterministic
catalogue resolution evidence and makes no claim about live provider support.

Java 21 task discovery used the following command with --dry-run first. Its graph
contained JVM compilation and tests only. The focused execution was:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :koog-ktor:jvmTest --tests ai.koog.ktor.ModelIdentifierParsingTest --console=plain --no-parallel --no-daemon
```

BUILD SUCCESSFUL in 49 seconds, 179 actionable tasks, 94 executed and 85 up-to-date.
The fresh XML report at 17:05:53 UTC on 28 September 2026 has one suite and 13 tests,
all passed, with zero skips, failures or errors. Logs are /tmp/kroog-s5b-graph.log and
/tmp/kroog-s5b-tests.log; XML and HTML remain under koog-ktor/build. This focused run
compiled its JVM prerequisites, including skills, but does not establish S6 acceptance.

All 299 ledger rows retain their historical columns. git diff --check passes. No source,
test, API dump, build input or dependency changed; no new tests were added. Compiled JVM
ABI comparison was not run for this audit-only slice. No non-JVM compilation, aggregate
ABI task, publication or live-provider request ran. Residual limits are the reused suites'
24 existing skips, untested live services, unverified whole-module ABI dumps and the
focused scope of the Ktor execution. No S5b or R-ktor blocker remains.

## S5c validation

U004 is byte-identical to pinned U: Langfuse completion conversion ignores reasoning
parts whose content list is empty and retains finish_reason for populated reasoning.
U005 includes the exact upstream signature-only reasoning regression, plus
`testPopulatedReasoningRetainsContentFinishReasonAndHiddenString`. The additional test
checks mixed empty and populated reasoning, ordered content across parts, HiddenString,
assistant role and stop, length and absent finish reasons. Before the production fix,
the six-test Langfuse suite had four passes and two expected regression failures.
After adoption, all six passed. Existing plain-text and tool-call completion checks remain.

All 14 assigned fork rows (F024 to F035, F223 and F224) have B=U and remain byte-identical
to their recorded H blobs. Inspected assertions and fresh JVM execution establish:

- ACP: generated-file identifiers and metadata, code and ordered outputs, hosted
  request, progress, output, result and error content, tool-call IDs and statuses,
  and non-zero exit-code failure with execution-ID fallback.
- Event handling and tracing: hosted lifecycle and variant order, empty optional fields,
  and exclusion of the supplied sensitive bodies, paths and identifiers. Existing code
  execution tests check retained fields and output order. EventHandlerTest also preserves
  its message and streaming event expectations, including the token-usage fields.
- OpenTelemetry: ordered hosted metadata, omission of empty optionals and sensitive
  bodies and identifiers, and HiddenString masking by default. Its verbose-mode test
  still excludes the supplied hosted code and error body. GenAIAttributesTest passed
  all 39 tests, including the code execution assertions.
- Tokeniser: visible hosted text and generated-file names and media types retain part
  order; empty optional text and opaque identifiers are omitted. PromptTokenizerTest
  passed all five tests, including code execution and cache checks.

Java 21 task discovery used the command below with `--dry-run` first. Both the inspected
graph and execution were JVM-only. No earlier test result was reused.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :agents:agents-features:agents-features-opentelemetry:jvmTest :agents:agents-features:agents-features-acp:jvmTest :agents:agents-features:agents-features-event-handler:jvmTest :agents:agents-features:agents-features-trace:jvmTest :prompt:prompt-tokenizer:jvmTest --console=plain --no-parallel --no-daemon
```

BUILD SUCCESSFUL in 1 minute 38 seconds; 111 actionable tasks, 45 executed and 66 up-to-date.
Fresh XML reports on 28 September 2026 recorded these results:

| Module | Suites | Passed | Existing skips | Failures or errors |
| --- | ---: | ---: | ---: | ---: |
| ACP | 2 | 4 | 0 | 0 |
| Event handler | 5 | 23 | 1 | 0 |
| OpenTelemetry | 30 | 196 | 19 | 0 |
| Tracing | 6 | 25 | 4 | 0 |
| Prompt tokeniser | 2 | 9 | 0 | 0 |

The focused pre-fix invocation was the OpenTelemetry jvmTest task above with
`--tests ai.koog.agents.features.opentelemetry.integration.langfuse.LangfuseSpanAdapterTest`.
Its two failures demonstrated the lost text and unwanted leading newline respectively;
the passing final run also verifies the newly propagated finish reason.

Only a private method body in an internal adapter changed in production. Public
signatures, build inputs and ABI dumps are unchanged. A compiled ABI comparison was
not run; source inspection establishes no public signature change for this slice.
All 299 ledger rows retain their historical columns. No new suppression or disabled
test was introduced. `git diff --check` passed. Remaining limits are 24 existing skips
(including 18 environment-gated cloud trace tests), untested live services and unverified
whole-module ABI dumps. No non-JVM task, aggregate ABI check or publication ran.
No S5c blocker remains.
