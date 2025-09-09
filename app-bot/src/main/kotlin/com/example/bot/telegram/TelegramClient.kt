package com.example.bot.telegram

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.DeleteWebhook
import com.pengrad.telegrambot.request.GetUpdates
import com.pengrad.telegrambot.request.GetWebhookInfo
import com.pengrad.telegrambot.request.SetWebhook
import com.pengrad.telegrambot.response.BaseResponse
import com.pengrad.telegrambot.response.GetWebhookInfoResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Abstraction over the Telegram Bot API client based on pengrad implementation.
 */
class TelegramClient(token: String, apiUrl: String? = null) {
    private val bot: TelegramBot =
        TelegramBot
            .Builder(token)
            .apply {
                if (apiUrl != null) apiUrl(apiUrl)
            }.build()

    suspend fun send(request: Any): BaseResponse = withContext(Dispatchers.IO) {
        @Suppress("UNCHECKED_CAST")
        bot.execute(request as BaseRequest<*, *>) as BaseResponse
    }

    suspend fun setWebhook(
        url: String,
        secret: String,
        maxConnections: Int,
        allowedUpdates: List<String>,
    ): BaseResponse = withContext(Dispatchers.IO) {
        bot.execute(
            SetWebhook()
                .url(url)
                .secretToken(secret)
                .maxConnections(maxConnections)
                .allowedUpdates(*allowedUpdates.toTypedArray()),
        )
    }

    suspend fun deleteWebhook(dropPending: Boolean): BaseResponse = withContext(Dispatchers.IO) {
        bot.execute(DeleteWebhook().dropPendingUpdates(dropPending))
    }

    suspend fun getWebhookInfo(): GetWebhookInfoResponse = withContext(Dispatchers.IO) {
        bot.execute(GetWebhookInfo())
    }

    suspend fun getUpdates(offset: Long, allowedUpdates: List<String>): List<Update> = withContext(Dispatchers.IO) {
        val resp =
            bot.execute(
                GetUpdates()
                    .offset(offset.toInt())
                    .allowedUpdates(*allowedUpdates.toTypedArray()),
            )
        resp.updates().toList()
    }
}
