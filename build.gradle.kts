import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm") version "2.2.10" apply false
    kotlin("plugin.serialization") version "2.2.10" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.6" apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(files(rootProject.file("detekt.yml")))
        baseline = rootProject.file("config/detekt/baseline.xml")
    }

    tasks.withType<Detekt>().configureEach {
        reports {
            html.required.set(true)
            sarif.required.set(true)
            xml.required.set(false)
            md.required.set(false)
            val reportsDir = project.layout.buildDirectory
            html.outputLocation.set(reportsDir.file("reports/detekt/detekt.html"))
            sarif.outputLocation.set(reportsDir.file("reports/detekt/detekt.sarif"))
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(from = rootProject.file("gradle/detekt-cli.gradle.kts"))
        apply(from = rootProject.file("gradle/ktlint-cli.gradle.kts"))
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

tasks.register("staticCheck") {
    group = "verification"
    description = "Run detekt CLI and ktlint CLI across all Kotlin modules"
    dependsOn(
        subprojects.flatMap { sp ->
            listOfNotNull(
                sp.tasks.findByName("detektCli"),
                sp.tasks.findByName("ktlintCheckCli")
            )
        }
    )
}

tasks.register("formatAll") {
    group = "formatting"
    description = "Run ktlint format for all Kotlin modules"
    dependsOn(
        subprojects.mapNotNull { it.tasks.findByName("ktlintFormatCli") }
    )
}

tasks.register("flywayMigrate") {
    group = "database"
    description = "Run Flyway migrations via :core-data module"
    dependsOn(":core-data:flywayMigrate")
}
