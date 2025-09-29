package com.example.bot.telegram.ott

import com.example.bot.telegram.MenuCallbacksHandler
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
    private val store: OneTimeTokenStore = InMemoryOneTimeTokenStore(),
) {
    /** Выдать токен под payload (для callback_data). */
    fun issueToken(payload: OttPayload): String = store.issue(payload)

    /** Атомарно потребить токен: вернуть payload или null, если повтор/истёк. */
    fun consume(token: String): OttPayload? = store.consume(token)
}

/** Мини-handler `callback_query` с примером ветвления по payload. */
class CallbackQueryHandler(
    private val bot: TelegramBot,
    private val tokenService: CallbackTokenService,
    private val menuCallbacksHandler: MenuCallbacksHandler,
) {
    fun handle(update: Update) {
        val callbackQuery: CallbackQuery = update.callbackQuery() ?: return
        val data: String = callbackQuery.data() ?: return

        if (isMenuCallback(data)) {
            menuCallbacksHandler.handle(update)
        } else {
            val payload: OttPayload? = tokenService.consume(data)
            if (payload == null) {
                // устарело/повтор — показываем alert, ничего не делаем
                bot.execute(
                    AnswerCallbackQuery(callbackQuery.id())
                        .text("Кнопка устарела, обновите экран.")
                        .showAlert(true),
                )
            } else {
                when (payload) {
                    is BookTableAction -> handleBookTable(callbackQuery, payload)
                    is TemplateOttPayload -> {
                        // подтверждаем, чтобы убрать "часики"
                        bot.execute(AnswerCallbackQuery(callbackQuery.id()))
                    }
                }
            }
        }
    }

    private fun isMenuCallback(data: String): Boolean {
        return MENU_PREFIXES.any { prefix -> data.startsWith(prefix) }
    }

    private fun handleBookTable(
        cq: CallbackQuery,
        p: BookTableAction,
    ) {
        // Пример: отправим подтверждение в чат (минимальный сценарий)
        val (chatId, threadId) = extractChatAndThread(cq)
        if (chatId == null) return

        val text = "Выбран стол #${p.tableId} • клуб ${p.clubId} • ночь ${p.startUtc}"
        val req = SendMessage(chatId, text)
        threadId?.let { req.messageThreadId(it) }
        bot.execute(req)

        // Закрыть "часики" на кнопке
        bot.execute(AnswerCallbackQuery(cq.id()))
    }
}

private const val NOOP_CALLBACK = "noop"
private val MENU_PREFIXES =
    listOf(
        "menu:",
        "club:",
        "night:",
        "tbl:",
        "pg:",
        "g:",
        NOOP_CALLBACK,
    )

/**
 * Единая точка использования устаревшего Java-метода pengrad.
 * Подавляем депрекацию локально, чтобы не размазывать по коду.
 */
@Suppress("DEPRECATION") // pengrad: CallbackQuery.message() помечен deprecated в Java-API
private fun extractChatAndThread(cq: CallbackQuery): Pair<Long?, Int?> {
    val msg = cq.message() ?: return null to null
    val chatId = msg.chat()?.id()
    val threadId =
        try {
            msg.messageThreadId()
        } catch (_: Throwable) {
            null
        }
    return chatId to threadId
}
