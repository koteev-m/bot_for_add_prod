package com.example.bot

import com.example.bot.dedup.UpdateDeduplicator
import com.example.bot.polling.PollingMain
import com.example.bot.telegram.TelegramClient
import com.example.bot.telegram.telegramSetupRoutes
import com.example.bot.webhook.WebhookReply
import com.example.bot.webhook.webhookRoute
import com.example.bot.webhook.UnauthorizedWebhook
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application")

private val allowedUpdates = listOf(
    "message",
    "edited_message",
    "callback_query",
    "contact",
    "pre_checkout_query",
    "successful_payment",
)

/** Ktor application module. */
fun Application.module() {
    install(ContentNegotiation) { json() }

    install(StatusPages) {
        exception<UnauthorizedWebhook> { call, _ ->
            call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Bad webhook secret"))
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled", cause)
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to "internal"))
        }
    }

    val token = env("BOT_TOKEN")
    val secret = env("WEBHOOK_SECRET_TOKEN")
    val baseUrl = env("WEBHOOK_BASE_URL")
    val maxConn = System.getenv("MAX_WEBHOOK_CONNECTIONS")?.toInt() ?: 40
    val localApi = System.getenv("LOCAL_BOT_API_URL")

    val client = TelegramClient(token, localApi)
    val dedup = UpdateDeduplicator()

    routing {
        webhookRoute(secret, dedup, handler = ::handleUpdate, client = client)
        telegramSetupRoutes(client, baseUrl, secret, maxConn, allowedUpdates)
    }
}

private suspend fun handleUpdate(update: com.example.bot.webhook.UpdateDto): WebhookReply? {
    update.callbackQuery?.let {
        return WebhookReply.Inline(mapOf("method" to "answerCallbackQuery", "callback_query_id" to it.id))
    }
    update.message?.let { msg ->
        if (msg.text == "/start") {
            return WebhookReply.Inline(
                mapOf("method" to "sendMessage", "chat_id" to msg.chat.id, "text" to "Hello")
            )
        }
    }
    return null
}

private fun env(name: String): String = System.getenv(name) ?: error("Missing $name")

/** Chooses between webhook and polling modes based on `RUN_MODE` variable. */
fun main(args: Array<String>) {
    when (System.getenv("RUN_MODE")?.lowercase()) {
        "polling" -> PollingMain.main(args)
        else -> io.ktor.server.netty.EngineMain.main(args)
    }
}

