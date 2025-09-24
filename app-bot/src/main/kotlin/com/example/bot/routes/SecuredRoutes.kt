package com.example.bot.routes

import com.example.bot.booking.BookingService
import com.example.bot.data.security.Role
import com.example.bot.security.rbac.ClubScope
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.clubScoped
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
fun Application.securedRoutes(bookingService: BookingService) {
    routing {
        securedBookingRoutes(bookingService)
        authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER) {
            get("/api/admin/overview") {
                call.respondText("overview")
            }
        }
        route("/api/clubs/{clubId}") {
            authorize(Role.CLUB_ADMIN, Role.MANAGER, Role.ENTRY_MANAGER) {
                clubScoped(ClubScope.Own) {
                    get("/bookings") { call.respondText("bookings") }
                    post("/checkin/scan") { call.respondText("scan") }
                }
            }
            authorize(Role.PROMOTER, Role.CLUB_ADMIN, Role.MANAGER, Role.GUEST) {
                clubScoped(ClubScope.Own) {
                    post("/tables/{tableId}/booking") { call.respondText("booked") }
                }
            }
        }
    }
}
