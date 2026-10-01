# Publishing Kroog JVM releases and snapshots

Kroog releases and snapshots use Maven coordinates under `com.kreoh.kroog`. Kotlin packages
remain under `ai.koog` for compatibility with JetBrains Koog.

The complete catalogue contains 69 Kotlin JVM publications and 18 pure-JVM
Maven publications, 87 in total. `gradle/kroog-jvm-publications.txt` is the
shared source of truth for snapshot and stable release workflows. The base
stable version is `1.3.0-kroog.2`; modules which apply a beta version transform
retain their module-specific version. Publication requires Ubuntu 24.04,
Java 21, `--no-parallel` and `--no-daemon`.

## Kroog version numbering

Kroog uses `UPSTREAM_VERSION-kroog.REVISION`. The major, minor and patch
components must match the latest incorporated upstream Koog release. The
`kroog` revision counts Kroog releases based on that upstream version.

- When incorporating a newer Koog release, update all three base components to
  that release and reset the Kroog revision to `1`.
- While the incorporated Koog version stays unchanged, increment only the
  Kroog revision for subsequent releases.
- For example, incorporating Koog `1.3.0` changes the release line from
  `1.1.1-kroog.15` to `1.3.0-kroog.1`. A subsequent Kroog release on that
  baseline is `1.3.0-kroog.2`; incorporating Koog `1.3.1` starts
  `1.3.1-kroog.1`.
- Never keep an older base version after incorporating a newer upstream
  release. Document Kroog-specific features, fixes and compatibility changes
  in the release notes.
- Preserve existing tags, published coordinates and historical validation
  records. If a proposed coordinate was already used, choose the next unused
  revision on the correct upstream base; never overwrite it.

`gradle.properties` is the source of truth for the configured Kroog version.
Before release preparation, compare its base with the incorporated upstream
version recorded in the alignment audit. A mismatch blocks release. Update
the README and source version documentation together. Keep installation dependency
examples on confirmed published coordinates until new artefacts are available
from Central and dependency resolution has been verified.
See `VERSIONING.md` for the policy and beta-module version forms.

For the current release configured as `1.3.0-kroog.2`, the version forms are:

| Purpose | Version or tag |
|---------|----------------|
| Stable modules | `1.3.0-kroog.2` |
| Beta modules, derived automatically | `1.3.0-beta-kroog.2` |
| Stable module snapshots | `1.3.0-kroog.2-SNAPSHOT` |
| Beta module snapshots | `1.3.0-beta-kroog.2-SNAPSHOT` |
| Annotated release tag | `1.3.0-kroog.2` |

The release tag exactly matches the stable version, with no `v` prefix.
Beta modules share that release tag; they do not need separate tags. These
forms describe the configured release revision. Local preparation does not
publish artefacts to Maven Central. Revision `1` records the first streamed-identity
preparation; revision `2` contains the later correctness batches. Neither is
a publication announcement. Installation examples retain confirmed published
`1.1.1-kroog.15` and `1.1.1-beta-kroog.15` until the new release is verified.

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
Snapshot staging requires exactly 87 POMs and 3,045 files: seven primary files
per coordinate, each accompanied by MD5, SHA-1, SHA-256 and SHA-512 checksums.
The snapshot workflow checks every required file and checksum. The release
bundle requires exactly 87 coordinate entries and 1,392 files: four primary
files per coordinate, each accompanied by a signature, MD5 and SHA-1 checksum.

`com.kreoh.kroog:skills-jvm` is a standalone beta publication. Its local
snapshot version is `1.3.0-beta-kroog.2-SNAPSHOT`, and its release version is
`1.3.0-beta-kroog.2`. It remains excluded from the stable `koog-agents`
umbrella and is included in the JVM publication of `koog-agents-additions`.
Remote publication remains
part of the normal release workflow.

`prompt-executor-managed-execution-jvm` exports
`aws.sdk.kotlin:bedrockagentcore-jvm:1.6.72` in its JVM POM. The Bedrock
client exports `aws.sdk.kotlin:bedrockruntime-jvm:1.6.72`. Consumers must retain both transitives
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

The repository is written to `build/artifacts/maven`. Use a fresh destination
for each validation so old snapshots cannot contribute to the closure counts.
Ordinary local staging is unsigned; leave `TEAMCITY_VERSION` and
`KOOG_GITHUB_RELEASE` unset. Release signing is checked separately.
Require non-empty binary, sources and Javadoc JARs, a POM, a Gradle module file,
and both version and coordinate Maven metadata files for every coordinate.
Record the source commit, logical coordinate, resolved snapshot filenames,
sizes and SHA-256 checksums in a machine-readable manifest.

Kotlin Gradle Plugin emits a `.module` file for each Kotlin JVM target which
points at an unpublished Kotlin Multiplatform root. The target-only contract
therefore consumes the generated POM and binary artefact explicitly and ignores
Gradle metadata redirection. The `.module` files remain required, checksummed
publication evidence; consumers must not use them for dependency resolution.

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

