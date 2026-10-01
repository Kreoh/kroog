# Kroog versioning

Kroog uses `UPSTREAM_VERSION-kroog.REVISION`. The major, minor and patch
components identify the latest incorporated upstream Koog release. The Kroog
revision identifies successive releases containing additional fixes and features
on that upstream baseline.

## Choosing the next version

1. Establish the incorporated Koog version from the upstream alignment audit.
2. If that version has changed, use its exact major, minor and patch components
   and reset the Kroog revision to `1`.
3. If the upstream version has not changed, increment the Kroog revision.
4. Check existing tags and published coordinates. Never overwrite a release or
   reuse its tag; choose the next unused revision on the correct upstream base.
5. Update `gradle.properties`, source version documentation and release notes
   together. Keep installation examples on confirmed published coordinates
   until the new artefacts are available and dependency resolution is verified.
   Record additional features, fixes and any compatibility changes.

The current source incorporates Koog `1.3.0`, so its configured version is
`1.3.0-kroog.2`. Keeping `1.1.1` as the base after incorporating Koog `1.3.0`
is incorrect. A mismatch between the incorporated upstream version and the
configured Kroog base blocks release preparation.

| Change | Resulting version |
| --- | --- |
| Incorporate Koog 1.3.0 after the old 1.1.1-based numbering | `1.3.0-kroog.1` |
| Release further Kroog changes on Koog 1.3.0 | `1.3.0-kroog.2` |
| Incorporate Koog 1.3.1 | `1.3.1-kroog.1` |
| Incorporate Koog 1.4.0 | `1.4.0-kroog.1` |
| Incorporate Koog 2.0.0 | `2.0.0-kroog.1` |

The upstream semantic version supplies the base. Kroog's suffix is its fork
release convention; it is a prerelease identifier under SemVer ordering.
Do not describe a suffixed version as a plain SemVer final release.

The first streamed-identity preparation remains `1.3.0-kroog.1`; the subsequent correctness batches prepare revision `2`. Neither preparation announces Maven Central publication. Installation examples use confirmed published `1.1.1-kroog.15` and `1.1.1-beta-kroog.15` until the new coordinates are published and verified.

## Stable modules, beta modules and snapshots

| Publication | Current source version |
| --- | --- |
| Stable modules and annotated release tag | `1.3.0-kroog.2` |
| Beta modules | `1.3.0-beta-kroog.2` |
| Stable module snapshots | `1.3.0-kroog.2-SNAPSHOT` |
| Beta module snapshots | `1.3.0-beta-kroog.2-SNAPSHOT` |

Beta versions are derived automatically. All modules share the same upstream
base and Kroog revision. The stable umbrella is `koog-agents-jvm`; the beta
umbrella is `koog-agents-additions-jvm`. The exact publication set is recorded
in [the JVM inventory](gradle/kroog-jvm-publications.txt).

These are source preparation versions. Availability from Maven Central must
be confirmed separately. Preserve historical release notes, validation records,
published versions and tags when correcting the current version.

See [PUBLISHING.md](PUBLISHING.md) for release preparation, README maintenance,
JVM validation and publication.
