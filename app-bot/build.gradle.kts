import org.gradle.api.tasks.JavaExec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
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

dependencies {
    implementation(projects.coreDomain)
    implementation(projects.coreData)
    implementation(projects.coreTelemetry)
    implementation(projects.coreSecurity)
    implementation(libs.exposed.jdbc)
    implementation(libs.flyway)
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.prometheus)
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.logback)
    implementation(libs.logstash.encoder)
    implementation(libs.pengrad.telegram)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.h2)
    testImplementation(libs.postgres)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}

application {
    mainClass.set("com.example.bot.ApplicationKt")
}

tasks.register<JavaExec>("runMigrations") {
    group = "application"
    description = "Run Flyway migrations using app runtime"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.bot.tools.MigrateMainKt")
}
