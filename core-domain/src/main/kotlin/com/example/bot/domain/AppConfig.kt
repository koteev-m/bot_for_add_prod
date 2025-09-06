package com.example.bot.domain

data class AppConfig(
    val botToken: String,
    val webhookSecretToken: String,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val ownerTelegramId: Long,
)
