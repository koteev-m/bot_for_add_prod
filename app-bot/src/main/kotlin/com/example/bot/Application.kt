package com.example.bot

import com.example.bot.config.BotLimits
import com.example.bot.di.bookingModule
import com.example.bot.di.securityModule
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
import com.example.bot.telegram.MenuCallbacksHandler
import com.example.bot.telegram.ott.BookTableAction
import com.example.bot.telegram.ott.CallbackQueryHandler
import com.example.bot.telegram.ott.CallbackTokenService
import com.example.bot.telegram.ott.KeyboardFactory
import com.example.bot.workers.OutboxWorker
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.SendMessage
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

@Suppress("unused")
fun Application.module() {
    // demo constants (чтобы не было «магических» чисел)
    val demoStateKey = BotLimits.Demo.STATE_KEY
    val demoClubId = BotLimits.Demo.CLUB_ID
    val demoStartUtc = BotLimits.Demo.START_UTC
    val demoTableIds = BotLimits.Demo.TABLE_IDS

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

    val telegramToken = System.getenv("TELEGRAM_BOT_TOKEN") ?: BotLimits.Demo.FALLBACK_TOKEN
    val telegramModule =
        module {
            single { TelegramBot(telegramToken) }
            single { CallbackTokenService() }
            single { MenuCallbacksHandler(get()) }
            single { CallbackQueryHandler(get(), get(), get()) }
        }
    install(Koin) {
        slf4jLogger()
        modules(bookingModule, securityModule, telegramModule)
    }

    val outboxWorker by inject<OutboxWorker>()
    val bot by inject<TelegramBot>()
    val ottService by inject<CallbackTokenService>()
    val callbackHandler by inject<CallbackQueryHandler>()
    var workerJob: Job? = null
    environment.monitor.subscribe(ApplicationStarted) {
        workerJob = launch { outboxWorker.run() }
    }
    environment.monitor.subscribe(ApplicationStopped) {
        workerJob?.cancel()
    }
    // 5) Routes
    val renderer = DefaultHallRenderer()
    routing {
        healthRoute()
        readinessRoute()
        hallImageRoute(renderer) { _, _ -> demoStateKey }
        // … остальные маршруты приложения
    }

    // Telegram bot demo integration
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
                                    demoTableIds.map { tableId ->
                                        "Стол $tableId" to BookTableAction(demoClubId, demoStartUtc, tableId)
                                    },
                            )
                        bot.execute(SendMessage(chatId, "Выберите стол:").replyMarkup(kb))
                    }
                }
                return UpdatesListener.CONFIRMED_UPDATES_ALL
            }
        },
    )
}
