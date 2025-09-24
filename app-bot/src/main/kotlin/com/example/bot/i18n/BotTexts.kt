package com.example.bot.i18n

/**
 * Localized bot texts used across guest flow.
 */
@Suppress("TooManyFunctions")
class BotTexts {
    /**
     * Returns greeting text based on [lang]. Defaults to Russian.
     */
    fun greeting(lang: String?): String = if (lang.isEnglish()) "Welcome!" else "Привет!"

    /**
     * Returns menu button labels in selected language.
     */
    fun menu(lang: String?): Menu {
        return if (lang.isEnglish()) {
            Menu("Choose club", "My bookings", "Ask question", "Music")
        } else {
            Menu("Выбрать клуб", "Мои бронирования", "Задать вопрос", "Музыка")
        }
    }

    /**
     * Legend for hall renderer.
     */
    fun legend(lang: String?): String =
        if (lang.isEnglish()) "🟢 free / 🟡 hold / 🔴 booked" else "🟢 свободно / 🟡 hold / 🔴 занято"

    /**
     * Prompt shown before table selection.
     */
    fun chooseTable(lang: String?): String = if (lang.isEnglish()) "Choose a table:" else "Выберите стол:"

    fun chooseGuests(lang: String?) =
        if (lang.isEnglish()) "Choose number of guests:" else "Выберите количество гостей:"

    fun buttonExpired(lang: String?) =
        if (lang.isEnglish()) {
            "The button has expired, please refresh the screen."
        } else {
            "Кнопка устарела, обновите экран."
        }

    fun tableTaken(lang: String?) =
        if (lang.isEnglish()) {
            "This table is already taken. Please choose another one."
        } else {
            "Стол уже занят. Выберите другой, пожалуйста."
        }

    fun tooManyRequests(lang: String?) =
        if (lang.isEnglish()) {
            "Too many requests. Please try again."
        } else {
            "Слишком много запросов. Попробуйте ещё раз."
        }

    fun holdExpired(lang: String?) =
        if (lang.isEnglish()) {
            "Hold expired. Please try again."
        } else {
            "Пауза истекла. Попробуйте снова."
        }

    fun bookingConfirmedTitle(lang: String?) = if (lang.isEnglish()) "Booking confirmed ✅" else "Бронь подтверждена ✅"

    fun sessionExpired(lang: String?) =
        if (lang.isEnglish()) {
            "The session has expired, please start over."
        } else {
            "Сессия устарела, начните выбор заново."
        }

    fun noTables(lang: String?) =
        if (lang.isEnglish()) {
            "No free tables for this night."
        } else {
            "Свободных столов на эту ночь нет."
        }

    data class Menu(val chooseClub: String, val myBookings: String, val ask: String, val music: String)

    private fun String?.isEnglish(): Boolean = this?.startsWith("en", ignoreCase = true) == true
}
