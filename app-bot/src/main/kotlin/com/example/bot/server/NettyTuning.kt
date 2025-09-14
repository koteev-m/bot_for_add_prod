package com.example.bot.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.util.pipeline.PipelineContext
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.io.DEFAULT_BUFFER_SIZE
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining

/**
 * Dispatcher для CPU/рендер-операций (ограниченный пул).
 * Размер пула настраивается ENV `CPU_POOL_SIZE` (по умолчанию = кол-во ядер).
 */
object PerfDispatchers {
    val cpu: CoroutineDispatcher by lazy {
        val cores = Runtime.getRuntime().availableProcessors()
        val size = System.getenv("CPU_POOL_SIZE")?.toIntOrNull()?.coerceAtLeast(1) ?: cores
        val tf = ThreadFactory { r ->
            Thread(r, "cpu-dispatcher-${System.nanoTime()}").apply { isDaemon = true }
        }
        Executors.newFixedThreadPool(size, tf).asCoroutineDispatcher()
    }
}

/**
 * Установить безопасные таймауты/лимиты HTTP на уровне приложения.
 * Примечание: engine-специфичные настройки Netty (idle/read/write) обычно задаются в application.conf;
 * здесь — проверка максимального размера запроса и быстрый 413 по Content-Length.
 */
fun Application.installServerTuning(
    maxRequestSizeBytes: Long = System.getenv("MAX_REQUEST_SIZE_BYTES")?.toLongOrNull() ?: 2L * 1024 * 1024 // 2 MiB
) {
    intercept(ApplicationCallPipeline.Setup) {
        val clHeader = call.request.header("Content-Length")
        val length = clHeader?.toLongOrNull()
        if (length != null && length > maxRequestSizeBytes) {
            call.respondText(
                text = "Payload too large",
                status = HttpStatusCode.PayloadTooLarge
            )
            finish()
        }
    }
}

/**
 * Хелпер: безопасно читать тело без блокировок и без переполнения.
 * Если Content-Length отсутствует, можно дополнительно подсчитать фактический размер (при необходимости).
 */
suspend fun PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.safeReceiveBytes(
    maxRequestSizeBytes: Long
): ByteArray? {
    val clHeader = call.request.header("Content-Length")?.toLongOrNull()
    if (clHeader != null && clHeader > maxRequestSizeBytes) {
        call.respondText(
            text = "Payload too large",
            status = HttpStatusCode.PayloadTooLarge
        )
        return null
    }
    // В примере используем Content-Length; подсчёт фактического размера можно добавить при необходимости.
    return withContext(PerfDispatchers.cpu) {
        call.receiveChannel().toByteArray()
    }
}

private suspend fun ByteReadChannel.toByteArray(): ByteArray {
    return readRemaining(DEFAULT_BUFFER_SIZE.toLong()).readBytes()
}

