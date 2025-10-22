package com.example.bot

import com.example.bot.availability.AvailabilityService
import com.example.bot.booking.BookingService
import com.example.bot.config.AppConfig
import com.example.bot.config.BotLimits
import com.example.bot.config.BotRunMode
import com.example.bot.data.club.GuestListCsvParser
import com.example.bot.data.repo.ClubDto
import com.example.bot.data.repo.ClubRepository
import com.example.bot.data.repo.ExposedClubRepository
import com.example.bot.di.availabilityModule
import com.example.bot.di.bookingModule
import com.example.bot.di.healthModule
import com.example.bot.di.musicModule
import com.example.bot.di.notifyModule
import com.example.bot.di.securityModule
import com.example.bot.di.webAppModule
import com.example.bot.di.paymentsModule
import com.example.bot.di.refundWorkerModule
import com.example.bot.di.outboxAdminModule
import com.example.bot.data.repo.OutboxAdminRepository
import com.example.bot.guestlists.GuestListRepository
import com.example.bot.metrics.AppMetricsBinder
import com.example.bot.music.MusicService
import com.example.bot.observability.MetricsProvider
import com.example.bot.payments.provider.ProviderRefundClient
import com.example.bot.payments.provider.ProviderRefundHealth
import com.example.bot.plugins.appConfig
import com.example.bot.plugins.configureSecurity
import com.example.bot.plugins.installAppConfig
import com.example.bot.plugins.installHotPathLimiterDefaults
import com.example.bot.plugins.installMetrics
import com.example.bot.plugins.installMigrationsAndDatabase
import com.example.bot.plugins.installRateLimitPluginDefaults
import com.example.bot.plugins.installRbacIfEnabled
import com.example.bot.plugins.installRequestLogging
import com.example.bot.plugins.installTracingFromEnv
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.installMiniAppAuthStatusPage
import com.example.bot.plugins.meterRegistry
import com.example.bot.plugins.rbacSampleRoute
import com.example.bot.plugins.resolveFlag
import com.example.bot.plugins.withMiniAppAuth
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
import com.example.bot.routes.healthRoutes
import com.example.bot.routes.musicRoutes
import com.example.bot.routes.notifyHealthRoute
import com.example.bot.routes.notifyRoutes
import com.example.bot.routes.outboxAdminRoutes
import com.example.bot.routes.paymentsCancelRefundRoutes
import com.example.bot.routes.paymentsFinalizeRoutes
import com.example.bot.routes.securedRoutes
import com.example.bot.routes.telegramWebhookRoutes
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
import com.example.bot.webapp.webAppRoutes
import com.example.bot.workers.OutboxWorker
import com.example.bot.workers.RefundOutboxWorker
import com.example.bot.workers.RefundWorkerConfig
import com.example.bot.workers.launchCampaignSchedulerOnStart
import com.example.bot.workers.schedulerModule
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.WebAppInfo
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.DeleteWebhook
import com.pengrad.telegrambot.request.GetWebhookInfo
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.SetWebhook
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.micrometer.tracing.Tracer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.ext.getKoin
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory

