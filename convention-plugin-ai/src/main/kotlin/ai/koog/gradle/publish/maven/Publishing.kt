package ai.koog.gradle.publish.maven

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import java.net.URI

object Publishing {
    fun Project.publishToMaven() {
        publishTo({
            it.artifactsMaven(project)
            if (findProperty("publishCentralSnapshots")?.toString()?.toBoolean() == true) {
                it.centralPortalSnapshots()
            }
        }) {
            // Use configureEach so that publications added lazily by AGP (e.g. the Android
            // release variant) also get POM metadata. An eager forEach would miss them because
            // AGP registers its publications after this block runs.
            it.publications.withType(MavenPublication::class.java).configureEach {
                pom(
                    Action {
                        val pom = this

                        pom.name.set(this@publishToMaven.name)
                        pom.description.set("Kroog is a framework for quickly creating AI agents in Kotlin with minimal effort.")
                        pom.url.set("https://github.com/Kreoh/kroog")

                        pom.licenses(
                            Action {
                                val licenses = this

                                licenses.license(
                                    Action {
                                        val license = this

                                        license.name.set("The Apache License, Version 2.0")
                                        license.url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                    }
                                )
                            }
                        )

                        pom.developers(
                            Action {
                                val developers = this

                                developers.developer(
                                    Action {
                                        val developer = this

                                        developer.id.set("Kreoh")
                                        developer.name.set("Kreoh")
                                        developer.organization.set("Kreoh")
                                        developer.organizationUrl.set("https://github.com/Kreoh")
                                    }
                                )
                            }
                        )

                        pom.scm(
                            Action {
                                val scm = this
                                scm.url.set("https://github.com/Kreoh/kroog")
                                scm.connection.set("scm:git:https://github.com/Kreoh/kroog.git")
                                scm.developerConnection.set("scm:git:ssh://git@github.com/Kreoh/kroog.git")
                            }
                        )
                    }
                )
            }
        }

        tasks.withType(PublishToMavenRepository::class.java).configureEach {
            doFirst {
                if (repository.name == "centralPortalSnapshots" && !publication.version.endsWith("-SNAPSHOT")) {
                    throw GradleException(
                        "Central Portal snapshot publication requires a -SNAPSHOT version, but was ${publication.version}."
                    )
                }
            }
        }
    }

    private fun Project.publishTo(
        configureRepository: (RepositoryHandler) -> Unit,
        configurePublish: (PublishingExtension) -> Unit = {}
    ) {
        pluginManager.apply("maven-publish")

        extensions.configure<PublishingExtension>("publishing") {
            repositories(Action { configureRepository(this) })
            configurePublish(this)
        }
    }

    private fun RepositoryHandler.artifactsMaven(project: Project) {
        maven(
            Action {
                val repo = this

                repo.name = "artifacts"
                repo.url = project.rootProject.layout.buildDirectory.dir("artifacts/maven").get().asFile.toURI()
            }
        )
    }

    private fun RepositoryHandler.centralPortalSnapshots() {
        maven(
            Action {
                val repo = this

                repo.name = "centralPortalSnapshots"
                repo.url = URI.create("https://central.sonatype.com/repository/maven-snapshots/")
                repo.credentials(PasswordCredentials::class.java)
                repo.mavenContent { snapshotsOnly() }
            }
        )
    }
}