### Release preparation

1. Merge the intended feature and fix PRs into `master`, including any branch
   refreshes. Choose the exact source commit to prepare for release.
2. Verify the incorporated Koog version against the alignment audit. Match the
   Kroog base to that version and reset the revision to `1` when the upstream
   base changes. Check existing tags and published coordinates before choosing
   the next unused revision on that base. Update
   `version` in `gradle.properties`, current dependency examples and version
   documentation. Keep historical release notes and audit evidence unchanged.
3. Add Kroog release notes covering user-visible features, fixes, compatibility
   changes and the incorporated upstream version. Complete the README release
   gate below and review the release commit.
4. Complete the model catalogue release gate below for every model change.
   Run the relevant module-specific JVM tests and JVM ABI checks. Investigate
   failing CI checks and document any existing limitations before release.
   Validate all 87 publications from a clean archive of the exact release commit
   using the local publication procedure above and a fresh output directory.
5. Merge the reviewed release preparation into `master`. If the final commit
   changes the validated source, repeat the affected checks. Check out the exact
   release commit with a clean working tree before creating its annotated tag.
6. Push the tag and verify its object on origin, then manually dispatch the
   release workflow with that exact tag, as described below.
7. Inspect the signed bundle and Central Portal validation results. Approve
   publication in Central Portal only after validation succeeds. Create the
   GitHub release against the same tag with the reviewed release notes, then
   update consumers once the artefacts are available from Central.

Pushing a tag or creating a GitHub release does not trigger Maven publication.
The CI workflow is manually dispatched and uploads a deployment for human
approval in Central Portal. Never reuse or move a tag after a failed release;
prepare a new Kroog revision and tag for the corrected release.

### README release gate

Before tagging each release, review every README section against the chosen
release source and update it alongside the release notes. README accuracy is a
release requirement.

- Keep Kroog's identity, Apache 2.0 licence, JVM-only scope and relationship with
  upstream Koog clear. Record the exact incorporated Koog version and comparison
  date. Describe contributions upstream only where supported by evidence.
- Refresh the additional features and fixes section against that incorporated
  Koog baseline. Link each difference to implementation, tests or release notes.
  Remove or qualify differences that the incorporated upstream version now
  includes. Avoid undated claims that upstream lacks a feature.
- Refresh the recent model table, especially OpenAI, Anthropic and Google.
  Distinguish the model developer from each supported hosting provider and API.
  Verify entries against both provider definitions and the central
  `ModelCatalogue`, including aliases and route-specific restrictions. State the
  verification date and relevant Kroog release; link to detailed capability and
  validation evidence. Do not infer one route's support from another route.
- Keep Maven Central installation instructions current for Gradle Kotlin DSL
  and Maven: repository configuration, `com.kreoh.kroog` coordinates, JVM artefact
  IDs, the stable umbrella, optional beta modules and their distinct versions,
  JVM requirements and the required POM-based Gradle resolution settings.
  Explain that Kotlin imports remain under `ai.koog`.
- Check the minimal usage example and linked documentation against the release
  API. Keep installation examples on a confirmed published version until the
  new artefacts are available from Central, then update them and verify
  dependency resolution. Clearly distinguish release preparation from published
  availability.
- Keep documentation about Kroog self-contained. Do not name consuming projects
  or include their internal configuration, deployment procedures, credentials
  setup or adoption claims. Use generic application examples where needed.

Record the README review and any deferred post-publication version update in the
release evidence. A stale feature comparison or unsupported model-route claim
blocks release preparation.

### Model catalogue release gate

Before creating a release tag or dispatching publication, review every model addition and profile change since the
previous release against the model support gate in `TESTING.md`. Record each canonical semantic ID, alias and
provider API with its catalogue test evidence. An omitted profile, stale capability or unresolved catalogue
contract gap blocks release preparation.

Run `:prompt:prompt-model:jvmTest` and the affected provider JVM tests on the chosen release source.
Verify that the normalised catalogue fixture and expected ID set include the reviewed changes.
A publication count, successful compilation or successful live provider request does not verify catalogue completeness.

Extend the separate JVM consumer smoke test against the freshly staged Maven artefacts to call
`ModelCatalogue.find()` for every added or changed semantic ID and alias. Assert the expected limits, reasoning,
temperature restrictions, MIME types, structured output, hosted execution and `compatibility()` results for
the intended provider APIs. Check provider definitions alongside the catalogue, accounting for documented route
restrictions and the distinction between context windows and input limits. Record the source commit, resolved
coordinates, assertions and test result in the release evidence.

Applications rely on Kroog's catalogue for capability and limit discovery. Repair catalogue omissions before
release; if a release is already published, prepare a new revision. Keep previously pushed release tags immutable.

### Tagging and CI dispatch

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
