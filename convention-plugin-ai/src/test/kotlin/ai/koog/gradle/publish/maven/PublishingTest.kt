package ai.koog.gradle.publish.maven

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PublishingTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun testCentralPortalSnapshotsRepositoryIsAbsentWithoutOptIn() {
        val project = createProject("without-opt-in", "1.0.0-SNAPSHOT", publishCentralSnapshots = false)
        val publishing = project.extensions.getByType(PublishingExtension::class.java)

        assertEquals(setOf("artifacts"), publishing.repositories.names)
        assertNull(project.tasks.findByName("publishTestPublicationToCentralPortalSnapshotsRepository"))
    }

    @Test
    fun testCentralPortalSnapshotsRepositoryUsesExpectedNameAndTask() {
        val project = createProject("with-opt-in", "1.0.0-SNAPSHOT", publishCentralSnapshots = true)
        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        val repository = publishing.repositories.getByName("centralPortalSnapshots")

        assertIs<MavenArtifactRepository>(repository)
        assertEquals("centralPortalSnapshots", repository.name)
        assertEquals("https://central.sonatype.com/repository/maven-snapshots/", repository.url.toString())
        assertNotNull(project.tasks.findByName("publishTestPublicationToCentralPortalSnapshotsRepository"))
    }

    @Test
    fun testCentralPortalSnapshotsGuardRejectsReleaseVersion() {
        val project = createProject("release", "1.0.0", publishCentralSnapshots = true)
        val task = centralPortalSnapshotsTask(project)

        val failure = assertFailsWith<GradleException> {
            task.actions.first().execute(task)
        }

        assertContains(failure.message.orEmpty(), "requires a -SNAPSHOT version")
    }

    @Test
    fun testCentralPortalSnapshotsGuardAcceptsSnapshotVersion() {
        val project = createProject("snapshot", "1.0.0-SNAPSHOT", publishCentralSnapshots = true)
        val task = centralPortalSnapshotsTask(project)

        task.actions.first().execute(task)
    }

    private fun createProject(name: String, version: String, publishCentralSnapshots: Boolean): Project {
        val directory = projectDirectory.resolve(name).toFile().apply { mkdirs() }
        val project = ProjectBuilder.builder().withName(name).withProjectDir(directory).build()
        project.group = "com.kreoh.kroog"
        project.version = version
        if (publishCentralSnapshots) {
            project.extensions.extraProperties.set("publishCentralSnapshots", "true")
        }

        with(Publishing) {
            project.publishToMaven()
        }
        project.extensions.getByType(PublishingExtension::class.java)
            .publications.create("test", MavenPublication::class.java)

        return project
    }

    private fun centralPortalSnapshotsTask(project: Project): PublishToMavenRepository {
        return project.tasks.getByName(
            "publishTestPublicationToCentralPortalSnapshotsRepository"
        ) as PublishToMavenRepository
    }
}
