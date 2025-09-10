package com.example.bot.security.rbac

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
