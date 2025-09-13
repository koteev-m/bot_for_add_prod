package com.example.bot.data.db

import java.util.Locale

data class DbConfig(val url: String, val user: String, val password: String) {
    companion object {
        fun fromEnv(): DbConfig {
            return DbConfig(
                url = envRequired("DATABASE_URL"),
                user = envRequired("DATABASE_USER"),
                password = envRequired("DATABASE_PASSWORD"),
            )
        }
    }
}

data class FlywayConfig(
    val enabled: Boolean = true,
    val locations: List<String> = listOf("classpath:db/migration"),
    val schemas: List<String> = emptyList(),
    val baselineOnMigrate: Boolean = true,
    val validateOnly: Boolean = false,
) {
    companion object {
        fun fromEnv(): FlywayConfig {
            return FlywayConfig(
                enabled = env("FLYWAY_ENABLED")?.toBooleanStrictOrNull() ?: true,
                locations =
                env("FLYWAY_LOCATIONS")
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: listOf("classpath:db/migration"),
                schemas =
                env("FLYWAY_SCHEMAS")
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList(),
                baselineOnMigrate = env("FLYWAY_BASELINE_ON_MIGRATE")?.toBooleanStrictOrNull() ?: true,
                validateOnly = env("FLYWAY_VALIDATE_ONLY")?.toBooleanStrictOrNull() ?: false,
            )
        }
    }
}

/** utils */
private fun env(name: String): String? = System.getenv(name)

private fun envRequired(name: String): String = env(name) ?: error("ENV $name is required")

private fun String.toBooleanStrictOrNull(): Boolean? {
    return when (this.lowercase(Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
