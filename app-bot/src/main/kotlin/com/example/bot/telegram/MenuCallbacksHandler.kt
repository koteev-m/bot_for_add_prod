package com.example.bot.telegram

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.SendMessage
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * Handles callback-based navigation for inline menus.
 */
class MenuCallbacksHandler(
    private val bot: TelegramBot,
) {
    private val logger = LoggerFactory.getLogger(MenuCallbacksHandler::class.java)

    fun handle(update: Update) {
        val callbackQuery = update.callbackQuery() ?: return
        val data = callbackQuery.data() ?: return
        val route = data.substringBefore(DELIMITER, data)
        logger.debug("Handling {} callback", route)

        val message = callbackQuery.message()
        val chatId = message?.chat()?.id()
        val threadId = message?.messageThreadId()

        try {
            when {
                data == MENU_CLUBS && chatId != null -> {
                    val request = SendMessage(chatId, "Выбор клуба…")
                    threadId?.let { request.messageThreadId(it) }
                    bot.execute(request)
                }

                data.startsWith(CLUB_PREFIX) ->
                    handleClubSelection(data.removePrefix(CLUB_PREFIX), chatId, threadId)

                data.startsWith(NIGHT_PREFIX) ->
                    handleNightSelection(data.removePrefix(NIGHT_PREFIX), chatId, threadId)

                else -> Unit
            }
        } finally {
            bot.execute(AnswerCallbackQuery(callbackQuery.id()))
        }
    }

    private fun handleClubSelection(token: String, chatId: Long?, threadId: Int?) {
        val clubId = ClubTokenCodec.decode(token)
        if (clubId == null) {
            logger.warn("Ignoring malformed club token: {}", token)
            return
        }
        logger.debug("Resolved club token {} to id {}", token, clubId)
        if (chatId != null) {
            val request = SendMessage(chatId, "Выбран клуб #$clubId")
            threadId?.let { request.messageThreadId(it) }
            bot.execute(request)
        }
    }

    private fun handleNightSelection(token: String, chatId: Long?, threadId: Int?) {
        val decoded = NightTokenCodec.decode(token)
        if (decoded == null) {
            logger.warn("Ignoring malformed night token: {}", token)
            return
        }
        val (clubId, startUtc) = decoded
        logger.debug("Resolved night token {} to club {} at {}", token, clubId, startUtc)
        if (chatId != null) {
            val request = SendMessage(chatId, "Ночь клуба #$clubId · $startUtc")
            threadId?.let { request.messageThreadId(it) }
            bot.execute(request)
        }
    }

    companion object {
        fun clubCallbackData(clubId: Long): String = CLUB_PREFIX + ClubTokenCodec.encode(clubId)

        fun nightCallbackData(clubId: Long, startUtc: Instant): String =
            NIGHT_PREFIX + NightTokenCodec.encode(clubId, startUtc)
    }
}

private const val DELIMITER = ":"
private const val MENU_CLUBS = "menu:clubs"
private const val CLUB_PREFIX = "club:"
private const val NIGHT_PREFIX = "night:"
