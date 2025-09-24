@file:Suppress("SpreadOperator")

package com.example.bot.telegram

import com.example.bot.availability.TableAvailabilityDto
import com.example.bot.i18n.BotTexts
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup

/**
 * Factory for inline keyboards used in the guest flow.
 */
class Keyboards(private val texts: BotTexts) {
    /**
     * Main menu keyboard shown on /start.
     */
    fun startMenu(lang: String?): InlineKeyboardMarkup {
        val m = texts.menu(lang)
        return InlineKeyboardMarkup(
            arrayOf(
                InlineKeyboardButton(m.chooseClub).callbackData("menu:clubs"),
            ),
            arrayOf(
                InlineKeyboardButton(m.myBookings).callbackData("menu:bookings"),
            ),
            arrayOf(
                InlineKeyboardButton(m.ask).callbackData("menu:ask"),
            ),
            arrayOf(
                InlineKeyboardButton(m.music).callbackData("menu:music"),
            ),
        )
    }

    /**
     * Keyboard with club choices.
     * Each pair is token to display name.
     */
    fun clubsKeyboard(clubs: List<Pair<String, String>>): InlineKeyboardMarkup {
        val rows =
            clubs.map { (token, name) ->
                arrayOf(InlineKeyboardButton(name).callbackData("club:$token"))
            }
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    /**
     * Keyboard listing nights.
     */
    fun nightsKeyboard(nights: List<Pair<String, String>>): InlineKeyboardMarkup {
        val rows =
            nights.map { (token, label) ->
                arrayOf(InlineKeyboardButton(label).callbackData("night:$token"))
            }
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    /**
     * Keyboard for tables with simple pagination.
     */
    fun tablesKeyboard(
        tables: List<TableAvailabilityDto>,
        page: Int,
        pageSize: Int,
        encode: (TableAvailabilityDto) -> String,
    ): InlineKeyboardMarkup {
        val start = (page - 1) * pageSize
        val slice = tables.drop(start).take(pageSize)
        val rows =
            slice
                .map { t ->
                    arrayOf(
                        InlineKeyboardButton("Table ${t.tableNumber} · от ${t.minDeposit}₽")
                            .callbackData(encode(t).ensureTablePrefix()),
                    )
                }.toMutableList()
        val totalPages = (tables.size + pageSize - 1) / pageSize
        if (totalPages > 1) {
            val nav = mutableListOf<InlineKeyboardButton>()
            if (page > 1) {
                nav +=
                    InlineKeyboardButton("⬅️")
                        .callbackData("pg:${page - 1}")
            }
            nav += InlineKeyboardButton("$page/$totalPages").callbackData("noop")
            if (page < totalPages) {
                nav +=
                    InlineKeyboardButton("➡️")
                        .callbackData("pg:${page + 1}")
            }
            rows += nav.toTypedArray()
        }
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    /**
     * Keyboard for selecting number of guests up to [capacity].
     */
    fun guestsKeyboard(
        capacity: Int,
        encode: (Int) -> String,
    ): InlineKeyboardMarkup {
        val rows = mutableListOf<Array<InlineKeyboardButton>>()
        var row = mutableListOf<InlineKeyboardButton>()
        for (i in 1..capacity) {
            row += InlineKeyboardButton(i.toString()).callbackData("g:${encode(i)}")
            if (row.size == GUESTS_PER_ROW) {
                rows += row.toTypedArray()
                row = mutableListOf()
            }
        }
        if (row.isNotEmpty()) rows += row.toTypedArray()
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }
}

private const val GUESTS_PER_ROW = 4
private const val TABLE_PREFIX = "tbl:"

private fun String.ensureTablePrefix(): String {
    return if (startsWith(TABLE_PREFIX)) this else TABLE_PREFIX + this
}
