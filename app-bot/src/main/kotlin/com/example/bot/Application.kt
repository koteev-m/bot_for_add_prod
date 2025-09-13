package com.example.bot

import com.example.bot.plugins.installMigrationsAndDatabase
import com.example.bot.plugins.installMetrics
import com.example.bot.plugins.installRequestLogging
import com.example.bot.routes.healthRoute
import com.example.bot.routes.readinessRoute
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

@Suppress("unused")
fun Application.module() {
    // 1) Сначала миграции и БД
    installMigrationsAndDatabase()
    // 2) Логирование и метрики
    installRequestLogging()
    installMetrics()
    // 3) Роуты наблюдаемости
    routing {
        healthRoute()
        readinessRoute()
        // … остальные роуты приложения
    }
}

