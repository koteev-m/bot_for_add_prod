package com.example.bot.workers

import com.example.bot.data.repo.OutboxRepository
import com.example.bot.notifications.NotifyConfig
import com.example.bot.notifications.NotifyMessage
import com.example.bot.notifications.NotifyMethod
import com.example.bot.notifications.ParseMode
import com.example.bot.notifications.RatePolicy
import com.example.bot.telegram.NotifySender
import com.example.bot.telemetry.Telemetry
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.Keyboard
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

private const val MS: Long = 1_000
private const val NANOS_IN_MS: Long = 1_000_000
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_FORBIDDEN = 403
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

    private suspend fun send(msg: NotifyMessage): NotifySender.Result {
        return when (msg.method) {
            NotifyMethod.TEXT ->
                deps.sender.sendMessage(
                    msg.chatId,
                    msg.text.orEmpty(),
                    toTelegramParseMode(msg.parseMode),
                    msg.messageThreadId,
                    toKeyboard(msg),
                )

            NotifyMethod.PHOTO ->
                deps.sender.sendPhoto(
                    msg.chatId,
                    NotifySender.PhotoContent.Url(requireNotNull(msg.photoUrl)),
                    msg.text,
                    toTelegramParseMode(msg.parseMode),
                    msg.messageThreadId,
                )

            NotifyMethod.ALBUM -> {
                val media =
                    msg.album.orEmpty().map {
                        NotifySender.Media(
                            content = NotifySender.PhotoContent.Url(it.url),
                            caption = it.caption,
                            parseMode = toTelegramParseMode(it.parseMode),
                        )
                    }
                deps.sender.sendMediaGroup(msg.chatId, media, msg.messageThreadId)
            }
        }
    }

    private suspend fun handleResult(rec: OutboxRepository.Record, msg: NotifyMessage, result: NotifySender.Result) {
        when (result) {
            NotifySender.Result.Ok -> {
                deps.repo.markSent(rec.id, null)
                deps.registry
                    .counter(
                        "notify.sent",
                        "method",
                        msg.method.name,
                        "threaded",
                        (msg.messageThreadId != null).toString(),
                    ).increment()
            }

            is NotifySender.Result.RetryAfter -> {
                val delayMs = result.seconds * MS
                deps.ratePolicy.on429(msg.chatId, delayMs)
                deps.registry.summary("notify.retry_after.ms").record(delayMs.toDouble())
                deps.repo.markFailed(
                    rec.id,
                    "429",
                    OffsetDateTime.now().plusNanos(delayMs * NANOS_IN_MS),
                )
                deps.registry.counter("notify.failed", "code", "429", "retryable", "true").increment()
                deps.registry.counter("notify.retried").increment()
            }

            is NotifySender.Result.Failed -> {
                if (result.code == HTTP_BAD_REQUEST || result.code == HTTP_FORBIDDEN) {
                    deps.repo.markPermanentFailure(rec.id, result.description)
                    deps.registry
                        .counter(
                            "notify.failed",
                            "code",
                            result.code.toString(),
                            "retryable",
                            "false",
                        ).increment()
                } else {
                    val delayMs = backoff(rec.attempts)
                    deps.repo.markFailed(
                        rec.id,
                        result.description,
                        OffsetDateTime.now().plusNanos(delayMs * NANOS_IN_MS),
                    )
                    deps.registry
                        .counter(
                            "notify.failed",
                            "code",
                            result.code.toString(),
                            "retryable",
                            "true",
                        ).increment()
                    deps.registry.counter("notify.retried").increment()
                }
            }
        }
    }

    private fun backoff(attempts: Int): Long =
        (deps.config.retryBaseMs * (1L shl attempts)).coerceAtMost(deps.config.retryMaxMs)

    private fun toTelegramParseMode(pm: ParseMode?): com.pengrad.telegrambot.model.request.ParseMode? {
        return when (pm) {
            ParseMode.MARKDOWNV2 -> com.pengrad.telegrambot.model.request.ParseMode.MarkdownV2
            ParseMode.HTML -> com.pengrad.telegrambot.model.request.ParseMode.HTML
            null -> null
        }
    }

    @Suppress("SpreadOperator")
    private fun toKeyboard(msg: NotifyMessage): Keyboard? {
        return msg.buttons?.let { spec ->
            val rows =
                spec.rows
                    .map { row ->
                        row.map { text -> InlineKeyboardButton(text).callbackData(text) }.toTypedArray()
                    }.toTypedArray()
            InlineKeyboardMarkup(*rows)
        }
    }
}
