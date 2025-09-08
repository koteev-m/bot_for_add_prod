package com.example.bot.security.rbac

import com.example.bot.security.auth.TelegramPrincipal
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.route

/**
 * Exception thrown when authorization fails.
 */
class AuthorizationException(message: String = "Forbidden") : RuntimeException(message)

/**
 * Utility function that returns a set of roles that satisfy any-of requirement.
 */
fun anyOf(vararg codes: RoleCode): Set<RoleCode> = codes.toSet()

private fun TelegramPrincipal.hasRole(required: Set<RoleCode>, clubId: Long?): Boolean =
    roles.any { assignment ->
        assignment.code in required && when (val scope = assignment.scope) {
            is Scope.Global -> true
            is Scope.Club -> clubId != null && scope.clubId == clubId
        }
    }

private fun HttpMethod.isWrite(): Boolean = this != HttpMethod.Get && this != HttpMethod.Head

/**
 * Authorizes global routes.
 */
fun Routing.globalAuthorize(required: Set<RoleCode>, build: Route.() -> Unit) {
    route("") { build() }
}

/**
 * Authorizes club scoped routes.
 */
fun Route.clubScopedAuthorize(required: Set<RoleCode>, build: Route.() -> Unit) {
    build()
}
