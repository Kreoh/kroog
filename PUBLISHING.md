# Publishing Kroog JVM snapshots

Kroog snapshots use Maven coordinates under `com.kreoh.kroog`. Kotlin packages
remain under `ai.koog` for compatibility with JetBrains Koog.

The ChatUI dependency closure is built from source commit
`a9b1ecdbfca6656b71ca54188fa29c9e27798326`. It contains 37 Kotlin JVM target
coordinates plus the pure-JVM `serialization-jackson` coordinate, 38 in total.
Thirty-seven coordinates use `1.0.0-kroog.1-SNAPSHOT`; the Google client uses
`1.0.0-beta-kroog.1-SNAPSHOT`. Publication requires Ubuntu 24.04, Java 21 and
`--no-parallel`.

## Exact target closure

The stable Kotlin JVM targets are:

```text
agents-core-jvm
agents-features-event-handler-jvm
agents-features-memory-jvm
agents-features-opentelemetry-jvm
agents-features-snapshot-jvm
agents-features-tokenizer-jvm
agents-features-trace-jvm
agents-mcp-metadata-jvm
agents-tools-jvm
agents-utils-jvm
embeddings-base-jvm
embeddings-llm-jvm
http-client-core-jvm
http-client-ktor-jvm
koog-agents-jvm
prompt-cache-files-jvm
prompt-cache-model-jvm
prompt-executor-cached-jvm
prompt-executor-clients-jvm
prompt-executor-anthropic-client-jvm
prompt-executor-bedrock-client-jvm
prompt-executor-ollama-client-jvm
prompt-executor-openai-client-jvm
prompt-executor-openai-client-base-jvm
prompt-executor-managed-execution-jvm
prompt-executor-model-jvm
prompt-llm-jvm
prompt-markdown-jvm
prompt-model-jvm
prompt-processor-jvm
prompt-structure-jvm
prompt-tokenizer-jvm
prompt-xml-jvm
rag-base-jvm
serialization-core-jvm
utils-jvm
```

The beta Kotlin JVM target is `prompt-executor-google-client-jvm`. The
additional stable pure-JVM target is `serialization-jackson`.

`prompt-executor-managed-execution-jvm` exports
`aws.sdk.kotlin:bedrockagentcore:1.6.72`. The Bedrock client exports
`aws.sdk.kotlin:bedrockruntime:1.6.72`. Consumers must retain both transitives
unless their build has verified an intentional exclusion.

## Validate the local publication

Run the exact module-qualified tasks from a clean archive of the pinned commit.
Do not run aggregate publication or Kotlin Multiplatform root publication tasks.

```shell
./gradlew \
  :agents:agents-core:publishJvmPublicationToArtifactsRepository \
  :agents:agents-features:agents-features-event-handler:publishJvmPublicationToArtifactsRepository \
  :agents:agents-features:agents-features-memory:publishJvmPublicationToArtifactsRepository \
  :agents:agents-features:agents-features-opentelemetry:publishJvmPublicationToArtifactsRepository \
  :agents:agents-features:agents-features-snapshot:publishJvmPublicationToArtifactsRepository \
  :agents:agents-features:agents-features-tokenizer:publishJvmPublicationToArtifactsRepository \
  :agents:agents-features:agents-features-trace:publishJvmPublicationToArtifactsRepository \
  :agents:agents-mcp-metadata:publishJvmPublicationToArtifactsRepository \
  :agents:agents-tools:publishJvmPublicationToArtifactsRepository \
  :agents:agents-utils:publishJvmPublicationToArtifactsRepository \
  :embeddings:embeddings-base:publishJvmPublicationToArtifactsRepository \
  :embeddings:embeddings-llm:publishJvmPublicationToArtifactsRepository \
  :http-client:http-client-core:publishJvmPublicationToArtifactsRepository \
  :http-client:http-client-ktor:publishJvmPublicationToArtifactsRepository \
  :koog-agents:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-cache:prompt-cache-files:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-cache:prompt-cache-model:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-executor:prompt-executor-cached:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-executor:prompt-executor-clients:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-executor:prompt-executor-clients:prompt-executor-anthropic-client:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-executor:prompt-executor-clients:prompt-executor-bedrock-client:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-executor:prompt-executor-clients:prompt-executor-ollama-client:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-executor:prompt-executor-clients:prompt-executor-openai-client:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-executor:prompt-executor-clients:prompt-executor-openai-client-base:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-executor:prompt-executor-managed-execution:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-executor:prompt-executor-model:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-llm:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-markdown:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-model:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-processor:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-structure:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-tokenizer:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-xml:publishJvmPublicationToArtifactsRepository \
  :rag:rag-base:publishJvmPublicationToArtifactsRepository \
  :serialization:serialization-core:publishJvmPublicationToArtifactsRepository \
  :utils:publishJvmPublicationToArtifactsRepository \
  :prompt:prompt-executor:prompt-executor-clients:prompt-executor-google-client:publishJvmPublicationToArtifactsRepository \
  :serialization:serialization-jackson:publishMavenPublicationToArtifactsRepository \
  --no-parallel --no-daemon
```

