package com.example.bot.telegram

import com.example.bot.i18n.BotTexts
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse

/**
 * Handles guest flow interactions for Telegram updates.
 */
class GuestFlowHandler(
    private val send: suspend (Any) -> BaseResponse,
    private val texts: BotTexts,
    private val keyboards: Keyboards,
) {
    /**
     * Processes incoming [update] and reacts to supported commands.
     */
    suspend fun handle(update: Update) {
        val msg = update.message() ?: return
        val chatId = msg.chat().id()
        val lang = msg.from()?.languageCode()
        when (msg.text()) {
            "/start" -> {
                val text = texts.greeting(lang)
                val keyboard = keyboards.startMenu(lang)
                send(SendMessage(chatId, text).replyMarkup(keyboard))
            }
        }
    }
}
