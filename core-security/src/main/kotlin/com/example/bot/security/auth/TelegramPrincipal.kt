package com.example.bot.security.auth

import io.ktor.server.auth.Principal

/**
 * Principal representing an authenticated Telegram user.
 *
 * @property userId Telegram numeric identifier
 * @property username Optional Telegram username
 */
data class TelegramPrincipal(val userId: Long, val username: String?) : Principal
