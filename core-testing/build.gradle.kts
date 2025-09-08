plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(projects.coreDomain)
    implementation(projects.coreData)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(projects.appBot)
    testImplementation(libs.pengrad.telegram)
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.micrometer.core)
}
