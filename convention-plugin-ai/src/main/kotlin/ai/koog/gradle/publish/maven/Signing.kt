package ai.koog.gradle.publish.maven

import jetbrains.sign.GpgSignSignatoryProvider
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

internal fun Project.configurePublicationSigning(environment: Map<String, String> = System.getenv()) {
    val signingMode = signingMode(environment)
    if (signingMode == SigningMode.NONE) return

    val signing = extensions.getByType(SigningExtension::class.java)
    when (signingMode) {
        SigningMode.TEAMCITY -> signing.signatories = GpgSignSignatoryProvider()
        SigningMode.GITHUB_RELEASE -> signing.useGpgCmd()
        SigningMode.NONE -> error("Signing mode must be enabled before configuring signing")
    }

    extensions.getByType(PublishingExtension::class.java)
        .publications
        .withType(MavenPublication::class.java)
        .configureEach {
            signing.sign(this)
        }
}

internal fun signingMode(environment: Map<String, String>): SigningMode = when {
    environment.containsKey("TEAMCITY_VERSION") -> SigningMode.TEAMCITY
    environment["KOOG_GITHUB_RELEASE"] == "true" -> SigningMode.GITHUB_RELEASE
    else -> SigningMode.NONE
}

internal enum class SigningMode {
    NONE,
    TEAMCITY,
    GITHUB_RELEASE,
}
