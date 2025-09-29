package com.example.bot.time

import com.example.bot.availability.AvailabilityRepository
import com.example.bot.availability.Table
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class OperatingRulesResolverTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val clock = Clock.fixed(Instant.parse("2025-05-01T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `holiday inherits exception overrides`() {
        val repository =
            object : AvailabilityRepository {
                override suspend fun findClub(clubId: Long) = Club(clubId, zone.id)

                override suspend fun listClubHours(clubId: Long) =
                    listOf(ClubHour(java.time.DayOfWeek.FRIDAY, LocalTime.of(22, 0), LocalTime.of(6, 0)))

                override suspend fun listHolidays(
                    clubId: Long,
                    from: LocalDate,
                    to: LocalDate,
                ) = listOf(
                    ClubHoliday(
                        LocalDate.of(2025, 5, 2),
                        isOpen = true,
                        overrideOpen = null,
                        overrideClose = LocalTime.of(2, 0),
                    ),
                )

                override suspend fun listExceptions(
                    clubId: Long,
                    from: LocalDate,
                    to: LocalDate,
                ) = listOf(
                    ClubException(
                        LocalDate.of(2025, 5, 2),
                        isOpen = true,
                        overrideOpen = LocalTime.of(21, 0),
                        overrideClose = null,
                    ),
                )

                override suspend fun listEvents(
                    clubId: Long,
                    from: Instant,
                    to: Instant,
                ) = emptyList<Event>()

                override suspend fun findEvent(
                    clubId: Long,
                    startUtc: Instant,
                ) = null

                override suspend fun listTables(clubId: Long) = emptyList<Table>()

                override suspend fun listActiveHoldTableIds(
                    eventId: Long,
                    now: Instant,
                ) = emptySet<Long>()

                override suspend fun listActiveBookingTableIds(eventId: Long) = emptySet<Long>()
            }

        val resolver = OperatingRulesResolver(repository, clock)
        val from = LocalDate.of(2025, 5, 1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val to = LocalDate.of(2025, 5, 4).atStartOfDay(ZoneOffset.UTC).toInstant()
        val slots = runBlocking { resolver.resolve(1, from, to) }

        assertEquals(1, slots.size)
        val slot = slots.single()
        assertEquals(NightSource.HOLIDAY, slot.source)
        assertTrue(slot.isSpecial)
        assertEquals(LocalTime.of(21, 0), slot.openLocal.toLocalTime())
        assertEquals(LocalTime.of(2, 0), slot.closeLocal.toLocalTime())
        assertEquals(LocalDate.of(2025, 5, 2), slot.openLocal.toLocalDate())
        assertEquals(LocalDate.of(2025, 5, 3), slot.closeLocal.toLocalDate())
    }

    @Test
    fun `holiday without base uses explicit overrides`() {
        val repository =
            object : AvailabilityRepository {
                override suspend fun findClub(clubId: Long) = Club(clubId, zone.id)

                override suspend fun listClubHours(clubId: Long) = emptyList<ClubHour>()

                override suspend fun listHolidays(
                    clubId: Long,
                    from: LocalDate,
                    to: LocalDate,
                ) = listOf(
                    ClubHoliday(
                        LocalDate.of(2025, 5, 2),
                        isOpen = true,
                        overrideOpen = LocalTime.of(20, 0),
                        overrideClose = LocalTime.of(3, 0),
                    ),
                )

                override suspend fun listExceptions(
                    clubId: Long,
                    from: LocalDate,
                    to: LocalDate,
                ) = emptyList<ClubException>()

                override suspend fun listEvents(
                    clubId: Long,
                    from: Instant,
                    to: Instant,
                ) = emptyList<Event>()

                override suspend fun findEvent(
                    clubId: Long,
                    startUtc: Instant,
                ) = null

                override suspend fun listTables(clubId: Long) = emptyList<Table>()

                override suspend fun listActiveHoldTableIds(
                    eventId: Long,
                    now: Instant,
                ) = emptySet<Long>()

                override suspend fun listActiveBookingTableIds(eventId: Long) = emptySet<Long>()
            }

        val resolver = OperatingRulesResolver(repository, clock)
        val from = LocalDate.of(2025, 5, 1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val to = LocalDate.of(2025, 5, 3).atStartOfDay(ZoneOffset.UTC).toInstant()
        val slots = runBlocking { resolver.resolve(1, from, to) }

        assertEquals(1, slots.size)
        val slot = slots.single()
        assertEquals(NightSource.HOLIDAY, slot.source)
        assertEquals(LocalTime.of(20, 0), slot.openLocal.toLocalTime())
        assertEquals(LocalTime.of(3, 0), slot.closeLocal.toLocalTime())
        assertEquals(LocalDate.of(2025, 5, 3), slot.closeLocal.toLocalDate())
    }

    @Test
    fun `holiday missing boundary without base keeps night closed`() {
        val repository =
            object : AvailabilityRepository {
                override suspend fun findClub(clubId: Long) = Club(clubId, zone.id)

                override suspend fun listClubHours(clubId: Long) = emptyList<ClubHour>()

                override suspend fun listHolidays(
                    clubId: Long,
                    from: LocalDate,
                    to: LocalDate,
                ) = listOf(
                    ClubHoliday(
                        LocalDate.of(2025, 5, 2),
                        isOpen = true,
                        overrideOpen = LocalTime.of(20, 0),
                        overrideClose = null,
                    ),
                )

                override suspend fun listExceptions(
                    clubId: Long,
                    from: LocalDate,
                    to: LocalDate,
                ) = emptyList<ClubException>()

                override suspend fun listEvents(
                    clubId: Long,
                    from: Instant,
                    to: Instant,
                ) = emptyList<Event>()

                override suspend fun findEvent(
                    clubId: Long,
                    startUtc: Instant,
                ) = null

                override suspend fun listTables(clubId: Long) = emptyList<Table>()

                override suspend fun listActiveHoldTableIds(
                    eventId: Long,
                    now: Instant,
                ) = emptySet<Long>()

                override suspend fun listActiveBookingTableIds(eventId: Long) = emptySet<Long>()
            }

        val resolver = OperatingRulesResolver(repository, clock)
        val from = LocalDate.of(2025, 5, 1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val to = LocalDate.of(2025, 5, 3).atStartOfDay(ZoneOffset.UTC).toInstant()
        val slots = runBlocking { resolver.resolve(1, from, to) }

        assertEquals(0, slots.size)
    }
}
