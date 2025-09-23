package com.example.bot.plugins

import com.example.bot.config.BotLimits
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Конфигурация плагина лимитирования «горячих» путей.
 */
class HotPathLimiterConfig {
    /**
     * Список префиксов путей, к которым применяем лимит (например, ["/webhook", "/api/bookings/confirm"]).
     */
    var pathPrefixes: List<String> = emptyList()

    /**
     * Максимум параллельных обработок для каждого совпадающего пути.
     */
    var maxConcurrent: Int =
        Runtime.getRuntime().availableProcessors()
            .coerceAtLeast(BotLimits.RateLimit.HOT_PATH_MIN_CONFIG_PARALLELISM)

    /**
     * Заголовок с информацией о лимитах (например, Retry-After).
     */
    var throttlingHeader: String = "Retry-After"

    /**
     * Значение Retry-After (секунды) при отказе.
     */
    var retryAfter: Duration = BotLimits.RateLimit.HOT_PATH_DEFAULT_RETRY_AFTER
}

/**
 * Простые метрики плагина (без привязки к конкретному реестру; можно подключить к Micrometer извне).
 */
object HotPathMetrics {
    val active = AtomicInteger(0)
    val throttled = AtomicLong(0)
    val availablePermits = AtomicInteger(0)
}

private enum class HotPathDecision { SKIP, ACQUIRE, THROTTLE }

val HotPathLimiter =
    createApplicationPlugin(name = "HotPathLimiter", createConfiguration = ::HotPathLimiterConfig) {
        val cfg = pluginConfig
        val semaphore = Semaphore(cfg.maxConcurrent, true)

        onCall { call ->
            val path = call.request.path()
            val decision =
                when {
                    cfg.pathPrefixes.none { prefix -> path.startsWith(prefix) } -> HotPathDecision.SKIP
                    semaphore.tryAcquire() -> HotPathDecision.ACQUIRE
                    else -> HotPathDecision.THROTTLE
                }

            when (decision) {
                HotPathDecision.SKIP -> Unit
                HotPathDecision.THROTTLE -> {
                    HotPathMetrics.throttled.incrementAndGet()
                    call.response.header(cfg.throttlingHeader, cfg.retryAfter.seconds.toString())
                    call.respondText("Too Many Requests", status = HttpStatusCode.TooManyRequests)
                }
                HotPathDecision.ACQUIRE -> {
                    HotPathMetrics.active.incrementAndGet()
                    HotPathMetrics.availablePermits.set(semaphore.availablePermits())

                    call.response.pipeline.intercept(ApplicationSendPipeline.Engine) {
                        try {
                            proceed()
                        } finally {
                            semaphore.release()
                            HotPathMetrics.active.decrementAndGet()
                            HotPathMetrics.availablePermits.set(semaphore.availablePermits())
                        }
                    }
                }
            }
        }
    }

/**
 * Утилита для регистрации плагина с ENV/дефолтами.
 */
fun Application.installHotPathLimiterDefaults() {
    val defaults =
        listOf(
            "/webhook",
            "/api/bookings/confirm",
            "/api/guest-lists/import",
        )
    install(HotPathLimiter) {
        pathPrefixes = defaults
        maxConcurrent = System.getenv("HOT_PATH_MAX_CONCURRENT")?.toIntOrNull()
            ?: Runtime.getRuntime()
                .availableProcessors()
                .coerceAtLeast(BotLimits.RateLimit.HOT_PATH_MIN_ENV_PARALLELISM)
        retryAfter =
            System.getenv("HOT_PATH_RETRY_AFTER_SEC")?.toLongOrNull()?.let(Duration::ofSeconds)
                ?: BotLimits.RateLimit.HOT_PATH_DEFAULT_RETRY_AFTER
    }
}
