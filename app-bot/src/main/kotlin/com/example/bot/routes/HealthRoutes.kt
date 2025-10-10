package com.example.bot.routes

import com.example.bot.observability.DefaultHealthService
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.healthRoutes(service: DefaultHealthService) {
    routing {
        get("/health") { call.respond(service.health()) }
        get("/ready")  { call.respond(service.readiness()) }
    }
}
