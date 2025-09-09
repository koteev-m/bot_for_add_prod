package com.example.bot

import com.example.bot.dedup.UpdateDeduplicator
import com.example.bot.miniapp.miniAppModule
import com.example.bot.observability.DefaultHealthService
import com.example.bot.observability.MetricsProvider
import com.example.bot.observability.TracingProvider
import com.example.bot.plugins.installLogging
import com.example.bot.plugins.installMetrics
import com.example.bot.plugins.installTracing
import com.example.bot.polling.PollingMain
import com.example.bot.routes.observabilityRoutes
import com.example.bot.telegram.TelegramClient
import com.example.bot.telegram.telegramSetupRoutes
import com.example.bot.telemetry.Telemetry
import com.example.bot.webhook.UnauthorizedWebhook
import com.example.bot.webhook.WebhookReply
import com.example.bot.webhook.webhookRoute
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application")

private val allowedUpdates =
    listOf(
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

    val metricsRegistry = MetricsProvider.prometheusRegistry()
    val metrics = MetricsProvider(metricsRegistry)
    Telemetry.registry = metricsRegistry
    metrics.registerBuildInfo(
        System.getenv("APP_VERSION") ?: "dev",
        System.getenv("APP_ENV") ?: "local",
        System.getenv("GIT_COMMIT") ?: "none",
    )

    installMetrics(metricsRegistry)
    installLogging()

    val tracing =
        if (System.getenv("TRACING_ENABLED") == "true") {
            val endpoint = System.getenv("OTLP_ENDPOINT") ?: "http://localhost:4318"
            TracingProvider.create(endpoint).tracer
        } else {
            null
        }
    tracing?.let { installTracing(it) }

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

    miniAppModule()
    val healthService = DefaultHealthService()

    routing {
        observabilityRoutes(metrics, healthService)
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
                mapOf("method" to "sendMessage", "chat_id" to msg.chat.id, "text" to "Hello"),
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
        else ->
            io.ktor.server.netty.EngineMain
                .main(args)
    }
}
