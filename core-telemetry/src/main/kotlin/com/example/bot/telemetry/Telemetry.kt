package com.example.bot.telemetry

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry

object Telemetry {
    val registry: MeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    fun Application.configureMonitoring() {
        routing {
            get("/metrics") {
                val metrics = (registry as PrometheusMeterRegistry).scrape()
                call.respondText(metrics, ContentType.parse("text/plain"))
            }
            get("/health") {
                call.respondText("OK")
            }
        }
    }
}
