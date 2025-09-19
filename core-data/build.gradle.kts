import net.ltgt.gradle.flyway.FlywayExtension
import net.ltgt.gradle.flyway.tasks.FlywayTask
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("net.ltgt.flyway") version "0.2.0"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

val databaseUrlProvider = providers.environmentVariable("DATABASE_URL")
    .orElse(providers.gradleProperty("DATABASE_URL"))
val databaseUserProvider = providers.environmentVariable("DATABASE_USER")
    .orElse(providers.gradleProperty("DATABASE_USER"))
val databasePasswordProvider = providers.environmentVariable("DATABASE_PASSWORD")
    .orElse(providers.gradleProperty("DATABASE_PASSWORD"))

val dbUrl = databaseUrlProvider.orNull
val dbVendor = (providers.gradleProperty("dbVendor").orNull)
    ?: when {
        dbUrl?.startsWith("jdbc:h2", ignoreCase = true) == true -> "h2"
        dbUrl?.startsWith("jdbc:postgresql", ignoreCase = true) == true -> "postgresql"
        else -> "postgresql"
    }

val migrationLocationDirs =
    listOf(
        layout.projectDirectory.dir("src/main/resources/db/migration/common"),
        layout.projectDirectory.dir("src/main/resources/db/migration/$dbVendor"),
    )

flyway {
    databaseUrlProvider.orNull?.let { url.set(it) }
    databaseUserProvider.orNull?.let { user.set(it) }
    databasePasswordProvider.orNull?.let { password.set(it) }
    val migrationLocations =
        migrationLocationDirs.joinToString(",") { "filesystem:${it.asFile.absolutePath}" }
    configuration.putAll(mapOf("flyway.locations" to migrationLocations))
}

val flywayExtension = extensions.getByType<FlywayExtension>()
flywayExtension.migrationLocations.setFrom(migrationLocationDirs.map { it.asFile })

tasks.withType<FlywayTask>().configureEach {
    doFirst("validateFlywayDatabaseConfiguration") {
        val missing = buildList {
            if (flywayExtension.url.orNull.isNullOrBlank()) add("DATABASE_URL")
            if (flywayExtension.user.orNull.isNullOrBlank()) add("DATABASE_USER")
            if (flywayExtension.password.orNull == null) add("DATABASE_PASSWORD")
        }

        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing required environment variables for Flyway migrations: " +
                    missing.joinToString(", ") +
                    ". Provide them via environment variables or Gradle properties."
            )
        }
    }
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
    flyway("org.flywaydb:flyway-core:11.13.1")
    flyway("org.flywaydb:flyway-database-postgresql:11.13.1")
    flyway("org.postgresql:postgresql:42.7.8")
    flyway("com.h2database:h2:2.3.232")
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}
