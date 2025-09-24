package com.example.bot.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object UiBookingMetrics {
    val menuClicks = AtomicLong(0)
    val nightsRendered = AtomicLong(0)
    val tablesRendered = AtomicLong(0)
    val pagesRendered = AtomicLong(0)
    val tableChosen = AtomicLong(0)
    val guestsChosen = AtomicLong(0)
    val bookingSuccess = AtomicLong(0)
    val bookingError = AtomicLong(0)

    @PublishedApi
    @Volatile
    internal var listTablesTimer: Timer? = null

    @PublishedApi
    @Volatile
    internal var bookingTotalTimer: Timer? = null
    private const val PERCENTILE_MEDIAN = 0.5
    private const val PERCENTILE_95 = 0.95
    private const val PERCENTILE_99 = 0.99

    fun bind(registry: MeterRegistry) {
        registry.gauge("ui.menu.clicks", menuClicks)
        registry.gauge("ui.nights.rendered", nightsRendered)
        registry.gauge("ui.tables.rendered", tablesRendered)
        registry.gauge("ui.tables.pages", pagesRendered)
        registry.gauge("ui.table.chosen", tableChosen)
        registry.gauge("ui.guests.chosen", guestsChosen)
        registry.gauge("ui.booking.success", bookingSuccess)
        registry.gauge("ui.booking.error", bookingError)

        listTablesTimer =
            Timer.builder("ui.tables.fetch.duration.ms")
                .publishPercentiles(PERCENTILE_MEDIAN, PERCENTILE_95, PERCENTILE_99)
                .description("AvailabilityService.listFreeTables duration")
                .register(registry)

        bookingTotalTimer =
            Timer.builder("ui.booking.total.duration.ms")
                .publishPercentiles(PERCENTILE_MEDIAN, PERCENTILE_95, PERCENTILE_99)
                .description("End-to-end booking flow from guests selection")
                .register(registry)
    }

    inline fun <T> timeListTables(block: () -> T): T {
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            listTablesTimer?.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        }
    }

    inline fun <T> timeBookingTotal(block: () -> T): T {
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            bookingTotalTimer?.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        }
    }
}
