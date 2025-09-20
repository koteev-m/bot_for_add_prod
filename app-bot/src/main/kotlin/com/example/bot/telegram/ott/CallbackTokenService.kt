package com.example.bot.telegram.ott

import com.example.bot.telegram.ott.TemplateOttPayload
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.SendMessage

/**
 * Сервис, который:
 *  - выдаёт одноразовые токены под payload;
 *  - потребляет токен в handler’е и возвращает payload;
 *  - умеет быстро отвечать пользователю при истёкших/повторных токенах.
 */
class CallbackTokenService(
    private val store: OneTimeTokenStore = InMemoryOneTimeTokenStore()
) {

    /** Выдать токен под payload (для callback_data). */
    fun issueToken(payload: OttPayload): String = store.issue(payload)

    /** Атомарно потребить токен: вернуть payload или null, если повтор/истёк. */
    fun consume(token: String): OttPayload? = store.consume(token)
}

/** Мини-handler `callback_query` с примером ветвления по payload. */
class CallbackQueryHandler(
    private val bot: TelegramBot,
    private val tokenService: CallbackTokenService
) {

    fun handle(update: Update) {
        val callbackQuery: CallbackQuery? = update.callbackQuery()
        val token: String? = callbackQuery?.data()
        val payload: OttPayload? = token?.let(tokenService::consume)

        return when {
            callbackQuery == null -> Unit
            token == null -> Unit
            payload == null -> {
                // устарело/повтор — показываем alert, ничего не делаем
                bot.execute(
                    AnswerCallbackQuery(callbackQuery.id())
                        .text("Кнопка устарела, обновите экран.")
                        .showAlert(true)
                )
                Unit
            }
            else -> {
                when (payload) {
                    is BookTableAction -> handleBookTable(callbackQuery, payload)
                    is TemplateOttPayload -> {
                        bot.execute(AnswerCallbackQuery(callbackQuery.id()))
                        Unit
                    }
                }
            }
        }
    }

    private fun handleBookTable(cq: CallbackQuery, p: BookTableAction) {
        // Пример: отправим подтверждение в чат (минимальный сценарий)
        val chatId = cq.message()?.chat()?.id() ?: return
        val text = "Выбран стол #${'$'}{p.tableId} • клуб ${'$'}{p.clubId} • ночь ${'$'}{p.startUtc}"
        val req = SendMessage(chatId, text)
        // Если callback был в теме — можно добавить message_thread_id (не всегда доступно из callback)
        bot.execute(req)
        // Закрыть "часики" на кнопке
        bot.execute(AnswerCallbackQuery(cq.id()))
    }
}

