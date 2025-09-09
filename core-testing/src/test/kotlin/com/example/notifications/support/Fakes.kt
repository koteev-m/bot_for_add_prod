package com.example.notifications.support

import com.example.bot.telegram.NotifySender
import com.pengrad.telegrambot.model.request.Keyboard
import com.pengrad.telegrambot.model.request.ParseMode

/** Simple in-memory fake of [NotifySender] that records sent messages. */
class FakeNotifySender {
    data class Sent(val timestamp: Long, val chatId: Long, val method: String)

    val sent = mutableListOf<Sent>()
    private val scripted = ArrayDeque<NotifySender.Result>()

    fun enqueue(result: NotifySender.Result) {
        scripted.add(result)
    }

    private fun nextResult(): NotifySender.Result = scripted.removeFirstOrNull() ?: NotifySender.Result.Ok

    suspend fun sendMessage(
        chatId: Long,
        text: String,
        parseMode: ParseMode? = null,
        threadId: Int? = null,
        buttons: Keyboard? = null,
    ): NotifySender.Result {
        sent += Sent(System.currentTimeMillis(), chatId, "message")
        return nextResult()
    }

    suspend fun sendPhoto(
        chatId: Long,
        photo: NotifySender.PhotoContent,
        caption: String? = null,
        parseMode: ParseMode? = null,
        threadId: Int? = null,
    ): NotifySender.Result {
        sent += Sent(System.currentTimeMillis(), chatId, "photo")
        return nextResult()
    }

    suspend fun sendMediaGroup(
        chatId: Long,
        media: List<NotifySender.Media>,
        threadId: Int? = null,
    ): NotifySender.Result {
        sent += Sent(System.currentTimeMillis(), chatId, "mediaGroup")
        return nextResult()
    }
}

/** Placeholder utility for seeding outbox messages in tests. */
object TestOutboxSeeder