The repository is written to `build/artifacts/maven`. Require one non-empty
binary JAR, POM, Gradle module file and Maven metadata file for every coordinate.
Record the source commit, logical coordinate, resolved snapshot filenames,
sizes and SHA-256 checksums in a machine-readable manifest.

Kotlin Gradle Plugin emits a `.module` file for each Kotlin JVM target which
points at an unpublished Kotlin Multiplatform root. The target-only contract
therefore consumes the generated POM and binary artefact explicitly and ignores
Gradle metadata redirection. The `.module` files remain required, checksummed
publication evidence; consumers must not use them for dependency resolution.

## Refresh the ChatUI eligibility manifest

ChatUI tracks `gradle/kroog-jvm-publication-manifest.json` as the fail-closed
eligibility contract for local Kroog repositories. Generate it deterministically
from one clean publication. It records the trusted schema, group, source commit,
stable and beta versions, common timestamp generation, exact sorted coordinate
set and kind, plus seven sorted primary-file records per coordinate: binary,
sources and Javadoc JARs, POM, `.module`, version Maven metadata and coordinate
Maven metadata. Every file record contains a repository-relative path, non-zero
size and SHA-256.

ChatUI validates the complete source tree without following links. Each accepted
Gradle invocation creates an unpredictable owner-only directory under the JVM
temporary root, copies only the recorded primary files into it and resolves from
that private verified snapshot. POSIX mode 0700 or an exactly verified
single-owner ACL is mandatory. Immediately before every project or buildscript
configuration resolves, ChatUI rechecks the captured root identity and exact
266-file sizes, hashes and no-follow closure. Kroog is not configured as a
Gradle plugin repository. Concurrent builds share no snapshot, lock or cache.
Build-finished and JVM-shutdown hooks attempt bounded cleanup without following
links and only while the root retains its original identity. A process crash can
leave one unpredictable temporary directory; later builds do not scan arbitrary
temporary parents. Checksum sidecars remain in the publication source and are
not copied. Explicit remote selection branches before ChatUI reads any local
override, manifest or publication path and creates no snapshot.

After regeneration, update ChatUI's `kroogSourceCommit`,
`kroogSnapshotGeneration` and `kroogManifestSha256` properties together with
the stable or beta version properties when they change. Run the validator
tamper table for a missing coordinate, mixed generation, changed file, extra
root coordinate and stale timestamp generation. Explicit `local` must reject
each copy with a sanitised reason code, while `auto` must fall back to Central Portal.
The exact repository must pass local compilation, dependency reports and named
dependency insights. Do not refresh dependency locks or verification metadata
when the resolved external graph is unchanged.

## Central Portal

Central Portal snapshot publication is opt-in. Supply token credentials through
`ORG_GRADLE_PROJECT_centralPortalSnapshotsUsername` and
`ORG_GRADLE_PROJECT_centralPortalSnapshotsPassword`, and set
`-PpublishCentralSnapshots=true`.

The current `Publish Maven snapshot` workflow still uses broad publication task
selectors and Java 17. It does not yet implement this exact JVM-only contract.
Before it may publish the closure, change it to Ubuntu 24.04 and Java 21,
replace its selectors with the same module-qualified tasks above using the
`publishJvmPublicationToCentralPortalSnapshotsRepository` suffix, use
`publishMavenPublicationToCentralPortalSnapshotsRepository` for
serialization-jackson, retain `--no-parallel`, and generate the manifest after
the remote publication step. The manifest must be uploaded as a workflow
artefact even when a later verification step fails.

Do not use Central Portal credentials during configuration-only checks. A
remote consumer can configure the repository without credentials:

```kotlin
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        mavenContent {
            snapshotsOnly()
        }
        metadataSources {
            mavenPom()
            artifact()
            ignoreGradleMetadataRedirection()
        }
    }
}
```

After a new remote snapshot generation appears, consumers must refresh their
dependency locks and dependency-verification checksums from that generation.
Stable releases require a separate reviewed release process.
