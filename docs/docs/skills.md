# Skills usage

Kroog adopts Koog 1.3.0's skill model, discovery and prompt APIs, with strict JVM filesystem boundaries and legacy loading compatibility. Skills are reusable capability bundles described by `SKILL.md` files. The module contains no bundled skills and does not execute scripts automatically.

Add the standalone beta JVM module explicitly:

```kotlin
dependencies {
    implementation("com.kreoh.kroog:skills-jvm:1.3.0-beta-kroog.1")
}
```

This is the current source-line coordinate, not an announcement of publication. Snapshot builds use `1.3.0-beta-kroog.1-SNAPSHOT`. The module is excluded from the stable `koog-agents` umbrella and included in the JVM publication of `koog-agents-additions`. Follow the repository's `PUBLISHING.md` for JVM POM-based resolution and snapshot repositories.

## Discover skills and compose a prompt

Usage has three steps: discover descriptors from configured roots, generate a catalogue, and place it in the agent's system prompt. Provide separately authorised tools for reading instructions or executing scripts when the workflow needs them.

```kotlin
import ai.koog.rag.base.files.JVMFileSystemProvider
import ai.koog.skills.discovery.discoverSkills
import ai.koog.skills.prompt.SkillsPromptFormat
import ai.koog.skills.prompt.generateSkillsPrompt
import java.nio.file.Path

suspend fun skillsSystemPrompt(): String {
    val root = Path.of("configured-skills").toAbsolutePath().toString()
    val skills = discoverSkills(JVMFileSystemProvider.ReadOnly, listOf(root))
    val catalogue = generateSkillsPrompt(
        skills,
        SkillsPromptFormat.XML,
        includeLocation = false,
    )
    return "You are a careful assistant. Available skills:\n$catalogue"
}
```

Pass the returned text to `AIAgent`'s `systemPrompt`. Discovery alone does not install tools or load instructions into a conversation. The upstream `ai.koog.skills.model.Skill` contains `name`, `description`, `location`, `license`, `compatibility`, `metadata` and `allowedTools`; its serialised form has no instruction body. Treat `allowedTools` as descriptive metadata, not permission to run a tool.

`SkillsPromptFormat.XML`, `JSON` and `YML` produce an `available_skills` envelope, including for an empty list. Name and description are always included. Location defaults to included; disable it explicitly when paths must remain private. Licence, compatibility, metadata and allowed tools have separate inclusion flags, all false by default. XML generation rejects included characters forbidden by XML 1.0 and unpaired UTF-16 surrogates. YAML preserves string metadata keys, including reserved scalar words, and JSON escapes control characters.

## Documents and discovery boundaries

A skill directory must match its document's name:

```text
configured-skills/
    example/
        SKILL.md
```

```markdown
---
name: example
description: Explains an example task
---
Follow the validated steps for this task.
```

Names use lowercase ASCII letters or digits joined by single hyphens, with at most 64 characters. Discovery requires string fields, strict UTF-8 and matching directory names; duplicate YAML keys and aliases are rejected. The upstream profile accepts its documented optional fields and an empty body. Legacy loading requires a non-blank instruction body and rejects unknown fields by default.

Discovery includes each root and traverses breadth-first to depth 4 by default. It skips `.git` and `node_modules`, bounds visits and listings to 2,000 directories, and applies the remaining `SkillLimits` to roots, documents, YAML, descriptions, bodies and candidates before deduplication. `SkillCollisionPrecedence.LAST_FOUND` is the default; `FIRST_FOUND` keeps the earlier descriptor. Malformed documents and traversal-budget exhaustion produce redacted warnings. File-byte limits and secure filesystem failures remain fatal.

JVM filesystem discovery requires `SecureDirectoryStream` and relative no-follow operations. Symlinks are rejected, including links within the root. Filesystems without secure directory streams fail closed. The standard `JVMFileSystemProvider.ReadOnly` is accepted; arbitrary providers, including wrappers around it, are rejected before they are invoked. There is no unrestricted `readBytes` fallback.

For memory-backed discovery, copy consumer-owned bytes into the bounded adapter:

```kotlin
import ai.koog.skills.discovery.SkillFileSystemSnapshot
import ai.koog.skills.discovery.discoverSkills

suspend fun memorySkills() = discoverSkills(
    SkillFileSystemSnapshot(
        mapOf(
            "/skills/example/SKILL.md" to
                "---\nname: example\ndescription: Example skill\n---\nInstructions".encodeToByteArray(),
        ),
    ),
    listOf("/skills"),
)
```

Snapshot paths must be absolute and normalised. Construction infers parents, checks entry and byte budgets and copies all byte arrays. It never delegates discovery to the host filesystem.

## Metadata-only catalogue and typed loading

The legacy `ai.koog.skills.Skill(name, description, instructions)` remains a separate loadable value. Use its registry when the agent should load captured instructions by exact name without reading source files again:

```kotlin
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.skills.JvmFileSystemSkillSource
import ai.koog.skills.SkillCatalogueRenderer
import ai.koog.skills.SkillRegistry
import ai.koog.skills.mergeToolInto
import java.nio.file.Path

suspend fun skillTools(): Pair<String?, ToolRegistry> {
    val registry = SkillRegistry.build(
        listOf(JvmFileSystemSkillSource(listOf(Path.of("configured-skills")))),
    )
    return SkillCatalogueRenderer.render(registry) to
        registry.mergeToolInto(ToolRegistry.EMPTY)
}
```

Place the returned catalogue in the system prompt when it is non-null, and pass the tools to the agent. This catalogue contains only names and descriptions, omits paths and instructions, and returns `null` when empty. The typed `load_skill` tool returns captured name, description and instructions. Exact case-sensitive lookup never accesses sources; unknown names, including traversal-shaped strings, return typed errors. Tool collisions fail by default, with explicit `KEEP_EXISTING` and `REPLACE` policies. An empty registry leaves the tool registry unchanged.

Registry construction reads sources sequentially and produces a lexically ordered immutable snapshot. Reloading returns a replacement registry. The legacy filesystem profile discovers direct children by default, fails on missing roots, malformed documents and duplicate names, and uses the same secure parser and discovery engine. Use `InMemorySkillSource` for already validated legacy values.

## Tools and execution

Applications own instruction precedence, prompt placement, root selection, reload timing and authorisation. If a workflow uses `ListDirectoryTool`, `ReadFileTool` or a script-execution tool, configure and review its access boundary separately. A read-only provider prevents writes but does not itself restrict which paths a general file tool can read. The discovery boundary does not constrain other registered tools. Keep execution tools narrow, validate structured arguments and script paths, and disclose the selected skill before running its instructions.

See the [Agent Skills specification](https://agentskills.io/specification) for the document format. Kroog's strict parsing and filesystem policies above apply to this implementation.
