package com.example.bot

import com.example.bot.plugins.installAppConfig
import com.example.bot.plugins.installHotPathLimiterDefaults
import com.example.bot.plugins.installMetrics
import com.example.bot.plugins.installMigrationsAndDatabase
import com.example.bot.plugins.installRequestLogging
import com.example.bot.render.DefaultHallRenderer
import com.example.bot.routes.hallImageRoute
import com.example.bot.routes.healthRoute
import com.example.bot.routes.readinessRoute
import com.example.bot.server.installServerTuning
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

@Suppress("unused")
fun Application.module() {
    // 0) Тюнинг сервера (лимиты размера запроса и пр.)
    installServerTuning()
    // 1) Лимиты на «горячие» пути (429 при переполнении)
    installHotPathLimiterDefaults()
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
}
