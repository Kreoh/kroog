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
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.core)
                api(project(":rag:rag-base"))
                api(project(":agents:agents-tools"))
                api(libs.kotlinx.serialization.json)
                implementation(libs.oshai.kotlin.logging)
            }
        }

        commonTest {
            dependencies {
                implementation(project(":test-utils"))
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
                implementation(project(":agents:agents-test"))
                implementation(project(":agents:agents-features:agents-features-event-handler"))
                implementation(kotlin("test-junit5"))
                implementation(libs.junit.jupiter.params)
                runtimeOnly(libs.logback.classic)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }

    explicitApi()
}

publishToMaven()
