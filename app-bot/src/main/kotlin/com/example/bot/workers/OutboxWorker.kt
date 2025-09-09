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

class OutboxWorker(
    private val scope: CoroutineScope,
    private val repo: OutboxRepository,
    private val sender: NotifySender,
    private val ratePolicy: RatePolicy,
    private val config: NotifyConfig,
    private val registry: MeterRegistry = Telemetry.registry,
    private val batchSize: Int = 10,
) {
    fun start() {
        repeat(config.workerParallelism) {
            scope.launch { run() }
        }
    }

    private suspend fun run() {
        while (scope.isActive) {
            val batch = repo.pickBatch(OffsetDateTime.now(), batchSize)
            if (batch.isEmpty()) {
                delay(1000)
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
        rec.dedupKey?.let {
            if (repo.isSent(it)) {
                repo.markSent(rec.id, null)
                return
            }
        }
        val g = ratePolicy.acquireGlobal(now = nowMs)
        if (!g.granted) {
            repo.postpone(rec.id, OffsetDateTime.now().plusNanos(g.retryAfterMs * 1_000_000))
            registry.counter("notify.rate.throttled.global").increment()
            registry.counter("notify.retried").increment()
            return
        }
        val c = ratePolicy.acquireChat(msg.chatId, now = nowMs)
        if (!c.granted) {
            repo.postpone(rec.id, OffsetDateTime.now().plusNanos(c.retryAfterMs * 1_000_000))
            registry.counter("notify.rate.throttled.chat").increment()
            registry.counter("notify.retried").increment()
            return
        }

        val result =
            when (msg.method) {
                NotifyMethod.TEXT ->
                    sender.sendMessage(
                        msg.chatId,
                        msg.text.orEmpty(),
                        toTelegramParseMode(msg.parseMode),
                        msg.messageThreadId,
                        toKeyboard(msg),
                    )
                NotifyMethod.PHOTO ->
                    sender.sendPhoto(
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
                    sender.sendMediaGroup(msg.chatId, media, msg.messageThreadId)
                }
            }

        when (result) {
            NotifySender.Result.Ok -> {
                repo.markSent(rec.id, null)
                registry
                    .counter(
                        "notify.sent",
                        "method",
                        msg.method.name,
                        "threaded",
                        (msg.messageThreadId != null).toString(),
                    ).increment()
            }
            is NotifySender.Result.RetryAfter -> {
                val delayMs = result.seconds * 1000L
                ratePolicy.on429(msg.chatId, delayMs)
                registry.summary("notify.retry_after.ms").record(delayMs.toDouble())
                repo.markFailed(rec.id, "429", OffsetDateTime.now().plusNanos(delayMs * 1_000_000))
                registry.counter("notify.failed", "code", "429", "retryable", "true").increment()
                registry.counter("notify.retried").increment()
            }
            is NotifySender.Result.Failed -> {
                if (result.code == 400 || result.code == 403) {
                    repo.markPermanentFailure(rec.id, result.description)
                    registry
                        .counter(
                            "notify.failed",
                            "code",
                            result.code.toString(),
                            "retryable",
                            "false",
                        ).increment()
                } else {
                    val delayMs = (config.retryBaseMs * (1L shl rec.attempts)).coerceAtMost(config.retryMaxMs)
                    repo.markFailed(rec.id, result.description, OffsetDateTime.now().plusNanos(delayMs * 1_000_000))
                    registry
                        .counter(
                            "notify.failed",
                            "code",
                            result.code.toString(),
                            "retryable",
                            "true",
                        ).increment()
                    registry.counter("notify.retried").increment()
                }
            }
        }
    }

    private fun toTelegramParseMode(pm: ParseMode?): com.pengrad.telegrambot.model.request.ParseMode? {
        return when (pm) {
            ParseMode.MARKDOWNV2 -> com.pengrad.telegrambot.model.request.ParseMode.MarkdownV2
            ParseMode.HTML -> com.pengrad.telegrambot.model.request.ParseMode.HTML
            null -> null
        }
    }

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
