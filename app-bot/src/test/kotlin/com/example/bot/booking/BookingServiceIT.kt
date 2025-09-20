package com.example.bot.booking

import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.booking.core.BookingHoldRepository
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.booking.core.OutboxRepository
import com.example.bot.data.booking.core.BookingOutboxTable
import com.example.bot.data.db.Clubs
import com.example.bot.testing.PostgresAppTest
import com.example.bot.workers.OutboxWorker
import com.example.bot.workers.SendOutcome
import com.example.bot.workers.SendPort
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import testing.RequiresDocker

@RequiresDocker
@Tag("it")
class BookingServiceIT : PostgresAppTest() {
    private val fixedNow: Instant = Instant.parse("2025-04-01T10:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private fun newService(): BookingService {
        val bookingRepo = BookingRepository(database, clock)
        val holdRepo = BookingHoldRepository(database, clock)
        val outboxRepo = OutboxRepository(database, clock)
        val auditRepo = AuditLogRepository(database, clock)
        return BookingService(bookingRepo, holdRepo, outboxRepo, auditRepo)
    }

    @Test
    fun `parallel confirm produces single booking`() = runBlocking {
        val service = newService()
        val seed = seedData()
        val holdResult =
            service.hold(
                HoldRequest(
                    clubId = seed.clubId,
                    tableId = seed.tableId,
                    slotStart = seed.slotStart,
                    slotEnd = seed.slotEnd,
                    guestsCount = 2,
                    ttl = Duration.ofMinutes(15),
                ),
                idempotencyKey = "hold-race",
            ) as BookingCmdResult.HoldCreated

        val outcomes =
            listOf("c1", "c2")
                .map { key -> async { service.confirm(holdResult.holdId, key) } }
                .awaitAll()

        val bookedCount =
            transaction(database) {
                BookingsTable
                    .select { BookingsTable.status eq BookingStatus.BOOKED.name }
                    .count()
            }
        assertEquals(1, bookedCount)
        assertTrue(outcomes.any { it is BookingCmdResult.Booked })
    }

    @Test
    fun `confirm is idempotent`() = runBlocking {
        val service = newService()
        val seed = seedData()
        val hold =
            service.hold(
                HoldRequest(
                    clubId = seed.clubId,
                    tableId = seed.tableId,
                    slotStart = seed.slotStart,
                    slotEnd = seed.slotEnd,
                    guestsCount = 3,
                    ttl = Duration.ofMinutes(10),
                ),
                idempotencyKey = "hold-idem",
            ) as BookingCmdResult.HoldCreated

        val first = service.confirm(hold.holdId, "confirm-idem") as BookingCmdResult.Booked
        val second = service.confirm(hold.holdId, "confirm-idem") as BookingCmdResult.AlreadyBooked
        assertEquals(first.bookingId, second.bookingId)

        val bookings =
            transaction(database) {
                BookingsTable
                    .select { BookingsTable.status eq BookingStatus.BOOKED.name }
                    .count()
            }
        assertEquals(1, bookings)
    }

    @Test
    fun `finalize enqueues and worker marks sent`() = runBlocking {
        val service = newService()
        val seed = seedData()
        val bookingId = confirmBooking(service, seed)
        val finalize = service.finalize(bookingId)
        assertTrue(finalize is BookingCmdResult.Booked)

        val sentMessages = mutableListOf<JsonObject>()
        val worker =
            OutboxWorker(
                repository = OutboxRepository(database, clock),
                sendPort = object : SendPort {
                    override suspend fun send(topic: String, payload: JsonObject): SendOutcome {
                        sentMessages += payload
                        return SendOutcome.Ok
                    }
                },
                limit = 5,
                idleDelay = Duration.ofMillis(50),
            )

        val job = launch { worker.run() }
        delay(200)
        job.cancel()

        val status =
            transaction(database) {
                BookingsTable
                    .select { BookingsTable.id eq bookingId }
                    .first()[BookingsTable.status]
            }
        assertEquals(BookingStatus.BOOKED.name, status)
        val outboxStatus =
            transaction(database) {
                BookingOutboxTable
                    .select { BookingOutboxTable.topic eq "booking.confirmed" }
                    .first()[BookingOutboxTable.status]
            }
        assertEquals("SENT", outboxStatus)
        assertTrue(sentMessages.isNotEmpty())
    }

