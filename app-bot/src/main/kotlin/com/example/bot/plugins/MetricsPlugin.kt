package com.example.bot.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry

private val prometheusRegistry: PrometheusMeterRegistry by lazy {
    PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
}

fun Application.installMetrics() {
    install(MicrometerMetrics) {
        registry = prometheusRegistry
        timers { _, _ -> io.micrometer.core.instrument.Timer.builder("http.server.requests") }
    }
    routing {
        metricsRoute()
    }
}

fun Route.metricsRoute() {
    get("/metrics") {
        val scrape = prometheusRegistry.scrape()
        call.respondText(
            text = scrape,
            contentType = io.ktor.http.ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
        )
    }
}

