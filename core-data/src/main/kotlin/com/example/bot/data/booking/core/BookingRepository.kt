package com.example.bot.data.booking.core

import com.example.bot.data.booking.BookingHoldsTable
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.db.isRetryLimitExceeded
import com.example.bot.data.db.isUniqueViolation
import com.example.bot.data.db.withTxRetry
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SortOrder

class BookingRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun findById(id: UUID): BookingRecord? {
        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                BookingsTable
                    .select { BookingsTable.id eq id }
                    .limit(1)
                    .firstOrNull()
                    ?.toBookingRecord()
            }
        }
    }

    suspend fun findByIdempotencyKey(key: String): BookingRecord? {
        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                BookingsTable
                    .select { BookingsTable.idempotencyKey eq key }
                    .limit(1)
                    .firstOrNull()
                    ?.toBookingRecord()
            }
        }
    }

    suspend fun existsActiveFor(tableId: Long, slotStart: Instant, slotEnd: Instant): Boolean {
        val start = slotStart.toOffsetDateTime()
        val end = slotEnd.toOffsetDateTime()
        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                BookingsTable
                    .select {
                        (BookingsTable.tableId eq tableId) and
                            (BookingsTable.slotStart eq start) and
                            (BookingsTable.slotEnd eq end) and
                            (BookingsTable.status inList ACTIVE_STATUSES)
                    }.empty()
                    .not()
            }
        }
    }

    suspend fun createBooked(
        clubId: Long,
        tableId: Long,
        slotStart: Instant,
        slotEnd: Instant,
        guests: Int,
        minRate: BigDecimal,
        idempotencyKey: String,
    ): BookingCoreResult<BookingRecord> {
        val start = slotStart.toOffsetDateTime()
        val end = slotEnd.toOffsetDateTime()
        return try {
            withTxRetry {
                newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                    val existingByKey =
                        BookingsTable
                            .select { BookingsTable.idempotencyKey eq idempotencyKey }
                            .limit(1)
                            .firstOrNull()
                    if (existingByKey != null) {
                        return@newSuspendedTransaction BookingCoreResult.Failure(
                            BookingCoreError.IdempotencyConflict,
                        )
                    }
                    val activeExists =
                        BookingsTable
                            .select {
                                (BookingsTable.tableId eq tableId) and
                                    (BookingsTable.slotStart eq start) and
                                    (BookingsTable.slotEnd eq end) and
                                    (BookingsTable.status inList ACTIVE_STATUSES)
                            }.empty()
                            .not()
                    if (activeExists) {
                        BookingCoreResult.Failure(BookingCoreError.DuplicateActiveBooking)
                    } else {
                        BookingCoreResult.Success(
                            insertBooking(clubId, tableId, start, end, guests, minRate, idempotencyKey),
                        )
                    }
                }
            }
        } catch (ex: Throwable) {
            when {
                ex.isUniqueViolation() -> BookingCoreResult.Failure(
                    determineConflictError(tableId, start, end, idempotencyKey),
                )
                ex.isRetryLimitExceeded() -> BookingCoreResult.Failure(BookingCoreError.OptimisticRetryExceeded)
                else -> throw ex
            }
        }
    }

    suspend fun setStatus(id: UUID, newStatus: BookingStatus): BookingCoreResult<BookingRecord> {
        return try {
            val record =
                withTxRetry {
                    newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                        updateStatusInternal(id, newStatus)
                    }
                }
            if (record != null) {
                BookingCoreResult.Success(record)
            } else {
                BookingCoreResult.Failure(BookingCoreError.BookingNotFound)
            }
        } catch (ex: Throwable) {
            when {
                ex.isRetryLimitExceeded() -> BookingCoreResult.Failure(BookingCoreError.OptimisticRetryExceeded)
                else -> throw ex
            }
        }
    }

    private suspend fun insertBooking(
        clubId: Long,
        tableId: Long,
        slotStart: OffsetDateTime,
        slotEnd: OffsetDateTime,
        guests: Int,
        minRate: BigDecimal,
        idempotencyKey: String,
    ): BookingRecord {
        val now = Instant.now(clock)
        val tableRow =
            TablesTable
                .select { TablesTable.id eq tableId }
                .limit(1)
                .firstOrNull()
                ?: throw IllegalStateException("table $tableId not found")
        val eventRow =
            EventsTable
                .select {
                    (EventsTable.clubId eq tableRow[TablesTable.clubId]) and
                        (EventsTable.startAt eq slotStart) and
                        (EventsTable.endAt eq slotEnd)
                }
                .limit(1)
                .firstOrNull()
                ?: throw IllegalStateException("event for slot not found")
        val id = UUID.randomUUID()
        val qrSecret = UUID.randomUUID().toString().replace("-", "")
        BookingsTable.insert {
            it[BookingsTable.id] = id
            it[BookingsTable.eventId] = eventRow[EventsTable.id]
            it[BookingsTable.clubId] = clubId
            it[BookingsTable.tableId] = tableId
            it[BookingsTable.tableNumber] = tableRow[TablesTable.tableNumber]
            it[BookingsTable.guestUserId] = null
            it[BookingsTable.guestName] = null
            it[BookingsTable.phoneE164] = null
            it[BookingsTable.promoterUserId] = null
            it[BookingsTable.guestsCount] = guests
            it[BookingsTable.minDeposit] = minRate
            it[BookingsTable.totalDeposit] = minRate
            it[BookingsTable.slotStart] = slotStart
            it[BookingsTable.slotEnd] = slotEnd
            it[BookingsTable.arrivalBy] = null
            it[BookingsTable.status] = BookingStatus.BOOKED.name
            it[BookingsTable.qrSecret] = qrSecret
            it[BookingsTable.idempotencyKey] = idempotencyKey
            val timestamp = now.toOffsetDateTime()
            it[BookingsTable.createdAt] = timestamp
            it[BookingsTable.updatedAt] = timestamp
        }
        return BookingsTable
            .select { BookingsTable.id eq id }
            .limit(1)
            .first()
            .toBookingRecord()
    }

    private suspend fun determineConflictError(
        tableId: Long,
        slotStart: OffsetDateTime,
        slotEnd: OffsetDateTime,
        idempotencyKey: String,
    ): BookingCoreError {
        return newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            BookingsTable
                .select { BookingsTable.idempotencyKey eq idempotencyKey }
                .limit(1)
                .firstOrNull()
                ?.let { return@newSuspendedTransaction BookingCoreError.IdempotencyConflict }
            val activeExists =
                BookingsTable
                    .select {
                        (BookingsTable.tableId eq tableId) and
                            (BookingsTable.slotStart eq slotStart) and
                            (BookingsTable.slotEnd eq slotEnd) and
                            (BookingsTable.status inList ACTIVE_STATUSES)
                    }.empty()
                    .not()
            if (activeExists) {
                BookingCoreError.DuplicateActiveBooking
            } else {
                BookingCoreError.UnexpectedFailure
            }
        }
    }

    private suspend fun updateStatusInternal(id: UUID, newStatus: BookingStatus): BookingRecord? {
        val updated =
            BookingsTable.update({ BookingsTable.id eq id }) {
                it[status] = newStatus.name
                it[updatedAt] = Instant.now(clock).toOffsetDateTime()
            }
        if (updated == 0) {
            return null
        }
        return BookingsTable
            .select { BookingsTable.id eq id }
            .limit(1)
            .firstOrNull()
            ?.toBookingRecord()
    }

    private fun ResultRow.toBookingRecord(): BookingRecord {
        return BookingRecord(
            id = this[BookingsTable.id],
            clubId = this[BookingsTable.clubId],
            tableId = this[BookingsTable.tableId],
            tableNumber = this[BookingsTable.tableNumber],
            eventId = this[BookingsTable.eventId],
            guests = this[BookingsTable.guestsCount],
            minRate = this[BookingsTable.minDeposit],
            totalRate = this[BookingsTable.totalDeposit],
            slotStart = this[BookingsTable.slotStart].toInstant(),
            slotEnd = this[BookingsTable.slotEnd].toInstant(),
            status = BookingStatus.valueOf(this[BookingsTable.status]),
            qrSecret = this[BookingsTable.qrSecret],
            idempotencyKey = this[BookingsTable.idempotencyKey],
            createdAt = this[BookingsTable.createdAt].toInstant(),
            updatedAt = this[BookingsTable.updatedAt].toInstant(),
        )
    }

    companion object {
        private val ACTIVE_STATUSES = listOf(BookingStatus.BOOKED.name, BookingStatus.SEATED.name)
    }
}

class BookingHoldRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun createHold(
        tableId: Long,
        slotStart: Instant,
        slotEnd: Instant,
        ttl: java.time.Duration,
    ): BookingCoreResult<BookingHold> {
        val start = slotStart.toOffsetDateTime()
        val end = slotEnd.toOffsetDateTime()
        return try {
            withTxRetry {
                newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                    val now = Instant.now(clock)
                    val activeExists =
                        BookingHoldsTable
                            .select {
                                (BookingHoldsTable.tableId eq tableId) and
                                    (BookingHoldsTable.slotStart eq start) and
                                    (BookingHoldsTable.slotEnd eq end) and
                                    (BookingHoldsTable.expiresAt greater now.toOffsetDateTime())
                            }.empty()
                            .not()
                    if (activeExists) {
                        BookingCoreResult.Failure(BookingCoreError.ActiveHoldExists)
                    } else {
                        BookingCoreResult.Success(insertHold(tableId, start, end, ttl, now))
                    }
                }
            }
        } catch (ex: Throwable) {
            when {
                ex.isUniqueViolation() -> BookingCoreResult.Failure(BookingCoreError.ActiveHoldExists)
                ex.isRetryLimitExceeded() -> BookingCoreResult.Failure(BookingCoreError.OptimisticRetryExceeded)
                else -> throw ex
            }
        }
    }

    suspend fun prolongHold(id: UUID, ttl: java.time.Duration): BookingCoreResult<BookingHold> {
        return try {
            withTxRetry {
                newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                    prolongHoldInternal(id, ttl)
                }
            }
        } catch (ex: Throwable) {
            when {
                ex.isRetryLimitExceeded() -> BookingCoreResult.Failure(BookingCoreError.OptimisticRetryExceeded)
                else -> throw ex
            }
        }
    }

    suspend fun consumeHold(id: UUID): BookingCoreResult<BookingHold> {
        return try {
            withTxRetry {
                newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                    consumeHoldInternal(id)
                }
            }
        } catch (ex: Throwable) {
            when {
                ex.isRetryLimitExceeded() -> BookingCoreResult.Failure(BookingCoreError.OptimisticRetryExceeded)
                else -> throw ex
            }
        }
    }

    suspend fun cleanupExpired(now: Instant): Int {
        val cutoff = now.toOffsetDateTime()
        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                BookingHoldsTable.deleteWhere { BookingHoldsTable.expiresAt lessEq cutoff }
            }
        }
    }

    private fun insertHold(
        tableId: Long,
        slotStart: OffsetDateTime,
        slotEnd: OffsetDateTime,
        ttl: java.time.Duration,
        now: Instant,
    ): BookingHold {
        val tableRow =
            TablesTable
                .select { TablesTable.id eq tableId }
                .limit(1)
                .firstOrNull()
                ?: throw IllegalStateException("table $tableId not found")
        val eventRow =
            EventsTable
                .select {
                    (EventsTable.clubId eq tableRow[TablesTable.clubId]) and
                        (EventsTable.startAt eq slotStart) and
                        (EventsTable.endAt eq slotEnd)
                }
                .limit(1)
                .firstOrNull()
                ?: throw IllegalStateException("event for hold slot not found")
        val expiresAt = now.plus(ttl).toOffsetDateTime()
        val id = UUID.randomUUID()
        BookingHoldsTable.insert {
            it[BookingHoldsTable.id] = id
            it[BookingHoldsTable.eventId] = eventRow[EventsTable.id]
            it[BookingHoldsTable.tableId] = tableId
            it[BookingHoldsTable.holderUserId] = null
            it[BookingHoldsTable.guestsCount] = 1
            it[BookingHoldsTable.minDeposit] = tableRow[TablesTable.minDeposit]
            it[BookingHoldsTable.slotStart] = slotStart
            it[BookingHoldsTable.slotEnd] = slotEnd
            it[BookingHoldsTable.expiresAt] = expiresAt
            it[BookingHoldsTable.idempotencyKey] = UUID.randomUUID().toString()
        }
        return BookingHold(
            id = id,
            tableId = tableId,
            eventId = eventRow[EventsTable.id],
            slotStart = slotStart.toInstant(),
            slotEnd = slotEnd.toInstant(),
            expiresAt = expiresAt.toInstant(),
        )
    }

    private fun prolongHoldInternal(id: UUID, ttl: java.time.Duration): BookingCoreResult<BookingHold> {
        val row =
            BookingHoldsTable
                .select { BookingHoldsTable.id eq id }
                .limit(1)
                .firstOrNull()
                ?: return BookingCoreResult.Failure(BookingCoreError.HoldNotFound)
        val expiresAt = row[BookingHoldsTable.expiresAt].toInstant()
        val now = Instant.now(clock)
        val outcome =
            if (expiresAt.isBefore(now)) {
                BookingHoldsTable.deleteWhere { BookingHoldsTable.id eq id }
                BookingCoreResult.Failure(BookingCoreError.HoldExpired)
            } else {
                val newExpiry = now.plus(ttl).toOffsetDateTime()
                BookingHoldsTable.update({ BookingHoldsTable.id eq id }) {
                    it[BookingHoldsTable.expiresAt] = newExpiry
                }
                BookingCoreResult.Success(
                    BookingHold(
                        id = id,
                        tableId = row[BookingHoldsTable.tableId],
                        eventId = row[BookingHoldsTable.eventId],
                        slotStart = row[BookingHoldsTable.slotStart].toInstant(),
                        slotEnd = row[BookingHoldsTable.slotEnd].toInstant(),
                        expiresAt = newExpiry.toInstant(),
                    ),
                )
            }
        return outcome
    }

    private fun consumeHoldInternal(id: UUID): BookingCoreResult<BookingHold> {
        val row =
            BookingHoldsTable
                .select { BookingHoldsTable.id eq id }
                .limit(1)
                .firstOrNull()
                ?: return BookingCoreResult.Failure(BookingCoreError.HoldNotFound)
        val expiresAt = row[BookingHoldsTable.expiresAt].toInstant()
        val now = Instant.now(clock)
        BookingHoldsTable.deleteWhere { BookingHoldsTable.id eq id }
        return if (expiresAt.isBefore(now)) {
            BookingCoreResult.Failure(BookingCoreError.HoldExpired)
        } else {
            BookingCoreResult.Success(
                BookingHold(
                    id = id,
                    tableId = row[BookingHoldsTable.tableId],
                    eventId = row[BookingHoldsTable.eventId],
                    slotStart = row[BookingHoldsTable.slotStart].toInstant(),
                    slotEnd = row[BookingHoldsTable.slotEnd].toInstant(),
                    expiresAt = expiresAt,
                ),
            )
        }
    }
}

class OutboxRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun enqueue(topic: String, payload: JsonObject): Long {
        val now = Instant.now(clock).toOffsetDateTime()
        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                BookingOutboxTable.insert {
                    it[BookingOutboxTable.topic] = topic
                    it[BookingOutboxTable.payload] = payload
                    it[BookingOutboxTable.status] = OutboxMessageStatus.NEW.name
                    it[BookingOutboxTable.attempts] = 0
                    it[BookingOutboxTable.nextAttemptAt] = now
                    it[BookingOutboxTable.lastError] = null
                    it[BookingOutboxTable.createdAt] = now
                    it[BookingOutboxTable.updatedAt] = now
                }[BookingOutboxTable.id]
            }
        }
    }

    suspend fun pickBatchForSend(limit: Int): List<OutboxMessage> {
        val now = Instant.now(clock).toOffsetDateTime()
        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                BookingOutboxTable
                    .select {
                        (BookingOutboxTable.status eq OutboxMessageStatus.NEW.name) and
                            (BookingOutboxTable.nextAttemptAt lessEq now)
                    }
                    .orderBy(
                        BookingOutboxTable.nextAttemptAt to SortOrder.ASC,
                        BookingOutboxTable.id to SortOrder.ASC,
                    )
                    .limit(limit)
                    .map { it.toOutboxMessage() }
            }
        }
    }

    suspend fun markSent(id: Long): BookingCoreResult<Unit> {
        return try {
            withTxRetry {
                newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                    val row =
                        BookingOutboxTable
                            .select { BookingOutboxTable.id eq id }
                            .limit(1)
                            .firstOrNull()
                            ?: return@newSuspendedTransaction BookingCoreResult.Failure(
                                BookingCoreError.OutboxRecordNotFound,
                            )
                    val attempts = row[BookingOutboxTable.attempts] + 1
                    val now = Instant.now(clock).toOffsetDateTime()
                    BookingOutboxTable.update({ BookingOutboxTable.id eq id }) {
                        it[BookingOutboxTable.status] = OutboxMessageStatus.SENT.name
                        it[BookingOutboxTable.attempts] = attempts
                        it[BookingOutboxTable.nextAttemptAt] = now
                        it[BookingOutboxTable.lastError] = null
                        it[BookingOutboxTable.updatedAt] = now
                    }
                    BookingCoreResult.Success(Unit)
                }
            }
        } catch (ex: Throwable) {
            when {
                ex.isRetryLimitExceeded() -> BookingCoreResult.Failure(BookingCoreError.OptimisticRetryExceeded)
                else -> throw ex
            }
        }
    }

    suspend fun markFailedWithRetry(id: Long, reason: String): BookingCoreResult<OutboxMessage> {
        return try {
            val message =
                withTxRetry {
                    newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                        val row =
                            BookingOutboxTable
                                .select { BookingOutboxTable.id eq id }
                                .limit(1)
                                .firstOrNull()
                                ?: return@newSuspendedTransaction null
                        val attempts = row[BookingOutboxTable.attempts] + 1
                        val nextAttempt = computeNextAttempt(attempts)
                        val nowTs = Instant.now(clock).toOffsetDateTime()
                        BookingOutboxTable.update({ BookingOutboxTable.id eq id }) {
                            it[BookingOutboxTable.attempts] = attempts
                            it[BookingOutboxTable.status] = OutboxMessageStatus.NEW.name
                            it[BookingOutboxTable.nextAttemptAt] = nextAttempt.toOffsetDateTime()
                            it[BookingOutboxTable.lastError] = reason
                            it[BookingOutboxTable.updatedAt] = nowTs
                        }
                        row.toOutboxMessage(attempts, nextAttempt, reason)
                    }
                }
            message?.let { BookingCoreResult.Success(it) }
                ?: BookingCoreResult.Failure(BookingCoreError.OutboxRecordNotFound)
        } catch (ex: Throwable) {
            when {
                ex.isRetryLimitExceeded() -> BookingCoreResult.Failure(BookingCoreError.OptimisticRetryExceeded)
                else -> throw ex
            }
        }
    }

    private fun computeNextAttempt(attempts: Int): Instant {
        val shift = min((attempts - 1).coerceAtLeast(0), com.example.bot.config.BotLimits.notifyBackoffMaxShift)
        val multiplier = 1L shl shift
        val base = com.example.bot.config.BotLimits.notifySendBaseBackoff
        val candidate = base.multipliedBy(multiplier)
        val capped =
            if (candidate <= com.example.bot.config.BotLimits.notifySendMaxBackoff) {
                candidate
            } else {
                com.example.bot.config.BotLimits.notifySendMaxBackoff
            }
        return Instant.now(clock).plus(capped)
    }

    private fun ResultRow.toOutboxMessage(): OutboxMessage {
        return OutboxMessage(
            id = this[BookingOutboxTable.id],
            topic = this[BookingOutboxTable.topic],
            payload = this[BookingOutboxTable.payload],
            status = OutboxMessageStatus.valueOf(this[BookingOutboxTable.status]),
            attempts = this[BookingOutboxTable.attempts],
            nextAttemptAt = this[BookingOutboxTable.nextAttemptAt].toInstant(),
            lastError = this[BookingOutboxTable.lastError],
        )
    }

    private fun ResultRow.toOutboxMessage(attempts: Int, nextAttempt: Instant, reason: String?): OutboxMessage {
        return OutboxMessage(
            id = this[BookingOutboxTable.id],
            topic = this[BookingOutboxTable.topic],
            payload = this[BookingOutboxTable.payload],
            status = OutboxMessageStatus.NEW,
            attempts = attempts,
            nextAttemptAt = nextAttempt,
            lastError = reason,
        )
    }
}

class AuditLogRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun log(
        userId: Long?,
        action: String,
        resource: String,
        clubId: Long?,
        result: String,
        ip: String?,
        meta: JsonObject?,
    ): Long {
        val now = Instant.now(clock).toOffsetDateTime()
        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                com.example.bot.data.audit.AuditLogTable.insert {
                    it[com.example.bot.data.audit.AuditLogTable.userId] = userId
                    it[com.example.bot.data.audit.AuditLogTable.action] = action
                    it[com.example.bot.data.audit.AuditLogTable.resource] = resource
                    it[com.example.bot.data.audit.AuditLogTable.resourceId] = null
                    it[com.example.bot.data.audit.AuditLogTable.clubId] = clubId
                    it[com.example.bot.data.audit.AuditLogTable.ip] = ip
                    it[com.example.bot.data.audit.AuditLogTable.result] = result
                    it[com.example.bot.data.audit.AuditLogTable.meta] = meta ?: JsonObject(emptyMap())
                    it[com.example.bot.data.audit.AuditLogTable.createdAt] = now
                }[com.example.bot.data.audit.AuditLogTable.id]
            }
        }
    }
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
