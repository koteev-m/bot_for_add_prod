package com.example.bot.plugins

import com.example.bot.ratelimit.SubjectBucketStore
import com.example.bot.ratelimit.TokenBucket
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.request.port
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Конфиг для rate-limiting.
 */
class RateLimitConfig {
    // IP
    var ipEnabled: Boolean = (System.getenv("RL_IP_ENABLED")?.toBooleanStrictOrNull() ?: true)
    var ipRps: Double = (System.getenv("RL_IP_RPS")?.toDoubleOrNull() ?: 100.0)
    var ipBurst: Double = (System.getenv("RL_IP_BURST")?.toDoubleOrNull() ?: 20.0)

    // Subject (например, chatId)
    var subjectEnabled: Boolean = (System.getenv("RL_SUBJECT_ENABLED")?.toBooleanStrictOrNull() ?: true)
    var subjectRps: Double = (System.getenv("RL_SUBJECT_RPS")?.toDoubleOrNull() ?: 60.0)
    var subjectBurst: Double = (System.getenv("RL_SUBJECT_BURST")?.toDoubleOrNull() ?: 20.0)
    var subjectTtlSeconds: Long = (System.getenv("RL_SUBJECT_TTL_SECONDS")?.toLongOrNull() ?: 600L)
    var subjectPathPrefixes: List<String> =
        listOf(
            "/webhook",
            "/api/bookings/confirm",
            "/api/guest-lists/import",
        )

    /**
     * Извлекает ключ для subject-лимитера (например, chatId).
     * По умолчанию пробуем X-Chat-Id, X-Telegram-Chat-Id, query chatId, иначе null.
     */
    var subjectKeyExtractor: suspend (io.ktor.server.application.ApplicationCall) -> String? = { call ->
        call.request.header("X-Chat-Id")
            ?: call.request.header("X-Telegram-Chat-Id")
            ?: call.request.queryParameters["chatId"]
    }

    // Ответ при ограничении
    var retryAfterSeconds: Int = (System.getenv("RL_RETRY_AFTER_SECONDS")?.toIntOrNull() ?: 1)
}

object RateLimitMetrics {
    val ipBlocked = AtomicLong(0)
    val subjectBlocked = AtomicLong(0)
    val subjectStoreSize = AtomicLong(0)
}

private fun String?.toBooleanStrictOrNull(): Boolean? = when (this?.lowercase()) {
    "true" -> true
    "false" -> false
    else -> null
}

val RateLimitPlugin =
    createApplicationPlugin(name = "RateLimitPlugin", createConfiguration = ::RateLimitConfig) {
        val cfg = pluginConfig

        val ipBuckets = ConcurrentHashMap<String, TokenBucket>()

        val subjectStore =
            SubjectBucketStore(
                capacity = cfg.subjectBurst,
                refillPerSec = cfg.subjectRps,
                ttl = Duration.ofSeconds(cfg.subjectTtlSeconds),
            )

        onCall { call ->
            val path = call.request.path()

            // 1) IP limiting
            if (cfg.ipEnabled) {
                val ip = clientIp(call)
                val bucket =
                    ipBuckets.computeIfAbsent(ip) {
                        TokenBucket(capacity = cfg.ipBurst, refillPerSec = cfg.ipRps)
                    }
                if (!bucket.tryAcquire()) {
                    RateLimitMetrics.ipBlocked.incrementAndGet()
                    call.response.header(HttpHeaders.RetryAfter, cfg.retryAfterSeconds.toString())
                    call.respondText("Too Many Requests (IP limit)", status = HttpStatusCode.TooManyRequests)
                    return@onCall
                }
            }

            // 2) Subject limiting
            if (cfg.subjectEnabled && cfg.subjectPathPrefixes.any { path.startsWith(it) }) {
                val key = cfg.subjectKeyExtractor(call)
                if (key != null) {
                    val ok = subjectStore.tryAcquire(key)
                    RateLimitMetrics.subjectStoreSize.set(subjectStore.size())
                    if (!ok) {
                        RateLimitMetrics.subjectBlocked.incrementAndGet()
                        call.response.header(HttpHeaders.RetryAfter, cfg.retryAfterSeconds.toString())
                        call.respondText("Too Many Requests (subject limit)", status = HttpStatusCode.TooManyRequests)
                        return@onCall
                    }
                }
            }
        }
    }

private fun clientIp(call: io.ktor.server.application.ApplicationCall): String {
    val xff = call.request.header("X-Forwarded-For")
    if (!xff.isNullOrBlank()) {
        val first = xff.split(',').first().trim()
        if (first.isNotEmpty()) return first
    }
    val real = call.request.header("X-Real-IP")
    if (!real.isNullOrBlank()) {
        return real
    }
    return call.request.host() + ":" + call.request.port()
}

fun Application.installRateLimitPluginDefaults() {
    install(RateLimitPlugin) {
        // настройки уже берутся из ENV; путь и extractor можно переопределить при необходимости
        // subjectKeyExtractor = { call ->
        //     call.request.header("X-Telegram-Chat-Id") ?: call.request.queryParameters["chatId"]
        // }
    }
}
