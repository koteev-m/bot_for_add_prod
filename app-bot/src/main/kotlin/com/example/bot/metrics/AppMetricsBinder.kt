package com.example.bot.metrics

import com.example.bot.cache.HallCacheMetrics
import com.example.bot.data.db.DbMetrics
import com.example.bot.plugins.RateLimitMetrics
import com.example.bot.telegram.NotifyMetrics
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicLong

/**
 * Разовая привязка простых Atomic-метрик к Micrometer registry.
 * Вызывать один раз после установки MicrometerMetrics.
 */
object AppMetricsBinder {
    fun bindAll(registry: MeterRegistry) {
        // Hall cache (20.4)
        registry.gauge("hall.cache.evictions", HallCacheMetrics.evictions)
        registry.gauge("hall.cache.renders.ms.sum", HallCacheMetrics.rendersMs)
        // хит/миссы лучше как counters — сделаем прокси-гейдж: чтобы не ломать текущую реализацию
        registry.gauge("hall.cache.hits", AtomicLong(HallCacheMetrics.hits.sum()))
        registry.gauge("hall.cache.misses", AtomicLong(HallCacheMetrics.misses.sum()))

        // Rate limit (20.5)
        registry.gauge("ratelimit.ip.blocked", RateLimitMetrics.ipBlocked)
        registry.gauge("ratelimit.subject.blocked", RateLimitMetrics.subjectBlocked)
        registry.gauge("ratelimit.subject.size", RateLimitMetrics.subjectStoreSize)

        // Telegram sender (20.3)
        registry.gauge("tg.send.ok.atomic", NotifyMetrics.ok)
        registry.gauge("tg.send.retry_after.atomic", NotifyMetrics.retryAfter)
        registry.gauge("tg.send.retryable.atomic", NotifyMetrics.retryable)
        registry.gauge("tg.send.permanent.atomic", NotifyMetrics.permanent)

        // DB metrics (20.2)
        registry.gauge("db.tx.retries.atomic", DbMetrics.txRetries)
        registry.gauge("db.query.slow.count.atomic", DbMetrics.slowQueryCount)

        // UI booking metrics
        UiBookingMetrics.bind(registry)
    }
}
