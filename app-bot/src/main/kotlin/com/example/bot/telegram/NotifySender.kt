package com.example.bot.telegram

import com.example.bot.notifications.RatePolicy
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.request.InputMediaPhoto
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMediaGroup
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.SendPhoto
import com.pengrad.telegrambot.response.BaseResponse
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.min
import kotlinx.coroutines.delay

sealed interface SendResult {
    data class Ok(val messageId: Long?, val already: Boolean = false) : SendResult
    data class RetryAfter(val retryAfterMs: Long) : SendResult
    data class RetryableError(val message: String) : SendResult
    data class PermanentError(val message: String) : SendResult
}

data class MediaSpec(
    val fileIdOrUrl: String,
    val caption: String? = null,
)

object NotifyMetrics {
    val ok: AtomicLong = AtomicLong()
    val retryAfter: AtomicLong = AtomicLong()
    val retryable: AtomicLong = AtomicLong()
    val permanent: AtomicLong = AtomicLong()
}

class NotifySender(
    private val bot: TelegramBot,
    private val ratePolicy: RatePolicy,
    private val idempotency: NotifyIdempotencyStore = InMemoryNotifyIdempotencyStore(),
    private val registry: MeterRegistry? = null,
    private val baseBackoffMs: Long = 500,
    private val maxBackoffMs: Long = 15_000,
    private val jitterMs: Long = 100,
) {
    private val timer: Timer? = registry?.let {
        Timer.builder("tg.send.duration.ms").publishPercentiles(0.5, 0.95).register(it)
    }

    suspend fun sendMessage(
        chatId: Long,
        text: String,
        threadId: Int? = null,
        dedupKey: String? = null,
    ): SendResult {
        val req = SendMessage(chatId, text)
        threadId?.let { req.messageThreadId(it) }
        return execute(req, chatId, dedupKey)
    }

    suspend fun sendPhoto(
        chatId: Long,
        photoUrlOrFileId: String,
        caption: String? = null,
        threadId: Int? = null,
        dedupKey: String? = null,
    ): SendResult {
        val req = SendPhoto(chatId, photoUrlOrFileId)
        caption?.let { req.caption(it) }
        threadId?.let { req.messageThreadId(it) }
        return execute(req, chatId, dedupKey)
    }

    suspend fun sendMediaGroup(
        chatId: Long,
        media: List<MediaSpec>,
        threadId: Int? = null,
        dedupKey: String? = null,
    ): SendResult {
        val arr = media.map { m ->
            val im = InputMediaPhoto(m.fileIdOrUrl)
            m.caption?.let { im.caption(it) }
            im
        }.toTypedArray()
        val req = SendMediaGroup(chatId, *arr)
        if (threadId != null) {
            try {
                SendMediaGroup::class.java.getMethod("messageThreadId", Int::class.javaPrimitiveType).invoke(req, threadId)
            } catch (_: Throwable) {
                // ignore
            }
        }
        val res = execute(req, chatId, dedupKey)
        return when (res) {
            is SendResult.Ok -> res
            is SendResult.RetryAfter -> res
            is SendResult.RetryableError, is SendResult.PermanentError -> {
                for (m in media) {
                    val r = sendPhoto(chatId, m.fileIdOrUrl, m.caption, threadId, null)
                    if (r !is SendResult.Ok) return r
                }
                SendResult.Ok(messageId = null)
            }
        }
    }

    suspend fun <R : BaseResponse> execute(
        request: BaseRequest<*, R>,
        chatId: Long,
        dedupKey: String? = null,
    ): SendResult {
        if (dedupKey != null && idempotency.seen(dedupKey)) {
            incOk(already = true)
            return SendResult.Ok(messageId = null, already = true)
        }

        var attempt = 0
        val maxAttempts = 3
        while (true) {
            val now = System.currentTimeMillis()
            val g = ratePolicy.acquireGlobal(now = now)
            if (!g.granted) {
                incRetryAfter(g.retryAfterMs)
                return SendResult.RetryAfter(g.retryAfterMs)
            }
            val c = ratePolicy.acquireChat(chatId, now = now)
            if (!c.granted) {
                incRetryAfter(c.retryAfterMs)
                return SendResult.RetryAfter(c.retryAfterMs)
            }

            val start = System.nanoTime()
            val resp = try {
                bot.execute(request)
            } catch (t: Throwable) {
                timer?.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
                if (attempt >= maxAttempts) {
                    incRetryable()
                    return SendResult.RetryableError("IO error: ${t.message}")
                }
                delay(backoffDelay(attempt))
                attempt++
                continue
            }
            timer?.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)

            if (resp.isOk) {
                if (dedupKey != null) idempotency.mark(dedupKey)
                incOk(already = false)
                return SendResult.Ok(messageId = null)
            }

            val code = resp.errorCode()
            val desc = resp.description() ?: "unknown"
            val retryAfterSec = resp.parameters()?.retryAfter()
            when {
                code == 429 || (retryAfterSec != null && retryAfterSec > 0) -> {
                    val retryMs = ((retryAfterSec ?: 1)).toLong() * 1000L
                    ratePolicy.on429(chatId, retryMs)
                    incRetryAfter(retryMs)
                    return SendResult.RetryAfter(retryMs)
                }
                code in 500..599 -> {
                    if (attempt >= maxAttempts) {
                        incRetryable()
                        return SendResult.RetryableError("code=$code desc=$desc")
                    }
                    delay(backoffDelay(attempt))
                    attempt++
                    continue
                }
                code == 400 || code == 403 -> {
                    incPermanent()
                    return SendResult.PermanentError("code=$code desc=$desc")
                }
                else -> {
                    incPermanent()
                    return SendResult.PermanentError("code=$code desc=$desc")
                }
            }
        }
    }

    private fun backoffDelay(attempt: Int): Long {
        val exp = 1L shl attempt.coerceAtMost(20)
        val base = baseBackoffMs * exp
        val capped = min(base, maxBackoffMs)
        val jitter = ThreadLocalRandom.current().nextLong(jitterMs + 1)
        return capped + jitter
    }

    private fun incOk(already: Boolean) {
        registry?.counter("tg.send.ok", "already", already.toString())?.increment()
            ?: NotifyMetrics.ok.incrementAndGet()
    }

    private fun incRetryAfter(retryAfterMs: Long) {
        val sec = ceil(retryAfterMs / 1000.0).toLong().toString()
        registry?.counter("tg.send.retry_after", "retry_after_seconds", sec)?.increment()
            ?: NotifyMetrics.retryAfter.incrementAndGet()
    }

    private fun incRetryable() {
        registry?.counter("tg.send.retryable")?.increment()
            ?: NotifyMetrics.retryable.incrementAndGet()
    }

    private fun incPermanent() {
        registry?.counter("tg.send.permanent")?.increment()
            ?: NotifyMetrics.permanent.incrementAndGet()
    }
}

