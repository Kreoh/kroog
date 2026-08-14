# Publishing Kroog JVM snapshots

Kroog snapshots use Maven coordinates under `com.kreoh.kroog`. Kotlin packages
remain under `ai.koog` for compatibility with JetBrains Koog.

The complete catalogue contains 69 Kotlin JVM publications and 18 pure-JVM
Maven publications, 87 in total. `gradle/kroog-jvm-publications.txt` is the
shared source of truth for snapshot and stable release workflows. The base
stable version is `1.1.1-kroog.3`; modules which apply a beta version transform
retain their module-specific version. Publication requires Ubuntu 24.04,
Java 21, `--no-parallel` and `--no-daemon`.

## Exact target closure

Each non-empty inventory line has one publication kind and one full Gradle
project path:

```text
jvm :agents:agents-core
maven :serialization:serialization-jackson
```

`jvm` selects only `JvmPublication`; `maven` selects only `MavenPublication`.
Both workflows reject malformed lines and require exactly 69 `jvm` entries and
18 `maven` entries. This prevents aggregate publication selectors from adding
Kotlin Multiplatform root, Android, JavaScript, Wasm, Native or iOS artefacts.
Snapshot staging requires exactly 87 POMs and 1,392 files. The release bundle
requires exactly 87 coordinate entries and the same 1,392-file signed and
checksummed closure.

`com.kreoh.kroog:skills-jvm` is a standalone beta publication. Its local
snapshot version is `1.1.1-beta-kroog.3-SNAPSHOT`, and its release version is
`1.1.1-beta-kroog.3`. It remains excluded from `koog-agents` and
`koog-agents-additions`; publication is deferred to the normal release
workflow.

`prompt-executor-managed-execution-jvm` exports
`aws.sdk.kotlin:bedrockagentcore:1.6.72`. The Bedrock client exports
`aws.sdk.kotlin:bedrockruntime:1.6.72`. Consumers must retain both transitives
unless their build has verified an intentional exclusion.

## Validate the local publication

Run the exact module-qualified tasks from a clean archive of the pinned commit.
Do not run aggregate publication or Kotlin Multiplatform root publication tasks.

```shell
tasks=()
while read -r kind project; do
  case "$kind" in
    jvm) tasks+=("${project}:publishJvmPublicationToArtifactsRepository") ;;
    maven) tasks+=("${project}:publishMavenPublicationToArtifactsRepository") ;;
  esac
done < gradle/kroog-jvm-publications.txt
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew \
  "${tasks[@]}" \
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
609-file sizes, hashes and no-follow closure. Kroog is not configured as a
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

The `Publish Maven snapshot` workflow first derives 87 local
`ArtifactsRepository` tasks from the shared inventory and completes them without
publication credentials. Only after that step succeeds does it run the existing
JVM and pure-JVM Maven publication-type selectors and expose credentials to the
remote Gradle invocation. The locally verified inventory establishes that those
selectors resolve to the same 87 publication tasks. Both invocations use Java
21, `--no-parallel` and `--no-daemon`. A local failure therefore prevents every
remote upload.

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

## Stable releases

Stable releases use the separate, manually dispatched `Publish Maven release`
workflow. The configured version in `gradle.properties` must be stable. Create
an immutable tag whose name exactly matches that version, for example
`1.1.1-kroog.1`, only after the release commit has been reviewed. The immutable
tag must use decimal `MAJOR.MINOR.PATCH-kroog.REVISION` fields. Characters
with path or query meaning, separators and whitespace are rejected before
checkout. The immutable `1.0.0-kroog.2` tag uploaded all 85 components, but
Central rejected eight
Spring AI starter POMs because they omitted the matching Spring AI BOM imports.
It must not be moved or reused. The immutable `1.0.0-kroog.1` tag contains the
incomplete 38-coordinate workflow and must not be moved or reused. Protect the
release tag pattern with a GitHub tag ruleset that restricts tag updates and
deletions, and tightly restrict any bypass permission. Never move, delete or
reuse a release tag.

Create and push an annotated tag, then verify that origin has the same tag
object. A lightweight tag is rejected even when it points at the right commit.

```shell
version="$(sed -n 's/^version=//p' gradle.properties)"
git tag -a "$version" -m "Kroog $version"
git push origin "refs/tags/$version"
test "$(git cat-file -t "refs/tags/$version")" = tag
test "$(git ls-remote --refs origin "refs/tags/$version" | cut -f1)" = \
  "$(git rev-parse "refs/tags/$version")"
