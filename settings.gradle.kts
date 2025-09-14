rootProject.name = "bot_for_add_prod"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    "app-bot",
    "core-domain",
    "core-data",
    "core-telemetry",
    "core-security",
    "core-testing",
    "tools:perf"
)