/** Значения по умолчанию для запуска сервера (выносим «магические» литералы). */
private const val DEFAULT_HTTP_PORT: Int = 8080
private const val DEFAULT_BIND_HOST: String = "0.0.0.0"
private const val START_CLUBS_LIMIT = 4 // максимум для стартового меню

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
    // 0) Профиль приложения
    val appProfile: String =
        environment.config.propertyOrNull("app.profile")?.getString()
            ?: System.getenv("APP_PROFILE")
            ?: "DEV"
    val isTestProfile: Boolean = appProfile.equals("TEST", ignoreCase = true)

    if (isTestProfile) {
        install(ContentNegotiation) { json() }
        routing {
            get("/health") { call.respondText("OK", ContentType.Text.Plain) }
            get("/ready") { call.respondText("READY", ContentType.Text.Plain) }
            get("/ping") { call.respondText("OK", ContentType.Text.Plain) }
        }
        return
    }

    val bootstrapLogger = LoggerFactory.getLogger("ApplicationBootstrap")
    val rateLimitEnabled = resolveFlag("RATE_LIMIT_ENABLED", default = true)
    val hotPathEnabled = resolveFlag("HOT_PATH_ENABLED", default = true)
    bootstrapLogger.info("feature.rateLimit.enabled={}", rateLimitEnabled)
    bootstrapLogger.info("feature.hotPath.enabled={}", hotPathEnabled)

    // demo constants
    val demoStateKey = BotLimits.Demo.STATE_KEY
    val demoClubId = BotLimits.Demo.CLUB_ID
    val demoStartUtc = BotLimits.Demo.START_UTC
    val demoTableIds = BotLimits.Demo.TABLE_IDS

    // 1) Тюнинг сервера и наблюдаемость
    installServerTuning()
    if (rateLimitEnabled) {
        installRateLimitPluginDefaults()
    } else {
        bootstrapLogger.info("RateLimitPlugin skipped by flag")
    }
    if (hotPathEnabled) {
        installHotPathLimiterDefaults()
    } else {
        bootstrapLogger.info("HotPathLimiter skipped by flag")
    }
    installMetrics()
    AppMetricsBinder.bindAll(meterRegistry())
    installMigrationsAndDatabase()
    installAppConfig()
    installRequestLogging()
    installMiniAppAuthStatusPage()
    val tracingSetup = installTracingFromEnv()
    install(ContentNegotiation) { json() }

    val miniAppBotTokenProvider = { System.getenv("BOT_TOKEN") ?: "" }
    environment.log.info("InitDataAuth wired on /api/miniapp/*")

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
                        bot.execute(
                            (request as? BaseRequest<*, *>) ?: error(
                                "Unsupported request type: ${request::class}",
                            ),
                        )
                    },
                    texts = get(),
                    keyboards = get(),
                    promoService = get(),
                )
            }
            single {
                MenuCallbacksHandler(
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                )
            }
            single { CallbackQueryHandler(get(), get(), get()) }
        }

    val tracingModule: Module? =
        tracingSetup?.let { tracing ->
            module {
                single<Tracer> { tracing.tracer }
            }
        }

    install(Koin) {
        slf4jLogger()

        val refundEnabled: Boolean =
            System.getenv("REFUND_WORKER_ENABLED")?.equals("true", ignoreCase = true) ?: false

        // Быстрая предвалидация секрета, если включено
        fun requireRefundSecretIfEnabled() {
            if (!refundEnabled) return
            val token = System.getenv("REFUND_PROVIDER_TOKEN")
                ?: System.getenv("REFUND_PROVIDER_API_KEY") // совместимость с твоим .env
            require(!token.isNullOrBlank()) {
                "Refund worker is enabled (REFUND_WORKER_ENABLED=true) but refund token is missing. " +
                    "Set REFUND_PROVIDER_TOKEN or REFUND_PROVIDER_API_KEY."
            }
        }
        requireRefundSecretIfEnabled()

        val moduleList = mutableListOf(
            bookingModule,
            securityModule,
            webAppModule,
            availabilityModule,
            /* telegramModule добавляется ниже */ telegramModule,
            musicModule,
            schedulerModule,
            healthModule,
            notifyModule,
            paymentsModule,
            outboxAdminModule,
        ).apply {
            if (refundEnabled) {
                add(refundWorkerModule)
            } else {
                LoggerFactory.getLogger("RefundOutboxWorker")
                    .info("Refund module skipped (REFUND_WORKER_ENABLED=false)")
            }
        }

        modules(*moduleList.toTypedArray())
    }

    healthRoutes(get())

    routing {
        get("/refund/health") {
            val koin = getKoin()
            val clientOrNull = runCatching { koin.get<ProviderRefundClient>() }.getOrNull()
            if (clientOrNull == null) {
                call.respondText("NOT_SUPPORTED", status = HttpStatusCode.NotImplemented)
                return@get
            }
            val healthCapable = clientOrNull as? ProviderRefundHealth
            if (healthCapable == null) {
                call.respondText("NOT_SUPPORTED", status = HttpStatusCode.NotImplemented)
                return@get
            }
            runCatching { healthCapable.ping() }
                .onSuccess { call.respondText("OK") }
                .onFailure { ex ->
                    call.respondText(
                        "DOWN: ${ex.message ?: ex::class.simpleName}",
                        status = HttpStatusCode.ServiceUnavailable
                    )
                }
        }
    }

    launchCampaignSchedulerOnStart()

    configureSecurity()
    installRbacIfEnabled()

    val outboxWorker by inject<OutboxWorker>()
    val refundWorker by inject<RefundOutboxWorker>()
    val refundWorkerConfig by inject<RefundWorkerConfig>()
    val bot by inject<TelegramBot>()
    val ottService by inject<CallbackTokenService>()
    val callbackHandler by inject<CallbackQueryHandler>()
    val bookingService by inject<BookingService>()
    val guestListRepository: GuestListRepository = get()
    val availability: AvailabilityService = get()
    val outboxAdminRepository: OutboxAdminRepository = get()
    val guestFlowHandler by inject<GuestFlowHandler>()
    val clubRepository by inject<ClubRepository>()
    val startMenuLogger = LoggerFactory.getLogger("TelegramStartMenu")
    val updateHandlerLogger = LoggerFactory.getLogger("TelegramUpdateHandler")
    val webAppBaseUrl = resolveWebAppBaseUrl()
    val musicService: MusicService = get()
    val refundWorkerLogger = LoggerFactory.getLogger("RefundOutboxWorker")

    var workerJob: Job? = null
    var refundWorkerJob: Job? = null
    // Подписка на события жизненного цикла
    monitor.subscribe(ApplicationStarted) {
        workerJob = launch { outboxWorker.run() }
        if (refundWorkerConfig.enabled) {
            refundWorkerJob = launch { refundWorker.runLoop() }
        } else {
            refundWorkerLogger.info("Refund outbox worker disabled via REFUND_WORKER_ENABLED")
        }
    }
    monitor.subscribe(ApplicationStopped) {
        workerJob?.cancel()
        refundWorkerJob?.cancel()
    }

    // 5) Routes
    val renderer = DefaultHallRenderer()
    routing {
        get("/ping") {
            call.respondText("OK", ContentType.Text.Plain)
        }

        // Рендер схемы зала с кеш/ETag (публично)
        hallImageRoute(renderer) { _, _ -> demoStateKey }

        // Публичный guest-flow (клуб → список ночей)
        guestFlowRoutes(availability)

        // Публичный API доступности: ночи и свободные столы
        availabilityRoutes(availability)

        route("/api/miniapp") {
            withMiniAppAuth(miniAppBotTokenProvider)
            get("/me") {
                val user = call.attributes[MiniAppUserKey]
                call.respond(user)
            }
        }

        this@module.musicRoutes(musicService)
    }

    clubsPublicRoutes(clubRepository)

    // Новые публичные API-доступы для Mini App
    availabilityApiRoutes(availability)

    // Mini App статика, CSP, gzip
    webAppRoutes()
    rbacSampleRoute()

    val koin = getKoin()
    val metricsProvider = runCatching { koin.get<MetricsProvider>() }.getOrNull()
    val tracer = runCatching { koin.get<Tracer>() }.getOrNull()
    outboxAdminRoutes(outboxAdminRepository, metricsProvider, tracer)

    // Чек-ин верификатор (WebApp → POST /api/clubs/{clubId}/checkin/scan)
    checkinRoutes(repository = guestListRepository, initDataAuth = initDataAuthConfig)
    // алиасы для Mini App коротких путей
    checkinCompatRoutes(repository = guestListRepository)

    bookingFinalizeRoutes(bookingService) { telegramToken }
    paymentsFinalizeRoutes { telegramToken }
    paymentsCancelRefundRoutes { telegramToken }

    // RBAC-защищённые брони: /api/clubs/{clubId}/bookings/hold|confirm
    securedRoutes(bookingService, initDataAuth = initDataAuthConfig)

    // Гостевые списки: поиск/экспорт/импорт (RBAC/club scope внутри)
    guestListRoutes(
        repository = guestListRepository,
        parser = GuestListCsvParser(),
        initDataAuth = initDataAuthConfig,
    )

    val notifyRoutesEnabled = resolveFlag("NOTIFY_ROUTES_ENABLED", default = true)
    if (notifyRoutesEnabled) {
        notifyRoutes(
            tx = get(),
            campaigns = get(),
        )
        bootstrapLogger.info("notifyRoutes enabled under /api")
    } else {
        bootstrapLogger.info("notifyRoutes disabled by flag")
    }

    val telegramStartupLogger = LoggerFactory.getLogger("TelegramStartup")
    val koinAppConfig: AppConfig? = runCatching { get<AppConfig>() }.getOrNull()
    val resolvedAppConfig = koinAppConfig ?: runCatching { appConfig }.getOrNull()

    // Определяем режим бота
    val runMode =
        resolvedAppConfig?.runMode ?: run {
            val pollingEnv =
                System.getenv("TELEGRAM_USE_POLLING")
                    ?.equals("true", ignoreCase = true)
                    ?: false
            if (pollingEnv) BotRunMode.POLLING else BotRunMode.WEBHOOK
        }
    telegramStartupLogger.info("bot.runMode={}", runMode)

    val updateHandlerScope = CoroutineScope(coroutineContext + SupervisorJob())

    val handleUpdate: suspend (Update) -> Unit = handler@{ update ->
        // Базовая диагностика
        try {
            val hasMsg = update.message() != null
            val hasCb = update.callbackQuery() != null
            updateHandlerLogger.info(
                "telegram.update received: message={}, callback={}, chatId={}",
                hasMsg, hasCb, runCatching { update.message()?.chat()?.id() }.getOrNull()
            )
        } catch (_: Throwable) {
            // no-op
        }

        if (update.callbackQuery() != null) {
            callbackHandler.handle(update)
            return@handler
        }

        val msg = update.message() ?: return@handler
        val text = msg.text()?.trim() ?: ""
        val chatId = msg.chat().id()

        when {
            text.startsWith("/start", ignoreCase = true) ||
                text.equals("start", ignoreCase = true) ||
                text.equals("старт", ignoreCase = true) -> {

                // 1) Дадим базовый ответ всегда
                runCatching {
                    bot.execute(
                        SendMessage(
                            chatId,
                            "Бот запущен ✅\n" +
                                "Если кнопки ниже не появились, отправь команду /demo для теста."
                        )
                    )
                }

                // 2) Гостевой flow (если нужен deep-link и т.п.)
                runCatching { guestFlowHandler.handle(update) }
                    .onFailure { t ->
                        updateHandlerLogger.warn("guestFlow.handle failed: {}", t.toString())
                    }

                // 3) Попробуем показать меню клубов — при любом количестве (1..N)
                val clubs = runCatching {
                    clubRepository.listClubs(limit = START_CLUBS_LIMIT).take(START_CLUBS_LIMIT)
                }.getOrElse { emptyList() }

                if (clubs.isEmpty()) {
                    startMenuLogger.warn(
                        "telegram.start_menu.skipped chatId={} reason=empty_clubs",
                        chatId
                    )
                } else {
                    val keyboard = buildStartKeyboard(webAppBaseUrl, clubs)
                    val message = SendMessage(chatId, "Выберите клуб:").replyMarkup(keyboard)
                    bot.execute(message)
                    startMenuLogger.info(
                        "telegram.start_menu.sent chatId={} clubs={}",
                        chatId,
                        clubs.map { it.id },
                    )
                }
            }

            text.equals("/demo", ignoreCase = true) -> {
                val items =
                    demoTableIds.map { tableId ->
                        "Стол $tableId" to
                            BookTableAction(
                                demoClubId,
                                demoStartUtc,
                                tableId,
                            )
                    }
                val kb = KeyboardFactory.tableKeyboard(service = ottService, items = items)
                bot.execute(SendMessage(chatId, "Выберите стол:").replyMarkup(kb))
            }
        }
    }

    val dispatchUpdate: (Update) -> Unit = { update ->
        updateHandlerScope.launch(Dispatchers.IO) {
            runCatching { handleUpdate(update) }
                .onFailure { t ->
                    updateHandlerLogger.warn("telegram.update.failed: {}", t.toString())
                }
        }
    }

    // ---- Fallback-секрет вебхука и установка вебхука ----
    fun resolveWebhookSecretToken(config: AppConfig?): String? {
        val fromConfig = runCatching { config?.webhook?.secretToken }.getOrNull()
        if (!fromConfig.isNullOrBlank()) return fromConfig
        val env1 = System.getenv("WEBHOOK_SECRET_TOKEN")
        if (!env1.isNullOrBlank()) return env1
        val env2 = System.getenv("TELEGRAM_WEBHOOK_SECRET")
        if (!env2.isNullOrBlank()) return env2
        return null
    }

    when (runMode) {
        BotRunMode.POLLING -> {
            try {
                val response = bot.execute(DeleteWebhook().dropPendingUpdates(true))
                telegramStartupLogger.info(
                    "telegram.deleteWebhook ok={} error={}",
                    response.isOk,
                    response.description(),
                )
            } catch (t: Throwable) {
                telegramStartupLogger.warn("telegram.deleteWebhook failed: {}", t.toString())
            }

            bot.setUpdatesListener(
                object : UpdatesListener {
                    override fun process(updates: MutableList<Update>?): Int {
                        if (updates == null) {
                            return UpdatesListener.CONFIRMED_UPDATES_ALL
                        }
                        updates.forEach { update -> dispatchUpdate(update) }
                        return UpdatesListener.CONFIRMED_UPDATES_ALL
                    }
                },
            )
            telegramStartupLogger.info("telegram.runMode=POLLING: updates listener started")
        }
        BotRunMode.WEBHOOK -> {
            telegramStartupLogger.info("telegram.runMode=WEBHOOK: updates listener NOT started")

            val baseUrl = (resolvedAppConfig?.webhook?.baseUrl)
                ?: System.getenv("BASE_URL") // безопасный fallback на ENV
            val secret: String? = resolveWebhookSecretToken(resolvedAppConfig)

            if (!baseUrl.isNullOrBlank() && !secret.isNullOrBlank()) {
                val webhookUrl = baseUrl.trimEnd('/') + "/telegram/webhook"
                try {
                    val response =
                        bot.execute(
                            SetWebhook()
                                .url(webhookUrl)
                                .secretToken(secret),
                        )
                    telegramStartupLogger.info(
                        "telegram.setWebhook url={} ok={} err={}",
                        webhookUrl,
                        response.isOk,
                        response.description(),
                    )
                } catch (t: Throwable) {
                    telegramStartupLogger.warn("telegram.setWebhook failed: {}", t.toString())
                }
            } else {
                telegramStartupLogger.warn(
                    "telegram.webhook not set: baseUrl or secretToken is missing (baseUrl set? {}, secret set? {})",
                    !baseUrl.isNullOrBlank(),
                    !secret.isNullOrBlank(),
                )
            }

            try {
                val response = bot.execute(GetWebhookInfo())
                val webhookInfo = response.webhookInfo()
                telegramStartupLogger.info(
                    "telegram.getWebhookInfo ok={} url={} pending={} last_error_date={} last_error_message={}",
                    response.isOk,
                    webhookInfo?.url(),
                    webhookInfo?.pendingUpdateCount(),
                    webhookInfo?.lastErrorDate(),
                    webhookInfo?.lastErrorMessage(),
                )
            } catch (t: Throwable) {
                telegramStartupLogger.warn("telegram.getWebhookInfo failed: {}", t.toString())
            }

            // Передаём тот же секрет в маршруты вебхука (плагин безопасности проверит заголовок)
            telegramWebhookRoutes(bot, secret) { update -> dispatchUpdate(update) }
        }
    }
}

/** Определение базового URL для Mini App. */
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

/** Собираем клавиатуру старта для 1..N клубов (максимум START_CLUBS_LIMIT), по 2 кнопки в ряд. */
@Suppress("SpreadOperator")
private fun buildStartKeyboard(
    baseUrl: String,
    clubs: List<ClubDto>,
): InlineKeyboardMarkup {
    require(clubs.isNotEmpty()) { "No clubs to build keyboard" }
    val normalizedBase = baseUrl.removeSuffix("/")
    val count = minOf(START_CLUBS_LIMIT, clubs.size)
    val buttons =
        clubs.take(count).map { club ->
            InlineKeyboardButton(club.name).webApp(
                WebAppInfo("$normalizedBase/app?clubId=${club.id}"),
            )
        }
    val rows = buttons.chunked(2).map { it.toTypedArray() }
    return InlineKeyboardMarkup(*rows.toTypedArray())
}
