plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("org.flywaydb.flyway") version "10.16.0"
}

flyway {
    url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/postgres"
    user = System.getenv("DB_USER") ?: "postgres"
    password = System.getenv("DB_PASSWORD") ?: "postgres"
    locations = arrayOf("filesystem:${'$'}{projectDir}/src/main/resources/db/migration")
}

dependencies {
    val exposedVersion = libs.versions.exposed.get()
    implementation(projects.coreDomain)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation(libs.hikari)
    implementation(libs.flyway)
    implementation(libs.postgres)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.h2)
    testImplementation(projects.coreTesting)
}
