package com.example.bot

import com.example.bot.availability.AvailabilityService
import com.example.bot.booking.BookingService
import com.example.bot.config.BotLimits
import com.example.bot.data.club.GuestListCsvParser
import com.example.bot.data.repo.ClubDto
import com.example.bot.data.repo.ClubRepository
import com.example.bot.data.repo.ExposedClubRepository
import com.example.bot.di.availabilityModule
import com.example.bot.di.bookingModule
import com.example.bot.di.musicModule
import com.example.bot.di.securityModule
import com.example.bot.di.webAppModule
import com.example.bot.guestlists.GuestListRepository
import com.example.bot.metrics.AppMetricsBinder
import com.example.bot.music.MusicService
import com.example.bot.plugins.appConfig
import com.example.bot.plugins.configureSecurity
import com.example.bot.plugins.installAppConfig
import com.example.bot.plugins.installMetrics
import com.example.bot.plugins.installMigrationsAndDatabase
import com.example.bot.plugins.installRateLimitPluginDefaults
import com.example.bot.plugins.installRequestLogging
import com.example.bot.plugins.meterRegistry
import com.example.bot.render.DefaultHallRenderer
import com.example.bot.routes.availabilityApiRoutes
import com.example.bot.routes.availabilityRoutes
import com.example.bot.routes.bookingFinalizeRoutes
import com.example.bot.routes.checkinCompatRoutes
import com.example.bot.routes.checkinRoutes
import com.example.bot.routes.clubsPublicRoutes
import com.example.bot.routes.guestFlowRoutes
import com.example.bot.routes.guestListRoutes
import com.example.bot.routes.hallImageRoute
import com.example.bot.routes.healthRoute
import com.example.bot.routes.musicRoutes
import com.example.bot.routes.readinessRoute
import com.example.bot.routes.securedRoutes
import com.example.bot.routes.webAppRoutes
import com.example.bot.server.installServerTuning
import com.example.bot.telegram.GuestFlowHandler
import com.example.bot.telegram.Keyboards
import com.example.bot.telegram.MenuCallbacksHandler
import com.example.bot.telegram.ott.BookTableAction
import com.example.bot.telegram.ott.CallbackQueryHandler
import com.example.bot.telegram.ott.CallbackTokenService
import com.example.bot.telegram.ott.KeyboardFactory
import com.example.bot.telegram.ui.ChatUiSessionStore
import com.example.bot.telegram.ui.InMemoryChatUiSessionStore
import com.example.bot.text.BotTexts
import com.example.bot.webapp.InitDataAuthConfig
import com.example.bot.workers.OutboxWorker
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.WebAppInfo
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMessage
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import jdk.internal.vm.ScopedValueContainer.call
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory

/** Значения по умолчанию для запуска сервера (выносим «магические» литералы). */
private const val DEFAULT_HTTP_PORT: Int = 8080
private const val DEFAULT_BIND_HOST: String = "0.0.0.0"

