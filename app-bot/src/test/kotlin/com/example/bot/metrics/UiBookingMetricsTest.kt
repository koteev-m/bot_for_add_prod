package com.example.bot.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class UiBookingMetricsTest {
    private lateinit var registry: SimpleMeterRegistry

    @BeforeEach
    fun setUp() {
        UiBookingMetrics.menuClicks.set(0)
        UiBookingMetrics.nightsRendered.set(0)
        UiBookingMetrics.tablesRendered.set(0)
        UiBookingMetrics.pagesRendered.set(0)
        UiBookingMetrics.tableChosen.set(0)
        UiBookingMetrics.guestsChosen.set(0)
        UiBookingMetrics.bookingSuccess.set(0)
        UiBookingMetrics.bookingError.set(0)
        registry = SimpleMeterRegistry()
        UiBookingMetrics.bind(registry)
    }

    @AfterEach
    fun tearDown() {
        registry.close()
    }

    @Test
    fun `counters are exposed via gauges`() {
        UiBookingMetrics.menuClicks.incrementAndGet()
        UiBookingMetrics.nightsRendered.incrementAndGet()
        UiBookingMetrics.tablesRendered.incrementAndGet()
        UiBookingMetrics.pagesRendered.incrementAndGet()
        UiBookingMetrics.tableChosen.incrementAndGet()
        UiBookingMetrics.guestsChosen.incrementAndGet()
        UiBookingMetrics.bookingSuccess.incrementAndGet()
        UiBookingMetrics.bookingError.incrementAndGet()

        assertEquals(1.0, registry.get("ui.menu.clicks").gauge().value())
        assertEquals(1.0, registry.get("ui.nights.rendered").gauge().value())
        assertEquals(1.0, registry.get("ui.tables.rendered").gauge().value())
        assertEquals(1.0, registry.get("ui.tables.pages").gauge().value())
        assertEquals(1.0, registry.get("ui.table.chosen").gauge().value())
        assertEquals(1.0, registry.get("ui.guests.chosen").gauge().value())
        assertEquals(1.0, registry.get("ui.booking.success").gauge().value())
        assertEquals(1.0, registry.get("ui.booking.error").gauge().value())
    }

    @Test
    fun `timers record durations`() {
        UiBookingMetrics.timeListTables { Thread.sleep(10) }
        UiBookingMetrics.timeBookingTotal { Thread.sleep(5) }

        val listTimer = registry.get("ui.tables.fetch.duration.ms").timer()
        val totalTimer = registry.get("ui.booking.total.duration.ms").timer()

        assertTrue(listTimer.totalTime(TimeUnit.MILLISECONDS) > 0.0)
        assertTrue(totalTimer.totalTime(TimeUnit.MILLISECONDS) > 0.0)
    }
}
