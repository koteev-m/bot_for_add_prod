package com.example.bot.security.auth

import com.example.bot.security.rbac.RoleAssignment
import io.ktor.server.auth.Principal

/**
 * Principal representing an authenticated Telegram user.
 *
 * @property telegramUserId Telegram numeric identifier
 * @property username Optional Telegram username
 * @property roles Assigned roles for this user
 */
data class TelegramPrincipal(val telegramUserId: Long, val username: String?, val roles: Set<RoleAssignment>) :
    Principal
