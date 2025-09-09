package com.example.bot.time

import com.example.bot.availability.AvailabilityRepository
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Source of the slot generation.
 */
enum class NightSource {
    WEEKEND_RULE,
    HOLIDAY,
    EXCEPTION,
    EVENT_MATERIALIZED,
}

/**
 * Represents a single night slot in UTC and local time.
 */
data class NightSlot(
    val clubId: Long,
    val eventStartUtc: Instant,
    val eventEndUtc: Instant,
    val isSpecial: Boolean,
    val source: NightSource,
    val openLocal: LocalDateTime,
    val closeLocal: LocalDateTime,
    val zone: ZoneId,
)

/**
 * Weekly operating hours for a club.
 */
data class ClubHour(val dayOfWeek: DayOfWeek, val open: LocalTime, val close: LocalTime)

/**
 * Special holiday rules for a club.
 */
data class ClubHoliday(
    val date: LocalDate,
    val isOpen: Boolean,
    val overrideOpen: LocalTime?,
    val overrideClose: LocalTime?,
)

/**
 * Exceptions override all other rules.
 */
data class ClubException(
    val date: LocalDate,
    val isOpen: Boolean,
    val overrideOpen: LocalTime?,
    val overrideClose: LocalTime?,
)

/**
 * Minimal club representation.
 */
data class Club(val id: Long, val timezone: String)

/**
 * Materialized event stored in the database.
 */
data class Event(
    val id: Long,
    val clubId: Long,
    val startUtc: Instant,
    val endUtc: Instant,
    val isSpecial: Boolean = true,
)

/**
 * Resolves operating rules into concrete night slots.
 */
class OperatingRulesResolver(
    private val repository: AvailabilityRepository,
    private val clock: Clock = Clock.systemUTC(),
) {

/**
     * Resolve slots for given club and period.
     */
    suspend fun resolve(clubId: Long, fromUtc: Instant, toUtc: Instant): List<NightSlot> {
        val club = repository.findClub(clubId) ?: return emptyList()
        val zone = ZoneId.of(club.timezone)
        val fromDate = fromUtc.atZone(zone).toLocalDate()
        val toDate = toUtc.atZone(zone).toLocalDate()

        val hours = repository.listClubHours(clubId)
        val holidays = repository.listHolidays(clubId, fromDate, toDate).associateBy { it.date }
        val exceptions = repository.listExceptions(clubId, fromDate, toDate).associateBy { it.date }
        val events = repository.listEvents(clubId, fromUtc, toUtc)

        val result = mutableListOf<NightSlot>()

        var date = fromDate
        while (!date.isAfter(toDate)) {
            val exception = exceptions[date]
            val holiday = holidays[date]
            val baseHour = hours.find { it.dayOfWeek == date.dayOfWeek }

            var open: LocalTime? = null
            var close: LocalTime? = null
            var source: NightSource? = null
            var special = false

            if (exception != null) {
                if (!exception.isOpen) {
                    date = date.plusDays(1)
                    continue
                }
                open = exception.overrideOpen ?: baseHour?.open
                close = exception.overrideClose ?: baseHour?.close
                source = NightSource.EXCEPTION
                special = true
            } else if (holiday != null) {
                if (!holiday.isOpen) {
                    date = date.plusDays(1)
                    continue
                }
                open = holiday.overrideOpen ?: baseHour?.open
                close = holiday.overrideClose ?: baseHour?.close
                source = NightSource.HOLIDAY
                special = true
            } else if (baseHour != null) {
                open = baseHour.open
                close = baseHour.close
                source = NightSource.WEEKEND_RULE
            }

            if (open != null && close != null && source != null) {
                val openZdt = zoned(date, open, zone)
                val closeDate = if (close <= open) date.plusDays(1) else date
                val closeZdt = zoned(closeDate, close, zone)
                val startUtc = openZdt.toInstant()
                val endUtc = closeZdt.toInstant()
                if (endUtc > startUtc) {
                    result +=
                        NightSlot(
                            clubId = clubId,
                            eventStartUtc = startUtc,
                            eventEndUtc = endUtc,
                            isSpecial = special,
                            source = source,
                            openLocal = openZdt.toLocalDateTime(),
                            closeLocal = closeZdt.toLocalDateTime(),
                            zone = zone,
                        )
                }
            }

            date = date.plusDays(1)
        }

        events.forEach { event ->
            val startLocal = event.startUtc.atZone(zone).toLocalDateTime()
            val endLocal = event.endUtc.atZone(zone).toLocalDateTime()
            result +=
                NightSlot(
                    clubId = clubId,
                    eventStartUtc = event.startUtc,
                    eventEndUtc = event.endUtc,
                    isSpecial = event.isSpecial,
                    source = NightSource.EVENT_MATERIALIZED,
                    openLocal = startLocal,
                    closeLocal = endLocal,
                    zone = zone,
                )
        }

        val now = Instant.now(clock)
        val filtered = result.filter { it.eventEndUtc > now }

        val sorted = filtered.sortedBy { it.eventStartUtc }
        if (sorted.isEmpty()) return sorted

        val merged = mutableListOf<NightSlot>()
        for (slot in sorted) {
            val last = merged.lastOrNull()
            if (
                last != null &&
                last.source == slot.source &&
                last.isSpecial == slot.isSpecial &&
                last.eventEndUtc == slot.eventStartUtc
            ) {
                merged[merged.lastIndex] =
                    last.copy(eventEndUtc = slot.eventEndUtc, closeLocal = slot.closeLocal)
            } else {
                merged += slot
            }
        }
        return merged
    }

    private fun zoned(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime {
        val ldt = LocalDateTime.of(date, time)
        return ZonedDateTime.ofLocal(ldt, zone, null)
    }
}
