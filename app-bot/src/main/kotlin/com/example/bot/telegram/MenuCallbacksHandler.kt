package com.example.bot.telegram

import com.example.bot.availability.AvailabilityService
import com.example.bot.availability.NightDto
import com.example.bot.availability.TableAvailabilityDto
import com.example.bot.data.repo.ClubDto
import com.example.bot.data.repo.ClubRepository
import com.example.bot.i18n.BotTexts
import com.example.bot.telegram.tokens.ClubTokenCodec
import com.example.bot.telegram.tokens.DecodedTable
import com.example.bot.telegram.tokens.NightTokenCodec
import com.example.bot.telegram.tokens.TableSelectCodec
import com.example.bot.telegram.ui.ChatUiSessionStore
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.SendMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Handles callback-based navigation for inline menus.
 */
@Suppress("TooManyFunctions")
class MenuCallbacksHandler(
    private val bot: TelegramBot,
    private val keyboards: Keyboards,
    private val texts: BotTexts,
    private val clubRepository: ClubRepository,
    private val availability: AvailabilityService,
    private val chatUiSession: ChatUiSessionStore,
    private val uiScope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(MenuCallbacksHandler::class.java)

    fun handle(update: Update) {
        val callbackQuery: CallbackQuery = update.callbackQuery() ?: return
        val data = callbackQuery.data() ?: return
        val message = callbackQuery.message()
        val chatId = message?.chat()?.id()
        val threadId = message?.messageThreadId()
        val lang = callbackQuery.from()?.languageCode()

        // Stop Telegram's spinner immediately to avoid blocking UI.
        bot.execute(AnswerCallbackQuery(callbackQuery.id()))

        val route = data.substringBefore(DELIMITER, data)
        logger.debug("ui.menu route={}", route)

        when {
            data == MENU_CLUBS && chatId != null ->
                uiScope.launch {
                    val clubs = safeLoadClubs()
                    val text = buildClubSelectionMessage(clubs, lang)
                    val clubButtons = clubs.map { club -> ClubTokenCodec.encode(club.id) to club.name }
                    val markup = keyboards.clubsKeyboard(clubButtons)
                    send(chatId, threadId, text, markup)
                }

            data.startsWith(CLUB_PREFIX) && chatId != null ->
                uiScope.launch {
                    val token = data.removePrefix(CLUB_PREFIX)
                    val clubId = ClubTokenCodec.decode(token)
                    if (clubId == null) {
                        logger.warn("Malformed club token: {}", token)
                        val text =
                            if (isEnglish(lang)) {
                                "We couldn't recognize this club. Please refresh the list."
                            } else {
                                "Не удалось распознать клуб. Обновите список клубов."
                            }
                        send(chatId, threadId, text)
                        return@launch
                    }

                    val nights = safeLoadNights(clubId)
                    if (nights == null) {
                        val text =
                            if (isEnglish(lang)) {
                                "Failed to load nights. Please try again."
                            } else {
                                "Не получилось загрузить ночи. Попробуйте ещё раз."
                            }
                        send(chatId, threadId, text)
                        return@launch
                    }
                    if (nights.isEmpty()) {
                        val text =
                            if (isEnglish(lang)) {
                                "No open nights available right now. Please check back later."
                            } else {
                                "Сейчас нет ночей с открытым бронированием. Загляните позже."
                            }
                        send(chatId, threadId, text)
                        return@launch
                    }

                    val buttons =
                        nights.map { night ->
                            NightTokenCodec.encode(clubId, night.eventStartUtc) to formatNightLabel(night, lang)
                        }
                    val text = buildNightsSelectionMessage(nights, lang)
                    val markup = keyboards.nightsKeyboard(buttons)
                    send(chatId, threadId, text, markup)
                }

            data.startsWith(NIGHT_PREFIX) && chatId != null ->
                uiScope.launch {
                    val token = data.removePrefix(NIGHT_PREFIX)
                    val decoded = NightTokenCodec.decode(token)
                    if (decoded == null) {
                        logger.warn("Malformed night token: {}", token)
                        send(chatId, threadId, texts.sessionExpired(lang), keyboards.startMenu(lang))
                        return@launch
                    }
                    val (clubId, startUtc) = decoded
                    chatUiSession.putNightContext(chatId, threadId, clubId, startUtc)
                    logger.info("ui.night.select clubId={} start={}", clubId, startUtc)
                    renderTablesPage(chatId, threadId, lang, clubId, startUtc, page = 1)
                }

            data.startsWith(PAGE_PREFIX) && chatId != null ->
                uiScope.launch {
                    val pageNumber = data.removePrefix(PAGE_PREFIX).toIntOrNull()
                    if (pageNumber == null) {
                        logger.warn("Malformed page token: {}", data)
                        send(chatId, threadId, texts.sessionExpired(lang), keyboards.startMenu(lang))
                        return@launch
                    }
                    val context = chatUiSession.getNightContext(chatId, threadId)
                    if (context == null) {
                        logger.warn("No night context for chat {} thread {}", chatId, threadId)
                        send(chatId, threadId, texts.sessionExpired(lang), keyboards.startMenu(lang))
                        return@launch
                    }
                    chatUiSession.putNightContext(chatId, threadId, context.clubId, context.startUtc)
                    renderTablesPage(chatId, threadId, lang, context.clubId, context.startUtc, pageNumber)
                }

            data.startsWith(TABLE_PREFIX) && chatId != null ->
                uiScope.launch {
                    val decoded = TableSelectCodec.decode(data)
                    if (decoded == null) {
                        logger.warn("Malformed table token: {}", data)
                        val text =
                            if (isEnglish(lang)) {
                                "The button has expired, please refresh the screen."
                            } else {
                                "Кнопка устарела, обновите экран."
                            }
                        send(chatId, threadId, text)
                        return@launch
                    }
                    val text = buildTableConfirmationMessage(decoded, lang)
                    send(chatId, threadId, text)
                }

            data == NOOP_CALLBACK -> Unit

            else -> Unit
        }
    }

    private suspend fun safeLoadClubs(limit: Int = CLUB_LIST_LIMIT): List<ClubDto> =
        withContext(Dispatchers.IO) {
            try {
                clubRepository.listClubs(limit)
            } catch (ex: Exception) {
                logger.error("Failed to load clubs", ex)
                emptyList()
            }
        }

    private suspend fun safeLoadNights(
        clubId: Long,
        limit: Int = NIGHT_LIST_LIMIT,
    ): List<NightDto>? =
        withContext(Dispatchers.IO) {
            try {
                availability.listOpenNights(clubId, limit)
            } catch (ex: Exception) {
                logger.error("Failed to load nights for club {}", clubId, ex)
                null
            }
        }

    private fun send(
        chatId: Long,
        threadId: Int?,
        text: String,
        markup: InlineKeyboardMarkup? = null,
    ) {
        val request = SendMessage(chatId, text)
        markup?.let { request.replyMarkup(it) }
        threadId?.let { request.messageThreadId(it) }
        bot.execute(request)
    }

    private suspend fun renderTablesPage(
        chatId: Long,
        threadId: Int?,
        lang: String?,
        clubId: Long,
        startUtc: Instant,
        page: Int,
    ) {
        val tables = safeLoadTables(clubId, startUtc)
        val totalTables = tables.size
        if (totalTables == 0) {
            logger.info("ui.tables.page page={} size={} total={}", 1, TABLES_PAGE_SIZE, totalTables)
            send(chatId, threadId, texts.noTables(lang))
            return
        }
        val endUtc = resolveNightEndUtc(clubId, startUtc) ?: startUtc.plus(DEFAULT_NIGHT_DURATION)
        val totalPages = maxOf((totalTables + TABLES_PAGE_SIZE - 1) / TABLES_PAGE_SIZE, 1)
        val targetPage = page.coerceIn(1, totalPages)
        val markup =
            keyboards.tablesKeyboard(tables, targetPage, TABLES_PAGE_SIZE) { dto ->
                TableSelectCodec.encode(clubId, startUtc, endUtc, dto.tableId)
            }
        logger.info("ui.tables.page page={} size={} total={}", targetPage, TABLES_PAGE_SIZE, totalTables)
        send(chatId, threadId, texts.chooseTable(lang), markup)
    }

    private suspend fun safeLoadTables(
        clubId: Long,
        startUtc: Instant,
    ): List<TableAvailabilityDto> =
        withContext(Dispatchers.IO) {
            try {
                availability.listFreeTables(clubId, startUtc)
            } catch (ex: Exception) {
                logger.error("Failed to load tables for club {} start {}", clubId, startUtc, ex)
                emptyList()
            }
        }

    private suspend fun resolveNightEndUtc(
        clubId: Long,
        startUtc: Instant,
    ): Instant? {
        val nights = safeLoadNights(clubId) ?: return null
        return nights.firstOrNull { it.eventStartUtc == startUtc }?.eventEndUtc
    }

    private fun buildTableConfirmationMessage(
        decoded: DecodedTable,
        lang: String?,
    ): String {
        return if (isEnglish(lang)) {
            "Table #${decoded.tableId} selected • club ${decoded.clubId} • night ${decoded.startUtc}"
        } else {
            "Выбран стол #${decoded.tableId} • клуб ${decoded.clubId} • ночь ${decoded.startUtc}"
        }
    }

    private fun buildClubSelectionMessage(
        clubs: List<ClubDto>,
        lang: String?,
    ): String {
        val header = texts.menu(lang).chooseClub
        if (clubs.isEmpty()) return header
        val details =
            clubs.joinToString("\n") { club ->
                buildString {
                    append("• ")
                    append(club.name)
                    val description = club.shortDescription?.takeIf { it.isNotBlank() }
                    if (description != null) {
                        append(" — ")
                        append(description)
                    }
                }
            }
        return "$header\n\n$details"
    }

    private fun buildNightsSelectionMessage(
        nights: List<NightDto>,
        lang: String?,
    ): String {
        val header = if (isEnglish(lang)) "Choose a night:" else "Выберите ночь:"
        if (nights.isEmpty()) return header
        val details = nights.joinToString("\n") { night -> "• ${formatNightLabel(night, lang)}" }
        return "$header\n\n$details"
    }

    private fun formatNightLabel(
        night: NightDto,
        lang: String?,
    ): String {
        val locale = if (isEnglish(lang)) Locale.ENGLISH else RUSSIAN_LOCALE
        val dateFormatter = DateTimeFormatter.ofPattern("d MMM", locale)
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)
        val day =
            night.openLocal.dayOfWeek
                .getDisplayName(TextStyle.SHORT, locale)
                .replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase(locale) else ch.toString() }
        val date = night.openLocal.format(dateFormatter)
        val start = night.openLocal.format(timeFormatter)
        val end = night.closeLocal.format(timeFormatter)
        val base = "$day, $date · $start–$end"
        return if (night.isSpecial) "✨ $base" else base
    }

    private fun isEnglish(lang: String?): Boolean = lang?.startsWith("en", ignoreCase = true) == true

    private companion object {
        private const val DELIMITER = ":"
        private const val MENU_CLUBS = "menu:clubs"
        private const val CLUB_PREFIX = "club:"
        private const val NIGHT_PREFIX = "night:"
        private const val PAGE_PREFIX = "pg:"
        private const val TABLE_PREFIX = "tbl:"
        private const val TABLES_PAGE_SIZE = 8
        private const val NOOP_CALLBACK = "noop"
        private val DEFAULT_NIGHT_DURATION: Duration = Duration.ofHours(8)
        private const val CLUB_LIST_LIMIT = 8
        private const val NIGHT_LIST_LIMIT = 8
        private val RUSSIAN_LOCALE: Locale = Locale("ru", "RU")
    }
}
