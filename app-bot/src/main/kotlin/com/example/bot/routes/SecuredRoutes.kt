package com.example.bot.routes

import com.example.bot.security.rbac.RoleCode
import com.example.bot.security.rbac.anyOf
import com.example.bot.security.rbac.clubScopedAuthorize
import com.example.bot.security.rbac.globalAuthorize
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Example secured HTTP routes using RBAC DSL.
 */
fun Application.securedRoutes() {
    routing {
        globalAuthorize(anyOf(RoleCode.OWNER, RoleCode.GLOBAL_ADMIN, RoleCode.HEAD_MANAGER)) {
            get("/api/admin/overview") {
                call.respondText("overview")
            }
        }
        route("/api/clubs/{clubId}") {
            clubScopedAuthorize(anyOf(RoleCode.CLUB_ADMIN, RoleCode.MANAGER, RoleCode.ENTRY_MANAGER)) {
                get("/bookings") { call.respondText("bookings") }
                post("/checkin/scan") { call.respondText("scan") }
            }
            clubScopedAuthorize(anyOf(RoleCode.PROMOTER, RoleCode.CLUB_ADMIN, RoleCode.MANAGER, RoleCode.GUEST)) {
                post("/tables/{tableId}/booking") { call.respondText("booked") }
            }
        }
    }
}
