package com.example.bot.workers

import com.example.bot.notifications.SchedulerApi
import com.example.bot.telemetry.Telemetry
import io.micrometer.core.instrument.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Periodically checks notification campaigns and enqueues recipients
 * into notifications_outbox in batches.
 */
class CampaignScheduler(
    private val scope: CoroutineScope,
    private val api: SchedulerApi,
    private val tickMillis: Long = 10_000,
    private val batchSize: Int = 1_000,
) {
    private val remaining = ConcurrentHashMap<Long, AtomicLong>()

    /** Starts background scheduling loop. */
    fun start() {
        scope.launch { loop() }
    }

    private suspend fun loop() {
        while (scope.isActive) {
            val now = OffsetDateTime.now()
            val campaigns = api.listActive()
            for (c in campaigns) {
                if (c.status == SchedulerApi.Status.SCHEDULED) {
                    if (!isDue(c, now)) continue
                    api.markSending(c.id)
                }
                if (c.status == SchedulerApi.Status.PAUSED) continue

                val added = api.enqueueBatch(c.id, batchSize)
                if (added > 0) {
                    Telemetry.registry
                        .counter("notify_campaign_enqueued_total", "campaign", c.id.toString())
                        .increment(added.toDouble())
                }
                val pr = api.progress(c.id)
                remainingGauge(c.id).set(pr.total - pr.enqueued)
                if (pr.enqueued >= pr.total && pr.total > 0) {
                    api.markDone(c.id)
                }
            }
            delay(tickMillis)
        }
    }

    private fun remainingGauge(id: Long): AtomicLong =
        remaining.computeIfAbsent(id) {
            AtomicLong(0).also { ref ->
                Telemetry.registry.gauge(
                    "notify_campaign_remaining",
                    listOf(Tag.of("campaign", id.toString())),
                    ref,
                )
            }
        }

    private fun isDue(c: SchedulerApi.Campaign, now: OffsetDateTime): Boolean {
        c.startsAt?.let { if (now.isBefore(it)) return false }
        c.scheduleCron?.let { if (!cronMatches(it, now)) return false }
        return true
    }

    private fun cronMatches(expr: String, time: OffsetDateTime): Boolean {
        val parts = expr.trim().split(" ").filter { it.isNotEmpty() }
        if (parts.size != 5) return false
        val (min, hour, dom, month, dow) = parts
        return match(min, time.minute) &&
            match(hour, time.hour) &&
            match(dom, time.dayOfMonth) &&
            match(month, time.monthValue) &&
            match(dow, time.dayOfWeek.value % 7)
    }

    private fun match(field: String, value: Int): Boolean {
        if (field == "*") return true
        if (field.contains(",")) return field.split(",").any { match(it, value) }
        if (field.contains("/")) {
            val (base, stepStr) = field.split("/")
            val step = stepStr.toInt()
            val range = if (base == "*") 0..59 else parseRange(base)
            if (value !in range) return false
            return (value - range.first) % step == 0
        }
        if (field.contains("-")) {
            val r = parseRange(field)
            return value in r
        }
        return field.toIntOrNull() == value
    }

    private fun parseRange(s: String): IntRange {
        val (start, end) = s.split("-")
        return start.toInt()..end.toInt()
    }
}
