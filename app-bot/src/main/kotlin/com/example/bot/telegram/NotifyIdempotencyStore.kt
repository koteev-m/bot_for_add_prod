package com.example.bot.telegram

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

interface NotifyIdempotencyStore {
    fun seen(key: String): Boolean
    fun mark(key: String)
}

class InMemoryNotifyIdempotencyStore(
    ttl: Duration = Duration.ofHours(System.getenv("NOTIFY_IDEMPOTENCY_TTL_HOURS")?.toLongOrNull() ?: 24L)
) : NotifyIdempotencyStore {

    private data class Entry(val timestamp: Instant)

    private val ttl: Duration = ttl
    private val map: ConcurrentHashMap<String, Entry> = ConcurrentHashMap()
    private val cleaning: AtomicBoolean = AtomicBoolean(false)

    override fun seen(key: String): Boolean {
        cleanupIfNeeded()
        val entry = map[key] ?: return false
        if (Instant.now().isAfter(entry.timestamp.plus(ttl))) {
            map.remove(key)
            return false
        }
        return true
    }

    override fun mark(key: String) {
        cleanupIfNeeded()
        map[key] = Entry(Instant.now())
    }

    private fun cleanupIfNeeded() {
        if (map.size <= 50_000 || !cleaning.compareAndSet(false, true)) return
        val now = Instant.now()
        try {
            map.entries.removeIf { now.isAfter(it.value.timestamp.plus(ttl)) }
        } finally {
            cleaning.set(false)
        }
    }
}

