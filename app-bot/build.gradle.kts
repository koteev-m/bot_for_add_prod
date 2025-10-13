import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources
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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // показывать println() и System.out/System.err в консоли Gradle
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped", "standardOut", "standardError")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    // Для Kotest: явно просим не глушить stdout
    systemProperty("kotest.framework.dump.test.stdout", "true")

    // TELEGRAM_BOT_TOKEN нужен для плагина InitDataAuth в тестах (MusicRoutesTest и др.)
    environment("TELEGRAM_BOT_TOKEN", "111111:TEST_BOT_TOKEN")
    environment("NOTIFICATIONS_ENABLED", "false")
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
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)

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
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
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

// =========================
// Mini App assets → resources
// =========================

val miniAppDistDir = rootProject.layout.projectDirectory.dir("miniapp/dist")

tasks.register<Copy>("copyMiniAppDist") {
    description = "Copy Mini App compiled assets into resources so Ktor can serve them from the JAR."
    group = "build"

    // провайдер-директория — безопасно для configuration cache
    from(miniAppDistDir)
    include("**/*")

    into(layout.buildDirectory.dir("generated/miniapp/webapp/app"))

    // ⚠️ больше никакого onlyIf { ... } — Copy просто ничего не скопирует, если папка пуста/не существует
    // при желании можно явно пометить вход каталога как опциональный:
    inputs.dir(miniAppDistDir).optional()
}

tasks.named<ProcessResources>("processResources") {
    dependsOn("copyMiniAppDist")
    from(layout.buildDirectory.dir("generated/miniapp")) {
        into("")
    }
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

tasks.test {
    if (project.hasProperty("runIT")) {
        systemProperty("junit.jupiter.tags.include", "it")
    } else {
        systemProperty("junit.jupiter.tags.exclude", "it")
    }
    systemProperty("FLYWAY_LOCATIONS", "classpath:db/migration,classpath:db/migration/postgresql")
}
