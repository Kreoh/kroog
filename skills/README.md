# Agent Skills for Kroog

The `skills-jvm` module provides generic, beta APIs for validating skill
documents, building immutable registries, rendering metadata catalogues and
registering a typed `load_skill` tool. It contains no bundled skills.

Use the local snapshot while developing against this Kroog line:

```kotlin
dependencies {
    implementation("com.kreoh.kroog:skills-jvm:1.3.0-beta-kroog.1-SNAPSHOT")
}
```

The corresponding release coordinate is
`com.kreoh.kroog:skills-jvm:1.3.0-beta-kroog.1`. Publication is deferred to
Kroog's normal release workflow.

## Skill documents

Filesystem discovery expects direct children named `<root>/<name>/SKILL.md` by
default. Each document starts with an exact, closed YAML frontmatter block and
has a non-blank Markdown body:

```markdown
---
name: example-skill
description: Explains how to perform an example task
---
Follow the validated steps for the example task.
```

`name` and `description` must be strings. The name must match its directory,
use at most 64 characters, and contain lowercase ASCII letters or digits joined
by single hyphens. Names, descriptions and bodies must be trimmed and
non-blank. Unknown frontmatter fields, duplicate YAML keys, aliases, recursive
keys and directory-name mismatches are rejected by default. Folded and plain
YAML strings are supported by the constrained YAML 1.2 parser.

`SkillParser.parse(document, expectedName, policy)` validates a raw document on
the JVM. Constructing `Skill(name, description, instructions)` applies the same
core field and name validation without requiring YAML or filesystem access.

## Building and using a registry

Use `InMemorySkillSource` for validated values:

```kotlin
val source = InMemorySkillSource(
    listOf(Skill("example-skill", "Explains an example task", "Follow the steps.")),
)
val registry = SkillRegistry.build(listOf(source))
```

On the JVM, load consumer-owned roots with bounded filesystem discovery:

```kotlin
val source = JvmFileSystemSkillSource(listOf(Path.of("configured-skills")))
val registry = SkillRegistry.build(listOf(source))
```

Import `java.nio.file.Path` for the filesystem example. Registry build and
reload are suspending calls. Sources are read only while `SkillRegistry.build`
runs. The resulting registry is an immutable,
lexically ordered snapshot; `findExact(name)` is a case-sensitive map lookup
and performs no source or filesystem access. `reload(sources, policy)` reads the
supplied sources and returns a distinct replacement snapshot. It never mutates
the active registry.

`SkillCatalogueRenderer.render(registry)` returns stable JSON containing only
each skill's `name` and `description`. It omits instructions, source paths and
diagnostics, and returns `null` for an empty registry.

For a non-empty registry, `registry.mergeToolInto(toolRegistry)` registers the
typed `load_skill` tool. The tool performs exact-name lookup and returns
`LoadSkillResult(name, description, instructions)` without source details.
Unknown names, including traversal-shaped strings, are ordinary missing map
keys. The checked merge detects an existing `load_skill`; collision handling is
`FAIL` by default, with explicit `KEEP_EXISTING` and `REPLACE` alternatives.
An empty registry returns the supplied tool registry unchanged.

## Safe defaults and limits

`SkillLoadPolicy()` uses direct-child discovery, fails on missing roots and
malformed or duplicate skills, rejects unknown fields and symlinks, redacts
diagnostic paths, selects immutable snapshot lifecycle and decodes with strict
UTF-8. `ToolCollisionPolicy.FAIL` is the separate tool-merge default.

`SkillLimits()` permits at most:

- 16 roots
- 1,024 directory entries and 1,024 discovered directories
- 256 skills
- 1,048,576 bytes per `SKILL.md`
- 1,024 Unicode code points per description
- 100,000 Unicode code points per instruction body
- 100,000 Unicode code points in the rendered catalogue
- 8 recursive directory levels when recursive discovery is selected
- 1,000,000 YAML code points and 32 YAML nesting levels

Every numerical limit must be positive. Recursive discovery must be selected
explicitly with `SkillDiscoveryMode.RECURSIVE`. Limit exhaustion fails with a
typed error by default; `MalformedSkillPolicy.SKIP_WITH_DIAGNOSTIC` can turn
malformed entries and discovery-budget exhaustion into diagnostics. Missing
roots, duplicates, unknown fields and diagnostic path disclosure each have
their own explicit policy.

## Filesystem and diagnostics contract

