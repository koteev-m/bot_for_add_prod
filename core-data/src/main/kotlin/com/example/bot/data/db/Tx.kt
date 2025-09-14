package com.example.bot.data.db

import java.sql.SQLException
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.min
import kotlin.system.measureNanoTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory

/**
 * Настройки retry/backoff читаются из ENV; есть дефолты.
 */
private fun envInt(name: String, default: Int): Int =
    System.getenv(name)?.toIntOrNull() ?: default

private fun envLong(name: String, default: Long): Long =
    System.getenv(name)?.toLongOrNull() ?: default

private val log = LoggerFactory.getLogger("DB.Tx")

private val MAX_RETRIES: Int = envInt("DB_TX_MAX_RETRIES", 3)
private val BASE_BACKOFF_MS: Long = envLong("DB_TX_BASE_BACKOFF_MS", 500)
private val MAX_BACKOFF_MS: Long = envLong("DB_TX_MAX_BACKOFF_MS", 15_000)
private val JITTER_MS: Long = envLong("DB_TX_JITTER_MS", 100)
private val SLOW_QUERY_MS: Long = envLong("DB_SLOW_QUERY_MS", 200)

/**
 * Возвращает SQLState из цепочки причин, если есть.
 */
private fun sqlStateOf(ex: Throwable): String? {
    var cur: Throwable? = ex
    while (cur != null) {
        if (cur is SQLException) return cur.sqlState
        cur = cur.cause
    }
    return null
}

private fun isRetryableSqlState(sqlState: String?): Boolean {
    // PostgreSQL: 40P01 (deadlock detected), 40001 (serialization failure)
    return sqlState == "40P01" || sqlState == "40001"
}

/**
 * Экспоненциальный backoff с джиттером [0..JITTER_MS].
 */
private suspend fun backoff(attempt: Int) {
    val exp = 1L shl attempt.coerceAtMost(20) // защита от переполнения
    val base = BASE_BACKOFF_MS * exp
    val delayMs = min(base, MAX_BACKOFF_MS)
    val jitter = if (JITTER_MS > 0) ThreadLocalRandom.current().nextLong(0, JITTER_MS + 1) else 0
    delay(delayMs + jitter)
}

/**
 * Выполнить блок внутри Exposed-транзакции (IO), с retry на deadlock/serialization,
 * и slow-query логом по порогу SLOW_QUERY_MS.
 */
suspend fun <T> txRetrying(db: Database? = null, block: suspend () -> T): T {
    var attempt = 0
    var lastError: Throwable? = null

    while (attempt <= MAX_RETRIES) {
        try {
            var result: T
            val elapsed = measureNanoTime {
                result =
                    newSuspendedTransaction(
                        context = Dispatchers.IO,
                        db = db,
                    ) {
                        block.invoke()
                    }
            }
            val tookMs = elapsed / 1_000_000
            if (tookMs > SLOW_QUERY_MS) {
                DbMetrics.slowQueryCount.incrementAndGet()
                log.warn("Slow transaction detected: {} ms > {} ms", tookMs, SLOW_QUERY_MS)
            }
            return result
        } catch (ex: Throwable) {
            lastError = ex
            val state = sqlStateOf(ex)
            val retryable = isRetryableSqlState(state)
            if (!retryable || attempt == MAX_RETRIES) {
                log.error(
                    "DB tx failed (attempt={} / max={}, sqlState={}): {}",
                    attempt,
                    MAX_RETRIES,
                    state,
                    ex.toString(),
                )
                throw ex
            }
            DbMetrics.txRetries.incrementAndGet()
            log.warn(
                "DB tx retrying (attempt={} / max={}, sqlState={}): {}",
                attempt + 1,
                MAX_RETRIES,
                state,
                ex.toString(),
            )
            attempt += 1
            backoff(attempt)
        }
    }
    throw lastError ?: IllegalStateException("txRetrying failed without exception")
}

