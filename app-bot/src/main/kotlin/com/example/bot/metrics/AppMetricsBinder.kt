package com.example.bot.metrics

import io.micrometer.core.instrument.MeterRegistry

/**
 * Разовая привязка простых Atomic-метрик к Micrometer registry.
 * Вызывать один раз после установки MicrometerMetrics.
 */
object AppMetricsBinder {
    fun bindAll(registry: MeterRegistry) {
        UiBookingMetrics.bind(registry)
        UiCheckinMetrics.bind(registry)
    }
}