JVM discovery requires a filesystem that supplies `SecureDirectoryStream`.
Filesystems without it fail with `SkillError.IoFailure`, and discovery has no
fallback path. Entries are inspected and opened relative to the secure root
with no-follow operations, which preserves containment during concurrent path
replacement. Strict character decoding reports malformed or unmappable input
as `SkillError.DecodingFailure`.

Symlinks fail closed. `SymlinkPolicy.REJECT` produces
`SkillError.SymlinkRejected` for every directory or document link, whether its
target is internal or escaping. Race-safe link following is unavailable, so the
public policy does not offer a link-following option.

Filesystem failures remain typed as `SkillError.IoFailure` inside
`SkillException`; they are not converted into missing skills. With the default
`DiagnosticPathPolicy.REDACT`, errors and diagnostics expose neither source
paths nor underlying exception details. Select `DISCLOSE` only in a trusted
diagnostic context.

## Consumer responsibilities

Consumers own skill roots and content, catalogue placement, prompt wording and
ordering, instruction precedence, authorisation, tool exposure, startup
strictness, reload timing, audit integration, diagnostic disclosure and any
runtime or user-interface exposure. Keep those choices outside this module.

## Upstream discovery and prompt compatibility

The module also exposes Koog 1.3.0's `ai.koog.skills.model.Skill`,
`ai.koog.skills.discovery.discoverSkills` and
`ai.koog.skills.prompt.generateSkillsPrompt`. The upstream model retains its
seven fields: `name`, `description`, `location`, `license`, `compatibility`,
`metadata` and `allowedTools`. Its serialised form has no instruction body.
The legacy `ai.koog.skills.Skill(name, description, instructions)` remains a
separate loadable value with its existing constructors and components.

```kotlin
val discovered = discoverSkills(
    JVMFileSystemProvider.ReadOnly,
    listOf(Path.of("configured-skills").toAbsolutePath().toString()),
)
val prompt = generateSkillsPrompt(discovered, SkillsPromptFormat.JSON)
```

Import the discovery and prompt functions from the packages above, and
`JVMFileSystemProvider` from `ai.koog.rag.base.files`. Discovery includes each
configured root, traverses breadth-first to depth 4 by default, skips `.git`
and `node_modules`, and selects the last discovered name on collision.
`SkillCollisionPrecedence.FIRST_FOUND` selects the first instead. The default
2,000-directory budget also bounds directory listings; the remaining default
`SkillLimits` apply to roots, documents, YAML, descriptions, bodies and the
number of candidates before deduplication. A custom name pattern can further
restrict the mandatory ASCII name rules.

Both APIs share a strict document parser, complete metadata-and-body records,
a scoped discovery engine and duplicate selection. The upstream profile accepts
its documented optional frontmatter fields, requires string values (including
metadata keys and values), and allows an empty body for metadata discovery.
Legacy loading requires a non-blank body. Duplicate keys, YAML aliases,
directory-name mismatches and malformed names are rejected. Upstream discovery
reports malformed documents and exhausted traversal budgets through redacted
warnings. File-byte limits and secure filesystem failures remain fatal.

On the JVM, generic discovery accepts the standard `JVMFileSystemProvider.ReadOnly`
through a secure descriptor session. It rejects arbitrary providers, including
wrappers around the JVM provider, before invoking them. There is no unrestricted
`readBytes` fallback. Use the bounded, copied in-memory provider for generic
memory-backed discovery:

```kotlin
val memory = SkillFileSystemSnapshot(
    mapOf("/skills/example/SKILL.md" to
        "---\nname: example\ndescription: Example skill\n---\nInstructions".encodeToByteArray()),
)
val discovered = discoverSkills(memory, listOf("/skills"))
```

Import `SkillFileSystemSnapshot` from `ai.koog.skills.discovery`. Paths must be
absolute and normalised; parent directories are inferred. Construction checks
entry, per-file and aggregate byte budgets and copies every byte array.
Discovery never accesses the host filesystem through this provider.

`SkillsPromptFormat.XML`, `JSON` and `YML` retain the upstream envelopes and
non-null empty output. Location is included by default; optional fields have
explicit inclusion flags. All formats share the same structured catalogue;
JSON uses complete control-character escaping. YAML quotes control characters,
unsafe metadata keys and reserved scalar keys such as `true`, `false` and `null`,
preserving string keys and their case. XML escapes entities and preserves valid
Unicode and whitespace. XML generation throws `IllegalArgumentException` for
included values containing XML 1.0 forbidden characters or unpaired UTF-16
surrogates; excluded fields are not checked. Legacy rendering uses its
metadata-only compact array projection, still returns `null` when empty and
still enforces its final encoded code-point limit. Registry and tool lookups
use captured bodies and never read a descriptor a second time.
