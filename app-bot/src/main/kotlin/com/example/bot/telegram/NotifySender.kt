package com.example.bot.telegram

import com.example.bot.telemetry.Telemetry
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.request.InputMediaPhoto
import com.pengrad.telegrambot.model.request.Keyboard
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMediaGroup
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.SendPhoto
import com.pengrad.telegrambot.response.BaseResponse
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private const val HTTP_BAD_REQUEST = 400
private const val VISIBLE_TAIL = 3

class NotifySender(private val bot: TelegramBot, private val registry: MeterRegistry = Telemetry.registry) {

    sealed interface Result {
        data object Ok : Result

        data class RetryAfter(val seconds: Int) : Result

        data class Failed(val code: Int, val description: String?) : Result
    }

    sealed interface PhotoContent {
        data class Url(val url: String) : PhotoContent

        data class Bytes(val bytes: ByteArray) : PhotoContent
    }

    data class Media(val content: PhotoContent, val caption: String? = null, val parseMode: ParseMode? = null)

    private val log = LoggerFactory.getLogger(NotifySender::class.java)

    suspend fun sendMessage(
        chatId: Long,
        text: String,
        parseMode: ParseMode? = null,
        threadId: Int? = null,
        buttons: Keyboard? = null,
    ): Result {
        val request = SendMessage(chatId, text)
        parseMode?.let { request.parseMode(it) }
        threadId?.let { request.messageThreadId(it) }
        buttons?.let { request.replyMarkup(it) }
        return execute(chatId, request)
    }

    suspend fun sendPhoto(
        chatId: Long,
        photo: PhotoContent,
        caption: String? = null,
        parseMode: ParseMode? = null,
        threadId: Int? = null,
    ): Result {
        val request =
            when (photo) {
                is PhotoContent.Url -> SendPhoto(chatId, photo.url)
                is PhotoContent.Bytes -> SendPhoto(chatId, photo.bytes)
            }
        caption?.let { request.caption(it) }
        parseMode?.let { request.parseMode(it) }
        threadId?.let { request.messageThreadId(it) }
        return execute(chatId, request)
    }

    @Suppress("SpreadOperator")
    suspend fun sendMediaGroup(chatId: Long, media: List<Media>, threadId: Int? = null): Result {
        val inputMedia =
            media.map { m ->
                val im =
                    when (m.content) {
                        is PhotoContent.Url -> InputMediaPhoto(m.content.url)
                        is PhotoContent.Bytes -> InputMediaPhoto(m.content.bytes)
                    }
                m.caption?.let { im.caption(it) }
                m.parseMode?.let { im.parseMode(it) }
                im
            }
        val request = SendMediaGroup(chatId, *inputMedia.toTypedArray())
        threadId?.let { request.messageThreadId(it) }
        val result = execute(chatId, request)
        if (shouldFallback(result, threadId)) {
            log.info("SendMediaGroup unsupported, fallback to sequential sendPhoto for chat {}", mask(chatId))
            var finalResult: Result = Result.Ok
            for (m in media) {
                val r = sendPhoto(chatId, m.content, m.caption, m.parseMode, threadId)
                if (r !is Result.Ok) {
                    finalResult = r
                    break
                }
            }
            return finalResult
        }
        return result
    }

    private val sendTimer: Timer = registry.timer("notify.send.ms")

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T : BaseRequest<T, R>, R : BaseResponse> execute(
        chatId: Long,
        request: BaseRequest<T, R>,
    ): Result =
        withContext(Dispatchers.IO) {
            val sample = Timer.start(registry)
            try {
                val resp = bot.execute(request)
                when {
                    resp.isOk -> Result.Ok
                    resp.errorCode() == 429 -> {
                        val retry = resp.parameters()?.retryAfter()
                        if (retry != null) Result.RetryAfter(retry) else Result.Failed(429, resp.description())
                    }
                    else -> Result.Failed(resp.errorCode(), resp.description())
                }
            } catch (t: Exception) {
                log.warn("Failed to send to chat {}: {}", mask(chatId), t.message)
                Result.Failed(-1, t.message)
            } finally {
                sample.stop(sendTimer)
            }
        }

    private fun mask(id: Long): String {
        val s = id.toString()
        val hidden = (s.length - VISIBLE_TAIL).coerceAtLeast(0)
        return "*".repeat(hidden) + s.takeLast(VISIBLE_TAIL)
    }

    private fun shouldFallback(result: Result, threadId: Int?): Boolean =
        result is Result.Failed &&
            result.code == HTTP_BAD_REQUEST &&
            threadId != null &&
            (result.description?.contains("thread", true) == true)
}
