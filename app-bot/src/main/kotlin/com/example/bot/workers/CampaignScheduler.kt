package com.example.bot.workers

import com.example.bot.notifications.SchedulerApi
import com.example.bot.telemetry.Telemetry
import io.micrometer.core.instrument.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val CRON_PARTS = 5
private val FULL_MINUTE_RANGE = 0..59
private const val DAYS_IN_WEEK = 7
private val DEFAULT_TICK_INTERVAL: Duration = Duration.ofSeconds(10)
private const val DEFAULT_BATCH_SIZE = 1_000
private const val MIN_INDEX = 0
private const val HOUR_INDEX = 1
private const val DOM_INDEX = 2
private const val MONTH_INDEX = 3
private const val DOW_INDEX = 4

/**
 * Periodically checks notification campaigns and enqueues recipients
 * into notifications_outbox in batches.
 */
class CampaignScheduler(
    private val scope: CoroutineScope,
    private val api: SchedulerApi,
    private val tickInterval: Duration = DEFAULT_TICK_INTERVAL,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
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
                handleCampaign(c, now)
            }
            delay(tickInterval.toMillis())
        }
    }

    private suspend fun handleCampaign(c: SchedulerApi.Campaign, now: OffsetDateTime) {
        val scheduledButNotDue =
            c.status == SchedulerApi.Status.SCHEDULED && !isDue(c, now)
        val paused = c.status == SchedulerApi.Status.PAUSED
        if (!scheduledButNotDue && !paused) {
            if (c.status == SchedulerApi.Status.SCHEDULED) {
                api.markSending(c.id)
            }
            val added = api.enqueueBatch(c.id, batchSize)
            if (added > 0) {
                Telemetry.registry
                    .counter("notify_campaign_enqueued_total", "campaign", c.id.toString())
                    .increment(added.toDouble())
            }
            val progress = api.progress(c.id)
            remainingGauge(c.id).set(progress.total - progress.enqueued)
            if (progress.enqueued >= progress.total && progress.total > 0) {
                api.markDone(c.id)
            }
        }
    }

    private fun remainingGauge(id: Long): AtomicLong {
        return remaining.computeIfAbsent(id) {
            AtomicLong(0).also { ref ->
                Telemetry.registry.gauge(
                    "notify_campaign_remaining",
                    listOf(Tag.of("campaign", id.toString())),
                    ref,
                )
            }
        }
    }

    private fun isDue(c: SchedulerApi.Campaign, now: OffsetDateTime): Boolean {
        val cron = c.scheduleCron
        return (c.startsAt == null || !now.isBefore(c.startsAt)) &&
            (cron == null || cronMatches(cron, now))
    }

    private fun cronMatches(expr: String, time: OffsetDateTime): Boolean {
        val parts = expr.trim().split(" ").filter { it.isNotEmpty() }
        return if (parts.size == CRON_PARTS) {
            val min = parts[MIN_INDEX]
            val hour = parts[HOUR_INDEX]
            val dom = parts[DOM_INDEX]
            val month = parts[MONTH_INDEX]
            val dow = parts[DOW_INDEX]
            match(min, time.minute) &&
                match(hour, time.hour) &&
                match(dom, time.dayOfMonth) &&
                match(month, time.monthValue) &&
                match(dow, time.dayOfWeek.value % DAYS_IN_WEEK)
        } else {
            false
        }
    }

    private fun match(field: String, value: Int): Boolean {
        return when {
            field == "*" -> true
            field.contains(",") -> field.split(",").any { match(it, value) }
            field.contains("/") -> {
                val (base, stepStr) = field.split("/")
                val step = stepStr.toInt()
                val range = if (base == "*") FULL_MINUTE_RANGE else parseRange(base)
                value in range && (value - range.first) % step == 0
            }
            field.contains("-") -> value in parseRange(field)
            else -> field.toIntOrNull() == value
        }
    }

    private fun parseRange(s: String): IntRange {
        val (start, end) = s.split("-")
        return start.toInt()..end.toInt()
    }
}
