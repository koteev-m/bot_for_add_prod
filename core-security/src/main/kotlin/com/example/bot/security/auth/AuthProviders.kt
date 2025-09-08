package com.example.bot.security.auth

import com.example.bot.security.rbac.RoleAssignment
import io.ktor.server.auth.AuthenticationConfig

/**
 * Stub authentication providers. Real implementation should integrate with Telegram.
 */
object AuthProviders {
    fun AuthenticationConfig.telegramWebhookAuth(
        secretToken: String,
        rolesProvider: suspend (Long) -> Set<RoleAssignment>,
    ) {
        // no-op stub
    }

    fun AuthenticationConfig.telegramWebAppAuth(
        botToken: String,
        rolesProvider: suspend (Long) -> Set<RoleAssignment>,
    ) {
        // no-op stub
    }
}

