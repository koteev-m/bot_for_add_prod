package com.example.bot.workers

import com.example.bot.data.repo.OutboxRepository
import com.example.bot.notifications.NotifyConfig
import com.example.bot.notifications.NotifyMessage
import com.example.bot.notifications.NotifyMethod
import com.example.bot.notifications.RatePolicy
import com.example.bot.telegram.MediaSpec
import com.example.bot.telegram.NotifySender
import com.example.bot.telegram.SendResult
import com.example.bot.telemetry.Telemetry
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

private const val MS: Long = 1_000
private const val NANOS_IN_MS: Long = 1_000_000
private const val EMPTY_BATCH_DELAY_MS: Long = 1 * MS

class OutboxWorker(private val scope: CoroutineScope, private val deps: Deps) {

    data class Deps(
        val repo: OutboxRepository,
        val sender: NotifySender,
        val ratePolicy: RatePolicy,
        val config: NotifyConfig,
        val registry: MeterRegistry = Telemetry.registry,
        val batchSize: Int = 10,
    )

    fun start() {
        repeat(deps.config.workerParallelism) {
            scope.launch { run() }
        }
    }

    private suspend fun run() {
        while (scope.isActive) {
            val batch = deps.repo.pickBatch(OffsetDateTime.now(), deps.batchSize)
            if (batch.isEmpty()) {
                delay(EMPTY_BATCH_DELAY_MS)
                continue
            }
            for (rec in batch) {
                process(rec)
            }
        }
    }

    private suspend fun process(rec: OutboxRepository.Record) {
        val msg = rec.message
        val nowMs = System.currentTimeMillis()
        if (dedup(rec)) return
        if (!checkRateLimits(rec, msg, nowMs)) return
        val result = send(msg)
        handleResult(rec, msg, result)
    }

    private suspend fun dedup(rec: OutboxRepository.Record): Boolean {
        rec.dedupKey?.let {
            if (deps.repo.isSent(it)) {
                deps.repo.markSent(rec.id, null)
                return true
            }
        }
        return false
    }

    private suspend fun checkRateLimits(rec: OutboxRepository.Record, msg: NotifyMessage, nowMs: Long): Boolean {
        val g = deps.ratePolicy.acquireGlobal(now = nowMs)
        return if (!g.granted) {
            deps.repo.postpone(rec.id, OffsetDateTime.now().plusNanos(g.retryAfterMs * NANOS_IN_MS))
            deps.registry.counter("notify.rate.throttled.global").increment()
            deps.registry.counter("notify.retried").increment()
            false
        } else {
            val c = deps.ratePolicy.acquireChat(msg.chatId, now = nowMs)
            if (!c.granted) {
                deps.repo.postpone(rec.id, OffsetDateTime.now().plusNanos(c.retryAfterMs * NANOS_IN_MS))
                deps.registry.counter("notify.rate.throttled.chat").increment()
                deps.registry.counter("notify.retried").increment()
                false
            } else {
                true
            }
        }
    }

    private suspend fun send(msg: NotifyMessage): SendResult {
        return when (msg.method) {
            NotifyMethod.TEXT ->
                deps.sender.sendMessage(
                    msg.chatId,
                    msg.text.orEmpty(),
                    msg.messageThreadId,
                    msg.dedupKey,
                )

            NotifyMethod.PHOTO ->
                deps.sender.sendPhoto(
                    msg.chatId,
                    requireNotNull(msg.photoUrl),
                    msg.text,
                    msg.messageThreadId,
                    msg.dedupKey,
                )

            NotifyMethod.ALBUM -> {
                val media =
                    msg.album.orEmpty().map {
                        MediaSpec(
                            fileIdOrUrl = it.url,
                            caption = it.caption,
                        )
                    }
                deps.sender.sendMediaGroup(msg.chatId, media, msg.messageThreadId, msg.dedupKey)
            }
        }
    }

    private suspend fun handleResult(rec: OutboxRepository.Record, msg: NotifyMessage, result: SendResult) {
        when (result) {
            is SendResult.Ok -> {
                deps.repo.markSent(rec.id, result.messageId)
                deps.registry
                    .counter(
                        "notify.sent",
                        "method",
                        msg.method.name,
                        "threaded",
                        (msg.messageThreadId != null).toString(),
                    ).increment()
            }

            is SendResult.RetryAfter -> {
                deps.ratePolicy.on429(msg.chatId, result.retryAfterMs)
                deps.registry.summary("notify.retry_after.ms").record(result.retryAfterMs.toDouble())
                deps.repo.markFailed(
                    rec.id,
                    "429",
                    OffsetDateTime.now().plusNanos(result.retryAfterMs * NANOS_IN_MS),
                )
                deps.registry.counter("notify.failed", "code", "429", "retryable", "true").increment()
                deps.registry.counter("notify.retried").increment()
            }

            is SendResult.RetryableError -> {
                val delayMs = backoff(rec.attempts)
                deps.repo.markFailed(
                    rec.id,
                    result.message,
                    OffsetDateTime.now().plusNanos(delayMs * NANOS_IN_MS),
                )
                deps.registry
                    .counter(
                        "notify.failed",
                        "code",
                        "RETRYABLE",
                        "retryable",
                        "true",
                    ).increment()
                deps.registry.counter("notify.retried").increment()
            }

            is SendResult.PermanentError -> {
                deps.repo.markPermanentFailure(rec.id, result.message)
                deps.registry
                    .counter(
                        "notify.failed",
                        "code",
                        "PERMANENT",
                        "retryable",
                        "false",
                    ).increment()
            }
        }
    }

    private fun backoff(attempts: Int): Long =
        (deps.config.retryBaseMs * (1L shl attempts)).coerceAtMost(deps.config.retryMaxMs)
}

