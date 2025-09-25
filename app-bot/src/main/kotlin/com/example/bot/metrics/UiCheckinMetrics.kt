package com.example.bot.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

private const val CHECKIN_PERCENTILE_P50 = 0.5
private const val CHECKIN_PERCENTILE_P95 = 0.95
private const val CHECKIN_PERCENTILE_P99 = 0.99

object UiCheckinMetrics {
    @PublishedApi
    @Volatile
    internal var checkinScanTimer: Timer? = null

    @Volatile
    private var cScanTotal: Counter? = null

    @Volatile
    private var cScanError: Counter? = null

    fun bind(registry: MeterRegistry) {
        cScanTotal =
            Counter
                .builder("ui.checkin.scan.total")
                .description("Total check-in scan attempts")
                .register(registry)

        cScanError =
            Counter
                .builder("ui.checkin.scan.error")
                .description("Failed check-in scans (any error)")
                .register(registry)

        checkinScanTimer =
            Timer
                .builder("ui.checkin.scan.duration.ms")
                .publishPercentiles(
                    CHECKIN_PERCENTILE_P50,
                    CHECKIN_PERCENTILE_P95,
                    CHECKIN_PERCENTILE_P99,
                )
                .description("Check-in scan processing duration")
                .register(registry)
    }

    fun incTotal() {
        cScanTotal?.increment()
    }

    fun incError() {
        cScanError?.increment()
    }

    inline fun <T> timeScan(block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            checkinScanTimer?.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        }
    }
}
