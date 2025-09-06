package com.example.bot.app

import com.example.bot.domain.AppConfig

object ConfigLoader {
    class ConfigurationException(message: String) : RuntimeException(message)

    private val required =
        listOf(
            "BOT_TOKEN",
            "WEBHOOK_SECRET_TOKEN",
            "DATABASE_URL",
            "DATABASE_USER",
            "DATABASE_PASSWORD",
            "OWNER_TELEGRAM_ID",
        )

    private fun value(name: String): String? = System.getenv(name) ?: System.getProperty(name)

    fun load(): AppConfig {
        val missing = required.filter { value(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw ConfigurationException("Missing required environment variables: ${missing.joinToString(", ")}")
        }

        val ownerId =
            value("OWNER_TELEGRAM_ID")!!.toLongOrNull()
                ?: throw ConfigurationException("Environment variable OWNER_TELEGRAM_ID must be a valid long")

        return AppConfig(
            botToken = value("BOT_TOKEN")!!,
            webhookSecretToken = value("WEBHOOK_SECRET_TOKEN")!!,
            databaseUrl = value("DATABASE_URL")!!,
            databaseUser = value("DATABASE_USER")!!,
            databasePassword = value("DATABASE_PASSWORD")!!,
            ownerTelegramId = ownerId,
        )
    }
}
