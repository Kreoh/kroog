package ai.koog.gradle.publish.maven

import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SigningTest {
    @Test
    fun testSigningIsDisabledOutsideReleaseBuilds() {
        val project = createProject()

        project.configurePublicationSigning(emptyMap())
        createPublication(project, "test")

        assertNull(project.tasks.findByName("signTestPublication"))
    }

    @Test
    fun testGitHubReleaseSignsPublicationsCreatedLazily() {
        val project = createProject()

        project.configurePublicationSigning(mapOf("KOOG_GITHUB_RELEASE" to "true"))
        createPublication(project, "release")

        assertNotNull(project.tasks.findByName("signReleasePublication"))
    }

    @Test
    fun testTeamCitySignsPublicationsCreatedLazily() {
        val project = createProject()

        project.configurePublicationSigning(mapOf("TEAMCITY_VERSION" to "2026.1"))
        createPublication(project, "teamCity")

        assertNotNull(project.tasks.findByName("signTeamCityPublication"))
    }

    @Test
    fun testTeamCityTakesPrecedenceOverGitHubRelease() {
        assertEquals(
            SigningMode.TEAMCITY,
            signingMode(
                mapOf(
                    "TEAMCITY_VERSION" to "2026.1",
                    "KOOG_GITHUB_RELEASE" to "true",
                )
            )
        )
    }

    @Test
    fun testGitHubReleaseSignalMustBeExplicitlyTrue() {
        assertEquals(SigningMode.NONE, signingMode(mapOf("KOOG_GITHUB_RELEASE" to "false")))
    }

    private fun createProject(): Project = ProjectBuilder.builder().build().also {
        it.pluginManager.apply("maven-publish")
        it.pluginManager.apply("signing")
    }

    private fun createPublication(project: Project, name: String) {
        project.extensions.getByType(PublishingExtension::class.java)
            .publications
            .create(name, MavenPublication::class.java)
    }
}
