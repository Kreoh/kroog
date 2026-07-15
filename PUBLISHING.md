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

ChatUI and other consumers of snapshots must add the Central Portal snapshot repository:

```kotlin
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}
```

Validate locally without an external upload by publishing to `build/artifacts/maven`:

```shell
./gradlew :koog-agents:publishAllPublicationsToArtifactsRepository --no-parallel
```

No CI publishing workflow is configured yet. Add one only after repository secrets and branch policy are ready. Stable releases will use the same `com.kreoh.kroog` group without the `-SNAPSHOT` suffix and should follow a separate, reviewed release process.
