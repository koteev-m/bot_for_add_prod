plugins {
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(from = rootProject.file("gradle/detekt-cli.gradle.kts"))
        apply(from = rootProject.file("gradle/ktlint-cli.gradle.kts"))
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
