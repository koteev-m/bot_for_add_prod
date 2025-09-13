package com.example.bot

import com.example.bot.plugins.installAppConfig
import com.example.bot.plugins.installMetrics
import com.example.bot.plugins.installMigrationsAndDatabase
import com.example.bot.plugins.installRequestLogging
import com.example.bot.routes.healthRoute
import com.example.bot.routes.readinessRoute
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

@Suppress("unused")
fun Application.module() {
    // 1) Migrations + DB (18.3)
    installMigrationsAndDatabase()
    // 2) AppConfig (этот шаг должен идти раньше бизнес-логики)
    installAppConfig()
    // 3) Observability (18.4)
    installRequestLogging()
    installMetrics()
    // 4) Routes
    routing {
        healthRoute()
        readinessRoute()
        // … остальные маршруты приложения
    }
}
