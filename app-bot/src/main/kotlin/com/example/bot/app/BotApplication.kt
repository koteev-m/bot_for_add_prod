package com.example.bot.app

import com.example.bot.telemetry.Telemetry.configureMonitoring
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
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
    ConfigLoader.load()

    install(ContentNegotiation) {
        json()
    }

    val corsHosts = environment.config.propertyOrNull("ktor.cors.hosts")?.getList() ?: emptyList()
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.ContentType)
        if (corsHosts.contains("*")) {
            anyHost()
        } else {
            corsHosts.forEach { host -> allowHost(host, schemes = listOf("http", "https")) }
        }
    }

    val routes = environment.config.config("ktor.routing")
    val webhookPath = routes.property("webhook").getString()
    val metricsPath = routes.property("metrics").getString()
    val healthPath = routes.property("health").getString()

    configureMonitoring(metricsPath, healthPath)

    routing {
        post(webhookPath) {
            val update = call.receive<TelegramUpdate>()
            val reply = update.message?.text ?: ""
            call.respondText(reply)
        }
    }
}

fun main(args: Array<String>) = EngineMain.main(args)
