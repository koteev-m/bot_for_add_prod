package com.example.bot.data.repo

import com.example.bot.payments.PaymentsRepository
import com.example.bot.payments.PaymentsRepository.PaymentRecord
import java.util.UUID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp

/**
 * Exposed-based implementation of [PaymentsRepository].
 */
class PaymentsRepositoryImpl(private val db: Database) : PaymentsRepository {
    object PaymentsTable : Table("payments") {
        val id = uuid("id").autoGenerate()
        val bookingId = uuid("booking_id").nullable()
        val provider = text("provider")
        val currency = varchar("currency", 8)
        val amountMinor = long("amount_minor")
        val status = text("status")
        val payload = text("payload").uniqueIndex()
        val externalId = text("external_id").nullable()
        val idempotencyKey = text("idempotency_key").uniqueIndex()
        val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp())
        override val primaryKey = PrimaryKey(id)
    }

    override suspend fun createInitiated(
        bookingId: UUID?,
        provider: String,
        currency: String,
        amountMinor: Long,
        payload: String,
        idempotencyKey: String,
    ): PaymentRecord = newSuspendedTransaction(db = db) {
        val row = PaymentsTable.insert {
            it[PaymentsTable.bookingId] = bookingId
            it[PaymentsTable.provider] = provider
            it[PaymentsTable.currency] = currency
            it[PaymentsTable.amountMinor] = amountMinor
            it[PaymentsTable.status] = "INITIATED"
            it[PaymentsTable.payload] = payload
            it[PaymentsTable.idempotencyKey] = idempotencyKey
        }.resultedValues!!.first()
        row.toRecord()
    }

    override suspend fun markPending(id: UUID) = updateStatus(id, "PENDING")

    override suspend fun markCaptured(id: UUID, externalId: String?) = newSuspendedTransaction(db = db) {
        PaymentsTable.update({ PaymentsTable.id eq id }) {
            it[status] = "CAPTURED"
            it[PaymentsTable.externalId] = externalId
        }
        Unit
    }

    override suspend fun markDeclined(id: UUID, reason: String) = newSuspendedTransaction(db = db) {
        PaymentsTable.update({ PaymentsTable.id eq id }) {
            it[status] = "DECLINED"
            it[externalId] = reason
        }
        Unit
    }

    override suspend fun markRefunded(id: UUID, externalId: String?) = newSuspendedTransaction(db = db) {
        PaymentsTable.update({ PaymentsTable.id eq id }) {
            it[status] = "REFUNDED"
            it[PaymentsTable.externalId] = externalId
        }
        Unit
    }

    override suspend fun findByPayload(payload: String): PaymentRecord? = newSuspendedTransaction(db = db) {
        PaymentsTable.select { PaymentsTable.payload eq payload }
            .firstOrNull()?.toRecord()
    }

    private suspend fun updateStatus(id: UUID, status: String) = newSuspendedTransaction(db = db) {
        PaymentsTable.update({ PaymentsTable.id eq id }) {
            it[PaymentsTable.status] = status
        }
        Unit
    }

    private fun ResultRow.toRecord(): PaymentRecord = PaymentRecord(
        id = this[PaymentsTable.id],
        bookingId = this[PaymentsTable.bookingId],
        provider = this[PaymentsTable.provider],
        currency = this[PaymentsTable.currency],
        amountMinor = this[PaymentsTable.amountMinor],
        status = this[PaymentsTable.status],
        payload = this[PaymentsTable.payload],
        externalId = this[PaymentsTable.externalId],
        idempotencyKey = this[PaymentsTable.idempotencyKey],
        createdAt = this[PaymentsTable.createdAt],
        updatedAt = this[PaymentsTable.updatedAt],
    )
}

