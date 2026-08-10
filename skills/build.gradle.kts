import ai.koog.gradle.publish.maven.Publishing.publishToMaven

val isBeta by extra(true)

plugins {
    id("ai.kotlin.multiplatform.server")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":agents:agents-tools"))
                implementation(libs.kotlinx.serialization.json)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.snakeyaml.engine)
            }
        }

        jvmTest {
            dependencies {
                implementation(kotlin("test-junit5"))
            }
        }
    }

    explicitApi()
}

publishToMaven()
