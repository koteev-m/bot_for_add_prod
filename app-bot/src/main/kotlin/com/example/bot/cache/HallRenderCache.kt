package com.example.bot.cache

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

data class CacheEntry(
    val etag: String,
    val bytes: ByteArray,
    val expiresAt: Instant
)

/**
 * Простой потокобезопасный TTL + LRU кэш на LinkedHashMap(access-order).
 * Все публичные методы синхронизированы.
 */
class TtlLruCache<K, V>(
    private val maxEntries: Int,
    private val ttl: Duration
) {
    private val map: LinkedHashMap<K, Timed<V>> = object : LinkedHashMap<K, Timed<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Timed<V>>): Boolean {
            val evict = size > maxEntries
            if (evict) {
                HallCacheMetrics.evictions.increment()
            }
            return evict
        }
    }

    data class Timed<V>(val value: V, val expiresAt: Instant)

    @Synchronized
    fun get(key: K): V? {
        val t = map[key] ?: return null
        if (Instant.now().isAfter(t.expiresAt)) {
            map.remove(key)
            return null
        }
        return t.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = Timed(value, Instant.now().plus(ttl))
    }

    @Synchronized
    fun size(): Int = map.size
}

/**
 * Метрики кэша (простые Atomic/Adder; при желании подключаются к Micrometer извне).
 */
object HallCacheMetrics {
    val hits: LongAdder = LongAdder()
    val misses: LongAdder = LongAdder()
    val evictions: LongAdder = LongAdder()
    val rendersMs: AtomicLong = AtomicLong(0)
}

/**
 * Обёртка для работы с кэшем рендера и ETag.
 */
class HallRenderCache(
    maxEntries: Int = System.getenv("HALL_CACHE_MAX_ENTRIES")?.toIntOrNull() ?: 500,
    ttlSeconds: Long = System.getenv("HALL_CACHE_TTL_SECONDS")?.toLongOrNull() ?: 60L
) {
    private val ttl = Duration.ofSeconds(ttlSeconds)
    private val cache = TtlLruCache<String, CacheEntry>(maxEntries, ttl)

    sealed interface Result {
        data class NotModified(val etag: String) : Result
        data class Ok(val etag: String, val bytes: ByteArray) : Result
    }

    /**
     * Вернёт NotModified если ifNoneMatch совпадает с актуальным etag, иначе Ok с байтами.
     * supplier должен отрисовать новые байты при отсутствии кэша.
     */
    suspend fun getOrRender(
        key: String,
        ifNoneMatch: String?,
        supplier: suspend () -> ByteArray
    ): Result {
        val cached = cache.get(key)
        if (cached != null) {
            if (ifNoneMatch != null && equalsWeakEtag(ifNoneMatch, cached.etag)) {
                HallCacheMetrics.hits.increment()
                return Result.NotModified(cached.etag)
            }
            HallCacheMetrics.hits.increment()
            return Result.Ok(cached.etag, cached.bytes)
        }

        HallCacheMetrics.misses.increment()
        val start = System.nanoTime()
        val bytes = supplier.invoke()
        val tookMs = (System.nanoTime() - start) / 1_000_000
        HallCacheMetrics.rendersMs.addAndGet(tookMs)

        val etag = computeEtag(bytes)
        val entry = CacheEntry(etag = etag, bytes = bytes, expiresAt = Instant.now().plus(ttl))
        cache.put(key, entry)

        if (ifNoneMatch != null && equalsWeakEtag(ifNoneMatch, etag)) {
            return Result.NotModified(etag)
        }
        return Result.Ok(etag, bytes)
    }

    companion object {
        fun computeEtag(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
            return "W/\"$b64\""
        }

        private fun equalsWeakEtag(ifNoneMatch: String, etag: String): Boolean {
            if (ifNoneMatch.trim() == "*") return true
            val normA = ifNoneMatch.trim()
            val normB = etag.trim()
            return normA == normB
        }
    }
}
