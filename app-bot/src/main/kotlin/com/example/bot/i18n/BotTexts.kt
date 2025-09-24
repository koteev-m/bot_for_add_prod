package com.example.bot.i18n

/**
 * Localized bot texts used across guest flow.
 */
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
