# Publishing Kroog

Kroog snapshots use Maven coordinates under `com.kreoh.kroog`, for example `com.kreoh.kroog:koog-agents:1.0.0-kroog.1-SNAPSHOT`. Kotlin packages and generated Android namespaces remain under `ai.koog` for compatibility with the upstream JetBrains Koog project.

Snapshot publication is opt-in and uses Gradle's existing `maven-publish` support:

```shell
./gradlew publishAllPublicationsToCentralPortalSnapshotsRepository \
    -PpublishCentralSnapshots=true \
    --no-parallel
```

Provide Central Portal credentials as Gradle properties named `centralPortalSnapshotsUsername` and `centralPortalSnapshotsPassword`. Environment variables use Gradle's standard mapping:

```shell
export ORG_GRADLE_PROJECT_centralPortalSnapshotsUsername="your-username"
export ORG_GRADLE_PROJECT_centralPortalSnapshotsPassword="your-password"
```

## Publish from GitHub Actions

CI publication is manual. The `Publish Maven snapshot` workflow publishes all Kroog Maven publications from the
`master` branch of `Kreoh/kroog`. It runs on macOS so that the complete Kotlin Multiplatform publication set is
available. The workflow rejects other repositories, branches, and versions which do not end in `-SNAPSHOT`.

Configure the repository once:

1. Sign in to [Maven Central Portal](https://central.sonatype.com/) with an account authorised for the verified
   `com.kreoh` namespace. Authorisation of this parent namespace covers `com.kreoh.kroog`.
2. Open the verified `com.kreoh` namespace menu, choose **Enable SNAPSHOTs**, and confirm that the SNAPSHOT-enabled
   badge is present before continuing.
3. Open the account page and generate a user token. Central Portal supplies a token username and token password.
4. In the GitHub repository, open **Settings**, then **Secrets and variables**, then **Actions**.
5. Add a repository secret named `CENTRAL_PORTAL_USERNAME` with the token username.
6. Add a repository secret named `CENTRAL_PORTAL_PASSWORD` with the token password.

To publish a snapshot:

1. Confirm that the `master` branch resolves to the intended `-SNAPSHOT` version.
2. Open **Actions**, select **Publish Maven snapshot**, and choose **Run workflow**.
3. Select the `master` branch and start the run.
4. Check that the credential and version preflight steps pass, then wait for the publication step to finish.

Published artefacts use the group `com.kreoh.kroog`, the existing module artefact names, and the Gradle project
version. For example, the `koog-agents` module is published as
`com.kreoh.kroog:koog-agents:1.0.0-kroog.1-SNAPSHOT`. Kotlin Multiplatform modules also publish their platform
variants and module metadata under the corresponding coordinates.

If the workflow fails, inspect the first failing step without copying secret values into logs or issues. Correct an
unset or expired repository secret, a Central Portal namespace permission, a non-snapshot version, or the reported
Gradle failure, then run the workflow again from `master`. Do not replace the token secrets with a Central Portal
account password. If Central Portal returns 403 or rejects the snapshot repository, confirm that **Enable SNAPSHOTs**
is active for the verified `com.kreoh` namespace. A failed publication can leave some snapshot artefacts in the
remote repository, so confirm the expected coordinates after a retry before telling consumers to use that snapshot.

## Consume a snapshot

ChatUI and other consumers of snapshots must add the Central Portal snapshot repository:

```kotlin
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}
```

## Validate locally

Validate locally without an external upload by publishing to `build/artifacts/maven`:

```shell
./gradlew :koog-agents:publishAllPublicationsToArtifactsRepository --no-parallel
```

Stable releases will use the same `com.kreoh.kroog` group without the `-SNAPSHOT` suffix and should follow a
separate, reviewed release process.
