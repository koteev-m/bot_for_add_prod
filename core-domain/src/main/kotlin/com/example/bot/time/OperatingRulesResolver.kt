package com.example.bot.time

import com.example.bot.availability.AvailabilityRepository
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

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

private data class DayHours(val open: LocalTime, val close: LocalTime)

private data class DayException(
    val isOpen: Boolean,
    val overrideOpen: LocalTime?,
    val overrideClose: LocalTime?,
)

private data class DayHoliday(
    val isOpen: Boolean,
    val overrideOpen: LocalTime?,
    val overrideClose: LocalTime?,
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
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount", "LoopWithTooManyJumpStatements")
    suspend fun resolve(
        clubId: Long,
        fromUtc: Instant,
        toUtc: Instant,
    ): List<NightSlot> {
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

            val dayHours =
                mergeDayHours(
                    base = baseHour?.let { DayHours(it.open, it.close) },
                    exception =
                        exception?.let {
                            DayException(
                                isOpen = it.isOpen,
                                overrideOpen = it.overrideOpen,
                                overrideClose = it.overrideClose,
                            )
                        },
                    holiday =
                        holiday?.let {
                            DayHoliday(
                                isOpen = it.isOpen,
                                overrideOpen = it.overrideOpen,
                                overrideClose = it.overrideClose,
                            )
                        },
                )

            val source =
                when {
                    dayHours == null -> null
                    holiday?.isOpen == true -> NightSource.HOLIDAY
                    exception?.isOpen == true -> NightSource.EXCEPTION
                    baseHour != null -> NightSource.WEEKEND_RULE
                    else -> null
                }

            if (dayHours != null && source != null) {
                val (startUtc, endUtc) = toUtcWindow(date, dayHours, zone)
                if (endUtc > startUtc) {
                    result +=
                        NightSlot(
                            clubId = clubId,
                            eventStartUtc = startUtc,
                            eventEndUtc = endUtc,
                            isSpecial = source != NightSource.WEEKEND_RULE,
                            source = source,
                            openLocal = startUtc.atZone(zone).toLocalDateTime(),
                            closeLocal = endUtc.atZone(zone).toLocalDateTime(),
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
            if (shouldMerge(last, slot)) {
                merged[merged.lastIndex] = last!!.copy(eventEndUtc = slot.eventEndUtc, closeLocal = slot.closeLocal)
            } else {
                merged += slot
            }
        }
        return merged
    }

    private fun shouldMerge(
        last: NightSlot?,
        slot: NightSlot,
    ): Boolean =
        last != null &&
            last.source == slot.source &&
            last.isSpecial == slot.isSpecial &&
            last.eventEndUtc == slot.eventStartUtc

    private fun toUtcWindow(
        localDate: LocalDate,
        hours: DayHours,
        zone: ZoneId,
    ): Pair<Instant, Instant> {
        val openZdt = localDate.atTime(hours.open).atZone(zone)
        val closeBase = localDate.atTime(hours.close).atZone(zone)
        val closeZdt = if (!closeBase.isAfter(openZdt)) closeBase.plusDays(1) else closeBase
        return openZdt.toInstant() to closeZdt.toInstant()
    }
}

private fun mergeDayHours(
    base: DayHours?,
    exception: DayException?,
    holiday: DayHoliday?,
): DayHours? {
    val hasException = exception != null
    val afterException =
        when {
            exception == null -> base
            !exception.isOpen -> return null
            else -> {
                val open = exception.overrideOpen ?: base?.open
                val close = exception.overrideClose ?: base?.close
                if (open != null && close != null) DayHours(open, close) else null
            }
        }

    return when {
        holiday == null -> afterException
        !holiday.isOpen -> null
        else -> {
            val src = afterException ?: if (!hasException) base else null
            val open = holiday.overrideOpen ?: src?.open
            val close = holiday.overrideClose ?: src?.close
            if (open != null && close != null) {
                DayHours(open, close)
            } else {
                null
            }
        }
    }
}
