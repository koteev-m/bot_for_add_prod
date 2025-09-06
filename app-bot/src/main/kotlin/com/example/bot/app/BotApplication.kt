package com.example.bot.app

import com.example.bot.telemetry.Telemetry.configureMonitoring
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class TelegramMessage(val text: String? = null)

@Serializable
data class TelegramUpdate(val updateId: Long, val message: TelegramMessage?)

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
    configureMonitoring()
    routing {
        post("/webhook") {
            val update = call.receive<TelegramUpdate>()
            val reply = update.message?.text ?: ""
            call.respondText(reply)
        }
    }
}

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}