    @Test
    fun `retryable errors apply exponential backoff`() = runBlocking {
        val seed = seedData()
        val bookingRepo = BookingRepository(database, clock)
        val holdRepo = BookingHoldRepository(database, clock)
        val outboxRepo = OutboxRepository(database, clock)
        val auditRepo = AuditLogRepository(database, clock)
        val service = BookingService(bookingRepo, holdRepo, outboxRepo, auditRepo)
        val bookingId = confirmBooking(service, seed)
        service.finalize(bookingId)

        val failingPort = object : SendPort {
            override suspend fun send(topic: String, payload: JsonObject): SendOutcome =
                SendOutcome.RetryableError(RuntimeException("temporary"))
        }
        val workerClock = Clock.fixed(fixedNow, ZoneOffset.UTC)
        val worker =
            OutboxWorker(
                repository = outboxRepo,
                sendPort = failingPort,
                limit = 1,
                idleDelay = Duration.ofMillis(20),
                clock = workerClock,
                random = Random(0),
            )

        val job = launch { worker.run() }
        delay(150)
        job.cancel()

        val stored =
            transaction(database) {
                BookingOutboxTable.select { BookingOutboxTable.status eq "NEW" }.first()
            }
        assertEquals(1, stored[BookingOutboxTable.attempts])
        val nextAttempt = stored[BookingOutboxTable.nextAttemptAt].toInstant()
        val expectedDelay = computeExpectedDelay(attemptsAfterFailure = 1)
        assertEquals(workerClock.instant().plus(expectedDelay), nextAttempt)
        assertTrue(
            expectedDelay.compareTo(com.example.bot.config.BotLimits.notifySendMaxBackoff) <= 0,
            "expected delay should not exceed max backoff",
        )
    }

    private fun computeExpectedDelay(attemptsAfterFailure: Int): Duration {
        val base = com.example.bot.config.BotLimits.notifySendBaseBackoff.toMillis()
        val max = com.example.bot.config.BotLimits.notifySendMaxBackoff.toMillis()
        val jitter = com.example.bot.config.BotLimits.notifySendJitter.toMillis()
        val shift = (attemptsAfterFailure - 1).coerceAtLeast(0).coerceAtMost(com.example.bot.config.BotLimits.notifyBackoffMaxShift)
        val raw = base shl shift
        val capped = raw.coerceAtMost(max)
        val offset = if (jitter == 0L) 0L else Random(0).nextLong(-jitter, jitter + 1)
        val candidate = (capped + offset).coerceAtLeast(base).coerceAtMost(max)
        return Duration.ofMillis(candidate)
    }

    private suspend fun confirmBooking(service: BookingService, seed: SeedData): UUID {
        val hold =
            service.hold(
                HoldRequest(
                    clubId = seed.clubId,
                    tableId = seed.tableId,
                    slotStart = seed.slotStart,
                    slotEnd = seed.slotEnd,
                    guestsCount = 2,
                    ttl = Duration.ofMinutes(30),
                ),
                idempotencyKey = "hold-${seed.tableId}",
            ) as BookingCmdResult.HoldCreated
        val confirm = service.confirm(hold.holdId, "confirm-${seed.tableId}") as BookingCmdResult.Booked
        return confirm.bookingId
    }

    private fun seedData(): SeedData {
        val slotStart = Instant.parse("2025-04-02T18:00:00Z")
        val slotEnd = Instant.parse("2025-04-02T21:00:00Z")
        return transaction(database) {
            val clubId =
                Clubs.insert {
                    it[name] = "Club"
                    it[description] = "Integration"
                    it[timezone] = "UTC"
                } get Clubs.id
            val clubIdValue = clubId.value.toLong()
            val tableId =
                TablesTable.insert {
                    it[TablesTable.clubId] = clubIdValue
                    it[zoneId] = null
                    it[tableNumber] = 5
                    it[capacity] = 6
                    it[minDeposit] = BigDecimal("75.00")
                    it[active] = true
                } get TablesTable.id
            EventsTable.insert {
                it[EventsTable.clubId] = clubIdValue
                it[startAt] = OffsetDateTime.ofInstant(slotStart, ZoneOffset.UTC)
                it[endAt] = OffsetDateTime.ofInstant(slotEnd, ZoneOffset.UTC)
                it[title] = "Event"
                it[isSpecial] = false
                it[posterUrl] = null
            }
            SeedData(
                clubId = clubIdValue,
                tableId = tableId,
                slotStart = slotStart,
                slotEnd = slotEnd,
            )
        }
    }

    private data class SeedData(
        val clubId: Long,
        val tableId: Long,
        val slotStart: Instant,
        val slotEnd: Instant,
    )
}
