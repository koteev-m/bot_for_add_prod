package com.example.bot.telemetry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.prometheus.PrometheusMeterRegistry

class TelemetryTest : StringSpec({
    "registry is prometheus" {
        Telemetry.registry.shouldBeInstanceOf<PrometheusMeterRegistry>()
    }
})
