package com.example.bot

import com.example.bot.metrics.AppMetricsBinder
import com.example.bot.plugins.installAppConfig
import com.example.bot.plugins.installMetrics
import com.example.bot.plugins.installMigrationsAndDatabase
import com.example.bot.plugins.installRateLimitPluginDefaults
import com.example.bot.plugins.installRequestLogging
import com.example.bot.plugins.meterRegistry
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

private const val DEMO_STATE_KEY = "v1"
private const val DEMO_CLUB_ID = 1L
private const val DEMO_START_UTC = "2025-12-31T22:00:00Z"
private const val DEMO_TABLE_ID_1 = 101L
private const val DEMO_TABLE_ID_2 = 102L
private const val DEMO_TABLE_ID_3 = 103L
private val DEMO_TABLE_IDS = listOf(DEMO_TABLE_ID_1, DEMO_TABLE_ID_2, DEMO_TABLE_ID_3)
private const val DEMO_FALLBACK_TOKEN = "000000:DEV"

@Suppress("unused")
fun Application.module() {
    // demo constants (чтобы не было «магических» чисел)
    val demoStateKey = DEMO_STATE_KEY
    val demoClubId = DEMO_CLUB_ID
    val demoStartUtc = DEMO_START_UTC
    val demoTableIds = DEMO_TABLE_IDS

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
    AppMetricsBinder.bindAll(meterRegistry())
    // 5) Routes
    val renderer = DefaultHallRenderer()
    routing {
        healthRoute()
        readinessRoute()
        hallImageRoute(renderer) { _, _ -> demoStateKey }
        // … остальные маршруты приложения
    }

    // Telegram bot demo integration
    val telegramToken = System.getenv("TELEGRAM_BOT_TOKEN") ?: DEMO_FALLBACK_TOKEN
    val bot = TelegramBot(telegramToken)
    val ottService = CallbackTokenService()
    val callbackHandler = CallbackQueryHandler(bot, ottService)
    bot.setUpdatesListener(
        object : UpdatesListener {
            override fun process(updates: MutableList<Update>?): Int {
                if (updates == null) return UpdatesListener.CONFIRMED_UPDATES_ALL
                for (u in updates) {
                    if (u.callbackQuery() != null) {
                        callbackHandler.handle(u)
                    } else if (u.message() != null && u.message().text() == "/demo") {
                        val chatId = u.message().chat().id()
                        val kb =
                            KeyboardFactory.tableKeyboard(
                                service = ottService,
                                items =
                                listOf(
                                    "Стол 101" to BookTableAction(demoClubId, demoStartUtc, demoTableIds[0]),
                                    "Стол 102" to BookTableAction(demoClubId, demoStartUtc, demoTableIds[1]),
                                    "Стол 103" to BookTableAction(demoClubId, demoStartUtc, demoTableIds[2]),
                                ),
                            )
                        bot.execute(SendMessage(chatId, "Выберите стол:").replyMarkup(kb))
                    }
                }
                return UpdatesListener.CONFIRMED_UPDATES_ALL
            }
        },
    )
}
