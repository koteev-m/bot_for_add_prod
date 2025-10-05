package com.example.bot.routes

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.utility.BotUtils
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

fun Application.telegramWebhookRoutes(
    _bot: TelegramBot,
    onUpdate: (Update) -> Unit,
) {
    val logger = LoggerFactory.getLogger("TelegramWebhookRoutes")

    routing {
        post("/telegram/webhook") {
            val body = call.receiveText()
            val update = runCatching { BotUtils.parseUpdate(body) }.getOrNull()
            if (update == null) {
                logger.warn("webhook: invalid update payload")
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            runCatching { onUpdate(update) }
                .onFailure { t -> logger.warn("webhook: handler failed: {}", t.toString()) }

            call.respond(HttpStatusCode.OK, "OK")
        }
    }
}
