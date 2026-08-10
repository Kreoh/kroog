val isBeta by extra(true)

plugins {
    id("ai.kotlin.jvm")
}

dependencies {
    implementation(project(":skills"))
}
