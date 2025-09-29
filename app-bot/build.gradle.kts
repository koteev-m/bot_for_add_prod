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
    // Ktor (через алиасы каталога версий)
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

    // Модули проекта
    implementation(projects.coreDomain)
    implementation(projects.coreData)
    implementation(projects.coreTelemetry)
    implementation(projects.coreSecurity)

    // DB (если действительно нужны в app-боте; чаще это в core-data)
    implementation(libs.exposed.jdbc)

    // Observability / logging
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.prometheus)
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.logback)
    implementation(libs.logstash.encoder)

    // Telegram
    implementation(libs.pengrad.telegram)

    // DI
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    // Tests
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.h2)
    testImplementation(libs.postgres)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.pg)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(projects.coreTesting)
}

application {
    // EngineMain + application.conf (modules = [ com.example.bot.ApplicationKt.module ])
    mainClass.set("io.ktor.server.netty.EngineMain")

    // listOf — каждый аргумент на своей строке + запятая в конце (требование ktlint)
    applicationDefaultJvmArgs =
        listOf(
            "-Dfile.encoding=UTF-8",
            "-XX:+ExitOnOutOfMemoryError",
        )
}

/**
 * Утилита для запуска миграций из рантайма приложения.
 * Стиль registering(JavaExec::class) не триггерит ktlint rule 'multiline-expression-wrapping'.
 */
val runMigrations by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run Flyway migrations using app runtime"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.bot.tools.MigrateMainKt")
}
