package com.example.bot.telegram

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.SendMessage
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
                data == "menu:clubs" && chatId != null -> {
                    val request = SendMessage(chatId, "Выбор клуба…")
                    threadId?.let { request.messageThreadId(it) }
                    bot.execute(request)
                }

                else -> Unit
            }
        } finally {
            bot.execute(AnswerCallbackQuery(callbackQuery.id()))
        }
    }
}

private const val DELIMITER = ":"
