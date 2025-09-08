package com.example.bot.workers

import com.example.bot.data.repo.OutboxRepository
import com.example.bot.notifications.NotifyMethod
import com.example.bot.notifications.NotifyMessage
import com.example.bot.notifications.ParseMode
import com.example.bot.telegram.NotifySender
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.Keyboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import kotlin.math.pow

/** Simple interface for token buckets. */
interface TokenBucket {
    suspend fun take()
}

class OutboxWorker(
    private val scope: CoroutineScope,
    private val repo: OutboxRepository,
    private val sender: NotifySender,
    private val globalBucket: TokenBucket,
    private val chatBucket: (Long) -> TokenBucket,
    private val workers: Int,
    private val batchSize: Int = 10,
) {

    fun start() {
        repeat(workers) {
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
        rec.dedupKey?.let {
            if (repo.isSent(it)) {
                repo.markSent(rec.id, null)
                return
            }
        }
        globalBucket.take()
        chatBucket(msg.chatId).take()

        val result = when (msg.method) {
            NotifyMethod.TEXT -> sender.sendMessage(
                msg.chatId,
                msg.text.orEmpty(),
                toTelegramParseMode(msg.parseMode),
                msg.messageThreadId,
                toKeyboard(msg),
            )
            NotifyMethod.PHOTO -> sender.sendPhoto(
                msg.chatId,
                NotifySender.PhotoContent.Url(requireNotNull(msg.photoUrl)),
                msg.text,
                toTelegramParseMode(msg.parseMode),
                msg.messageThreadId,
            )
            NotifyMethod.ALBUM -> {
                val media = msg.album.orEmpty().map {
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
            NotifySender.Result.Ok -> repo.markSent(rec.id, null)
            is NotifySender.Result.RetryAfter -> {
                val next = OffsetDateTime.now().plusSeconds(result.seconds.toLong())
                repo.markFailed(rec.id, "retry_after", next)
            }
            is NotifySender.Result.Failed -> {
                val delaySec = 2.0.pow(rec.attempts.toDouble()).toLong().coerceAtLeast(1)
                val next = OffsetDateTime.now().plusSeconds(delaySec)
                repo.markFailed(rec.id, result.description, next)
            }
        }
    }

    private fun toTelegramParseMode(pm: ParseMode?): com.pengrad.telegrambot.model.request.ParseMode? =
        when (pm) {
            ParseMode.MARKDOWNV2 -> com.pengrad.telegrambot.model.request.ParseMode.MarkdownV2
            ParseMode.HTML -> com.pengrad.telegrambot.model.request.ParseMode.HTML
            null -> null
        }

    private fun toKeyboard(msg: NotifyMessage): Keyboard? = msg.buttons?.let { spec ->
        val rows = spec.rows.map { row ->
            row.map { text -> InlineKeyboardButton(text).callbackData(text) }.toTypedArray()
        }.toTypedArray()
        InlineKeyboardMarkup(*rows)
    }
}

