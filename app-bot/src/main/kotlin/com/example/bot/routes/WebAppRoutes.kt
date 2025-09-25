package com.example.bot.routes

import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.routing

fun Application.webAppRoutes() {
    routing {
        staticResources("/webapp/entry", "webapp/entry")
    }
}
