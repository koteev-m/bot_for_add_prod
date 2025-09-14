package com.example.bot

import com.example.bot.plugins.installAppConfig
import com.example.bot.plugins.installMetrics
import com.example.bot.plugins.installMigrationsAndDatabase
import com.example.bot.plugins.installRateLimitPluginDefaults
import com.example.bot.plugins.installRequestLogging
import com.example.bot.render.DefaultHallRenderer
import com.example.bot.routes.hallImageRoute
import com.example.bot.routes.healthRoute
import com.example.bot.routes.readinessRoute
import com.example.bot.server.installServerTuning
import com.example.bot.telegram.ott.BookTableAction
import com.example.bot.telegram.ott.CallbackQueryHandler
import com.example.bot.telegram.ott.CallbackTokenService
import com.example.bot.telegram.ott.KeyboardFactory
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.SendMessage
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

@Suppress("unused")
fun Application.module() {
    // 0) Тюнинг сервера (лимиты размера запроса и пр.)
    installServerTuning()
    // 1) Rate limiting: IP + per-subject (429 при переполнении)
    installRateLimitPluginDefaults()
    // 2) Migrations + DB (18.3)
    installMigrationsAndDatabase()
    // 3) AppConfig (этот шаг должен идти раньше бизнес-логики)
    installAppConfig()
    // 4) Observability (18.4)
    installRequestLogging()
    installMetrics()
    // 5) Routes
    val renderer = DefaultHallRenderer()
    routing {
        healthRoute()
        readinessRoute()
        hallImageRoute(renderer) { _, _ -> "v1" }
        // … остальные маршруты приложения
    }

    // Telegram bot demo integration
    val telegramToken = System.getenv("TELEGRAM_BOT_TOKEN") ?: "000000:DEV"
    val bot = TelegramBot(telegramToken)
    val ottService = CallbackTokenService()
    val callbackHandler = CallbackQueryHandler(bot, ottService)
    bot.setUpdatesListener(object : UpdatesListener {
        override fun process(updates: MutableList<Update>?): Int {
            if (updates == null) return UpdatesListener.CONFIRMED_UPDATES_ALL
            for (u in updates) {
                if (u.callbackQuery() != null) {
                    callbackHandler.handle(u)
                } else if (u.message() != null && u.message().text() == "/demo") {
                    val chatId = u.message().chat().id()
                    val kb = KeyboardFactory.tableKeyboard(
                        service = ottService,
                        items = listOf(
                            "Стол 101" to BookTableAction(1L, "2025-12-31T22:00:00Z", 101L),
                            "Стол 102" to BookTableAction(1L, "2025-12-31T22:00:00Z", 102L),
                            "Стол 103" to BookTableAction(1L, "2025-12-31T22:00:00Z", 103L)
                        )
                    )
                    bot.execute(SendMessage(chatId, "Выберите стол:").replyMarkup(kb))
                }
            }
            return UpdatesListener.CONFIRMED_UPDATES_ALL
        }
    })
}
