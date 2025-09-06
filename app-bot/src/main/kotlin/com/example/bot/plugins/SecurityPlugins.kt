package com.example.bot.plugins

import com.example.bot.security.auth.AuthProviders.telegramWebAppAuth
import com.example.bot.security.auth.AuthProviders.telegramWebhookAuth
import com.example.bot.security.rbac.AuthorizationException
import com.example.bot.security.rbac.RoleCache
import com.example.bot.security.rbac.RoleRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond

/**
 * Installs authentication and basic error handling.
 */
fun Application.configureSecurity() {
    val repository = object : RoleRepository {
        override suspend fun findRoles(userId: Long) = emptySet<com.example.bot.security.rbac.RoleAssignment>()
    }
    val cache = RoleCache(repository)
    install(Authentication) {
        telegramWebhookAuth("secret") { cache.rolesFor(it) }
        telegramWebAppAuth("token") { cache.rolesFor(it) }
    }
    install(StatusPages) {
        exception<AuthorizationException> { call, cause ->
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to cause.message))
        }
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "error")))
        }
    }
}
