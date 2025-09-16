package com.example.bot.ratelimit

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Простой потокобезопасный токен-бакет:
 *  - capacity: максимум токенов (burst)
 *  - refillPerSec: скорость пополнения в токенах/сек
 */
class TokenBucket(capacity: Double, refillPerSec: Double, nowNanos: Long = System.nanoTime()) {
    private val capacity = capacity.coerceAtLeast(1.0)
    private val refillPerSec = refillPerSec.coerceAtLeast(0.1)

    @Volatile private var tokens: Double = this.capacity

    @Volatile private var lastRefillNs: Long = nowNanos

    /**
     * Пытается взять 1 токен.
     * Возвращает true, если удалось (не блокирует), иначе false.
     */
    @Synchronized
    fun tryAcquire(nowNanos: Long = System.nanoTime()): Boolean {
        refill(nowNanos)
        return if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }

    @Synchronized
    private fun refill(nowNanos: Long) {
        val elapsedNs = max(0L, nowNanos - lastRefillNs)
        if (elapsedNs <= 0) return
        val elapsedSec = elapsedNs / 1_000_000_000.0
        tokens = min(capacity, tokens + elapsedSec * refillPerSec)
        lastRefillNs = nowNanos
    }
}

/**
 * Хранилище subject-бакетов с TTL (удаляем неиспользуемые).
 */
class SubjectBucketStore(private val capacity: Double, private val refillPerSec: Double, private val ttl: Duration) {
    private data class Entry(val bucket: TokenBucket, @Volatile var lastSeen: Instant)

    private val map = ConcurrentHashMap<String, Entry>()
    private val sizeCounter = AtomicLong(0)

    fun tryAcquire(subjectKey: String): Boolean {
        val now = Instant.now()
        val entry =
            map.compute(subjectKey) { _, old ->
                val e =
                    if (old == null) {
                        Entry(TokenBucket(capacity, refillPerSec), now).also { sizeCounter.incrementAndGet() }
                    } else {
                        old.lastSeen = now
                        old
                    }
                e
            }!!
        val ok = entry.bucket.tryAcquire()
        if (!ok) {
            cleanupIfNeeded(now)
        }
        return ok
    }

    fun size(): Long = sizeCounter.get()

    private fun cleanupIfNeeded(now: Instant) {
        // Ленивая очистка: если карта разрослась, удалим протухшие
        if (map.size < 10_000) return
        var removed = 0
        for ((k, v) in map.entries) {
            if (Duration.between(v.lastSeen, now).seconds >= ttl.seconds) {
                if (map.remove(k, v)) removed++
            }
        }
        if (removed > 0) {
            sizeCounter.addAndGet(-removed.toLong())
        }
    }
}
