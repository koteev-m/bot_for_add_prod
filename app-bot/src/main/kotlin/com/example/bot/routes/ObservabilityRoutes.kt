package com.example.bot.routes

import com.example.bot.observability.CheckStatus
import com.example.bot.observability.HealthService
import com.example.bot.observability.MetricsProvider
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.micrometer.prometheus.PrometheusMeterRegistry

fun Route.observabilityRoutes(metrics: MetricsProvider, health: HealthService) {
    get("/metrics") {
        val registry = metrics.registry as PrometheusMeterRegistry
        call.respondText(
            registry.scrape(),
            ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
        )
    }
    get("/health") {
        val report = health.health()
        val status = if (report.status == CheckStatus.UP) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(status, report)
    }
    get("/ready") {
        val report = health.readiness()
        val status = if (report.status == CheckStatus.UP) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(status, report)
    }
}