/**
 * Точка входа приложения — для Docker/`installDist`.
 * Стартует Ktor Netty на 0.0.0.0 и порту из ENV PORT (по умолчанию 8080),
 * вызывая модуль [Application.module].
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_HTTP_PORT
    embeddedServer(
        factory = Netty,
        port = port,
        host = DEFAULT_BIND_HOST,
        module = Application::module,
    ).start(wait = true)
}

@Suppress("unused", "LoopWithTooManyJumpStatements", "MaxLineLength")
fun Application.module() {
    // demo constants
    val demoStateKey = BotLimits.Demo.STATE_KEY
    val demoClubId = BotLimits.Demo.CLUB_ID
    val demoStartUtc = BotLimits.Demo.START_UTC
    val demoTableIds = BotLimits.Demo.TABLE_IDS

    // 0) Тюнинг сервера и наблюдаемость
    installServerTuning()
    installRateLimitPluginDefaults()
    installMetrics()
    AppMetricsBinder.bindAll(meterRegistry())
    installMigrationsAndDatabase()
    installAppConfig()
    installRequestLogging()
    install(ContentNegotiation) { json() }

    val telegramToken = System.getenv("TELEGRAM_BOT_TOKEN") ?: BotLimits.Demo.FALLBACK_TOKEN
    val initDataAuthConfig: InitDataAuthConfig.() -> Unit = { botTokenProvider = { telegramToken } }

    // DI модуль Telegram/бота (pengrad)
    val telegramModule =
        module {
            single { BotTexts() }
            single { Keyboards(get()) }
            single { TelegramBot(telegramToken) }
            single { CallbackTokenService() }
            single<ClubRepository> { ExposedClubRepository(get()) }
            single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
            single<ChatUiSessionStore> { InMemoryChatUiSessionStore() }
            single {
                val bot: TelegramBot = get()
                GuestFlowHandler(
                    send = { request ->
                        @Suppress("UNCHECKED_CAST")
                        bot.execute((request as? BaseRequest<*, *>) ?: error("Unsupported request type: ${request::class}"))
                    },
                    texts = get(),
                    keyboards = get(),
                    promoService = get(),
                )
            }
            single { MenuCallbacksHandler(get(), get(), get(), get(), get(), get(), get(), get()) }
            single { CallbackQueryHandler(get(), get(), get()) }
        }

    install(Koin) {
        slf4jLogger()
        modules(
            bookingModule,
            securityModule,
            webAppModule,
            availabilityModule,
            telegramModule,
            musicModule,
        )
    }

    configureSecurity()

    val outboxWorker by inject<OutboxWorker>()
    val bot by inject<TelegramBot>()
    val ottService by inject<CallbackTokenService>()
    val callbackHandler by inject<CallbackQueryHandler>()
    val bookingService by inject<BookingService>()
    val guestListRepository: GuestListRepository = get()
    val availability: AvailabilityService = get()
    val guestFlowHandler by inject<GuestFlowHandler>()
    val clubRepository by inject<ClubRepository>()
    val startMenuLogger = LoggerFactory.getLogger("TelegramStartMenu")
    val webAppBaseUrl = resolveWebAppBaseUrl()
    val musicService: MusicService = get()

    var workerJob: Job? = null
    // Подписка на события жизненного цикла
    monitor.subscribe(ApplicationStarted) {
        workerJob = launch { outboxWorker.run() }
    }
    monitor.subscribe(ApplicationStopped) {
        workerJob?.cancel()
    }

    // 5) Routes
    val renderer = DefaultHallRenderer()
    routing {
        // /health, /ready — публичные
        healthRoute()
        readinessRoute()

        get("/ping") {
            call.respondText("OK", ContentType.Text.Plain)
        }

        // Рендер схемы зала с кеш/ETag (публично)
        hallImageRoute(renderer) { _, _ -> demoStateKey }

        // Публичный guest-flow (клуб → список ночей)
        guestFlowRoutes(availability)

        // Публичный API доступности: ночи и свободные столы
        availabilityRoutes(availability)

        this@module.musicRoutes(musicService)
    }

    clubsPublicRoutes(clubRepository)

    // Новые публичные API-доступы для Mini App
    availabilityApiRoutes(availability)

    // Mini App статика, CSP, gzip
    webAppRoutes()

    // Чек-ин верификатор (WebApp → POST /api/clubs/{clubId}/checkin/scan)
    checkinRoutes(repository = guestListRepository, initDataAuth = initDataAuthConfig)
    // алиасы для Mini App коротких путей
    checkinCompatRoutes(repository = guestListRepository)

    bookingFinalizeRoutes(bookingService) { telegramToken }

    // RBAC-защищённые брони: /api/clubs/{clubId}/bookings/hold|confirm
    securedRoutes(bookingService, initDataAuth = initDataAuthConfig)

    // Гостевые списки: поиск/экспорт/импорт (RBAC/club scope внутри)
    guestListRoutes(
        repository = guestListRepository,
        parser = GuestListCsvParser(),
        initDataAuth = initDataAuthConfig,
    )

    // Telegram bot (polling demo)
    bot.setUpdatesListener(
        object : UpdatesListener {
            override fun process(updates: MutableList<Update>?): Int {
                if (updates == null) return UpdatesListener.CONFIRMED_UPDATES_ALL

                for (u in updates) {
                    // 1) Callback-кнопки
                    u.callbackQuery()?.let {
                        callbackHandler.handle(u)
                        continue
                    }

                    // 2) Обычные сообщения
                    val msg = u.message() ?: continue
                    val text = msg.text()?.trim() ?: ""
                    val chatId = msg.chat().id()

                    when {
                        // Обрабатываем /start (и "старт"/"start" на всякий)
                        text.startsWith("/start", ignoreCase = true) ||
                            text.equals("start", ignoreCase = true) ||
                            text.equals("старт", ignoreCase = true) -> {
                            runBlocking {
                                if (text.startsWith("/start", ignoreCase = true)) {
                                    guestFlowHandler.handle(u)
                                }
                                val clubs = clubRepository.listClubs(limit = START_CLUBS_LIMIT).take(START_CLUBS_LIMIT)
                                if (clubs.size == START_CLUBS_LIMIT) {
                                    val keyboard = buildStartKeyboard(webAppBaseUrl, clubs)
                                    val message = SendMessage(chatId, "Выберите клуб:").replyMarkup(keyboard)
                                    bot.execute(message)
                                    startMenuLogger.info(
                                        "telegram.start_menu.sent chatId={} clubs={}",
                                        chatId,
                                        clubs.map { it.id },
                                    )
                                } else {
                                    startMenuLogger.warn(
                                        "telegram.start_menu.skipped chatId={} reason={} available={}",
                                        chatId,
                                        "insufficient_clubs",
                                        clubs.size,
                                    )
                                }
                            }
                        }

                        // Твой уже существующий демо-обработчик
                        text.equals("/demo", ignoreCase = true) -> {
                            val items = demoTableIds.map { tableId ->
                                "Стол $tableId" to BookTableAction(demoClubId, demoStartUtc, tableId)
                            }
                            val kb = KeyboardFactory.tableKeyboard(service = ottService, items = items)
                            bot.execute(SendMessage(chatId, "Выберите стол:").replyMarkup(kb))
                        }
                    }
                }

                return UpdatesListener.CONFIRMED_UPDATES_ALL
            }
        },
    )
}

private const val START_CLUBS_LIMIT = 4

private fun Application.resolveWebAppBaseUrl(): String {
    val envBase = System.getenv("WEBAPP_BASE_URL")?.takeIf { it.isNotBlank() }
    val configBase =
        listOfNotNull(
            runCatching { appConfig.localApi.baseUrl }.getOrNull(),
            runCatching { appConfig.webhook.baseUrl }.getOrNull(),
        ).firstOrNull { it.isNotBlank() }
    val base = envBase ?: configBase ?: "http://localhost:8080"
    return base.removeSuffix("/")
}

@Suppress("SpreadOperator")
private fun buildStartKeyboard(baseUrl: String, clubs: List<ClubDto>): InlineKeyboardMarkup {
    require(clubs.size >= START_CLUBS_LIMIT) { "Expected at least $START_CLUBS_LIMIT clubs" }
    val normalizedBase = baseUrl.removeSuffix("/")
    val buttons =
        clubs.take(START_CLUBS_LIMIT).map { club ->
            InlineKeyboardButton(club.name).webApp(WebAppInfo("$normalizedBase/app?clubId=${club.id}"))
        }
    val rows = buttons.chunked(2).map { it.toTypedArray() }
    return InlineKeyboardMarkup(*rows.toTypedArray())
}