```

To dispatch in the GitHub browser, open **Actions**, select **Publish Maven
release**, and choose **Run workflow**. Select the repository default branch in
the branch selector, enter the exact annotated tag in `release_tag`, and run
the workflow. The branch selects the trusted workflow definition. The workflow
checks out and publishes the commit peeled from `release_tag`, rather than the
default-branch commit.

The equivalent GitHub CLI command is:

```shell
default_branch="$(gh repo view Kreoh/kroog --json defaultBranchRef --jq \
  '.defaultBranchRef.name')"
gh workflow run publish-maven-release.yml \
  --repo Kreoh/kroog \
  --ref "$default_branch" \
  --field release_tag="$version"
```

An API caller may dispatch from the same exact tag ref instead. In that mode,
both the dispatch ref and `release_tag` must equal the configured stable
version, and the dispatch SHA must equal the tag's peeled commit:

```shell
gh api --method POST \
  "repos/Kreoh/kroog/actions/workflows/publish-maven-release.yml/dispatches" \
  --field ref="$version" \
  --field "inputs[release_tag]=$version"
```

Before dispatch, inspect the repository tag ruleset and confirm that the
release-tag pattern cannot be updated or deleted. The workflow also requires
the exact annotated tag object to exist on origin and match the local checkout.
These checks make the explicit tag the immutable publication source even when
the browser starts the workflow from the default branch.

Configure these GitHub Actions secrets:

- `MAVEN_GPG_PRIVATE_KEY`: the ASCII-armoured, unencrypted private signing key.
- `CENTRAL_PORTAL_USERNAME`: the Central Portal token username.
- `CENTRAL_PORTAL_PASSWORD`: the Central Portal token password.

The workflow imports the signing key with `actions/setup-java` and enables
Gradle's native GPG signing path through `KOOG_GITHUB_RELEASE=true`. TeamCity
continues to use its existing signatory when `TEAMCITY_VERSION` is present.
Do not set either release signal during ordinary local builds.

Before the first release with a signing key, publish its public key to a
supported public keyserver:

```shell
gpg --keyserver keyserver.ubuntu.com --send-keys <full-key-fingerprint>
```

Confirm that the key is discoverable by its full fingerprint before dispatching
the workflow. Restrict the unencrypted private key to the local GnuPG keyring,
the GitHub Actions secret and a protected offline backup. Preserve its
revocation certificate separately.

The workflow derives all 87 module-qualified local tasks from the shared
inventory, then signs and stages every release artefact. It discovers each
coordinate and its actual version from the staged POM path, validates the POM,
JARs, detached signatures and checksum sidecars, and copies the exact Maven
layout into one bundle. This version discovery covers every beta-version module
without a hard-coded beta list. The workflow retains the bundle as a workflow
artefact, then uploads it once to the Central Portal publisher API with
`publishingType=USER_MANAGED`. Curl URL-encodes the verified deployment name and
fixed publishing type before sending the multipart POST. The workflow does not
publish or drop the deployment.

After a successful upload, a human must open Central Portal, inspect the
validation results and exact coordinate closure, and approve publication there.
If validation fails, fix the release commit and prepare a new version and tag.
Do not move the failed tag.

Once Central has published and synchronised the release, update consumers to
the new stable or beta coordinate as appropriate. Refresh dependency locks and
dependency-verification checksums, review the resolved graph, and run the
consumer's focused compilation and test checks before merging the update.
