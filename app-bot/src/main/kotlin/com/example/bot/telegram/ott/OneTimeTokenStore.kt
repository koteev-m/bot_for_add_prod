package com.example.bot.telegram.ott

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/** Маркер всех payload’ов, на которые мапится одноразовый токен. */
sealed interface OttPayload

/** Пример payload’а: действие «забронировать стол». */
data class BookTableAction(val clubId: Long, val startUtc: String, val tableId: Long) : OttPayload

/** Простейшие метрики стора. */
object OttMetrics {
    val issued = AtomicLong(0)
    val consumed = AtomicLong(0)
    val replayed = AtomicLong(0)
    val storeSize = AtomicInteger(0)
}

/** API стора: issue — выдать новый токен; consume — атомарно получить payload и удалить. */
interface OneTimeTokenStore {
    fun issue(payload: OttPayload): String

    fun consume(token: String): OttPayload?

    fun size(): Int
}

/**
 * In-memory One-Time Token Store:
 *  - TTL: истёкшие записи очищаются лениво;
 *  - LRU-ограничение по размеру: при переполнении — эвиктим в порядке вставки (упрощённо);
 *  - Потокобезопасность: ConcurrentHashMap + lock-free очереди.
 */
class InMemoryOneTimeTokenStore(
    ttlSeconds: Long = System.getenv("OTT_TTL_SECONDS")?.toLongOrNull() ?: 300L,
    maxEntries: Int = System.getenv("OTT_MAX_ENTRIES")?.toIntOrNull() ?: 100_000,
) : OneTimeTokenStore {

    private data class Entry(val payload: OttPayload, val expiresAt: Instant)

    private val ttl: Duration = Duration.ofSeconds(ttlSeconds.coerceAtLeast(30))
    private val maxEntries: Int = maxEntries.coerceAtLeast(1)

    private val map = ConcurrentHashMap<String, Entry>(16, 0.75f, 4)
    private val order = ConcurrentLinkedQueue<String>() // упрощённое LRU по порядку вставки
    private val cleanupThreshold = min(10_000, this.maxEntries / 2)

    // CSPRNG для токенов
    private val random = SecureRandom()

    override fun issue(payload: OttPayload): String {
        cleanupIfNeeded()
        val token = generateToken()
        val entry = Entry(payload = payload, expiresAt = Instant.now().plus(ttl))
        map[token] = entry
        order.add(token)
        OttMetrics.issued.incrementAndGet()
        OttMetrics.storeSize.set(map.size)
        return token
    }

    override fun consume(token: String): OttPayload? {
        cleanupIfNeeded()
        val entry =
            map.remove(token) ?: run {
                OttMetrics.replayed.incrementAndGet()
                return null
            }
        OttMetrics.storeSize.set(map.size)
        return if (Instant.now().isAfter(entry.expiresAt)) {
            // истёк — считаем как replay/просрочку
            OttMetrics.replayed.incrementAndGet()
            null
        } else {
            OttMetrics.consumed.incrementAndGet()
            entry.payload
        }
    }

    override fun size(): Int = map.size

    private fun generateToken(): String {
        // 16–24 байта энтропии → base64url без паддинга; длина < 64
        val len = 20 + random.nextInt(5) // 20..24
        val bytes = ByteArray(len)
        random.nextBytes(bytes)
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        // страховка на редкий случай длинее 64 (не случится при выбранных размерах)
        return if (b64.length <= 64) b64 else b64.substring(0, 64)
    }

    private fun cleanupIfNeeded() {
        if (map.isEmpty()) return
        evictOverflowIfAny()
        evictExpiredIfLarge()
    }

    /** Эвикция при переполнении (упрощённое LRU по порядку вставки). */
    private fun evictOverflowIfAny() {
        while (map.size > maxEntries) {
            val victim = order.poll() ?: return
            map.remove(victim)
        }
        OttMetrics.storeSize.set(map.size)
    }

    /** Ленивая TTL-очистка при заметном росте. */
    private fun evictExpiredIfLarge() {
        if (map.size <= cleanupThreshold) return
        val now = Instant.now()
        var removed = 0
        for (k in order) {
            val e = map[k] ?: continue
            if (now.isAfter(e.expiresAt) && map.remove(k, e)) removed++
        }
        if (removed > 0) OttMetrics.storeSize.set(map.size)
    }
}
