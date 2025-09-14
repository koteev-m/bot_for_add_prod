package com.example.bot.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.ApplicationSendPipeline
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
    var maxConcurrent: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

    /**
     * Заголовок с информацией о лимитах (например, Retry-After).
     */
    var throttlingHeader: String = "Retry-After"

    /**
     * Значение Retry-After (секунды) при отказе.
     */
    var retryAfterSeconds: Int = 1
}

/**
 * Простые метрики плагина (без привязки к конкретному реестру; можно подключить к Micrometer извне).
 */
object HotPathMetrics {
    val active = AtomicInteger(0)
    val throttled = AtomicLong(0)
    val availablePermits = AtomicInteger(0)
}

val HotPathLimiter = createApplicationPlugin(name = "HotPathLimiter", createConfiguration = ::HotPathLimiterConfig) {
    val cfg = pluginConfig
    val semaphore = Semaphore(cfg.maxConcurrent, true)

    onCall { call ->
        val path = call.request.path()

        // Применяем лимит только для указанных префиксов
        val hot = cfg.pathPrefixes.any { prefix -> path.startsWith(prefix) }
        if (!hot) return@onCall

        // Попытка немедленного захвата
        val acquired = semaphore.tryAcquire()
        if (!acquired) {
            HotPathMetrics.throttled.incrementAndGet()
            call.response.header(cfg.throttlingHeader, cfg.retryAfterSeconds.toString())
            call.respondText("Too Many Requests", status = HttpStatusCode.TooManyRequests)
            return@onCall
        }

        try {
            HotPathMetrics.active.incrementAndGet()
            HotPathMetrics.availablePermits.set(semaphore.availablePermits())
        } finally {
            // До перехода к остальной pipeline — ничего
        }

        // Когда ответ будет отправлен, освободим пермит
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

/**
 * Утилита для регистрации плагина с ENV/дефолтами.
 */
fun Application.installHotPathLimiterDefaults() {
    val defaults = listOf(
        "/webhook",
        "/api/bookings/confirm",
        "/api/guest-lists/import"
    )
    install(HotPathLimiter) {
        pathPrefixes = defaults
        maxConcurrent = System.getenv("HOT_PATH_MAX_CONCURRENT")?.toIntOrNull()
            ?: Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
        retryAfterSeconds = System.getenv("HOT_PATH_RETRY_AFTER_SEC")?.toIntOrNull() ?: 1
    }
}
