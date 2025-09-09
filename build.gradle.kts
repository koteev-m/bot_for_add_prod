plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlintGradle)
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(from = rootProject.file("gradle/detekt.gradle.kts"))
        apply(from = rootProject.file("gradle/ktlint.gradle.kts"))
    }
}

tasks.register("staticCheck") {
    group = "verification"
    description = "Run detekt and ktlintCheck across all modules"
    dependsOn(
        subprojects.mapNotNull { it.tasks.findByName("detekt") },
        subprojects.mapNotNull { it.tasks.findByName("ktlintCheck") }
    )
}
