package com.example.bot.payments

import java.time.Instant
import java.util.UUID

/**
 * Persistence interface for payment records.
 */
interface PaymentsRepository {
    /** Representation of a payment row. */
    data class PaymentRecord(
        val id: UUID,
        val bookingId: UUID?,
        val provider: String,
        val currency: String,
        val amountMinor: Long,
        val status: String,
        val payload: String,
        val externalId: String?,
        val idempotencyKey: String,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    suspend fun createInitiated(
        bookingId: UUID?,
        provider: String,
        currency: String,
        amountMinor: Long,
        payload: String,
        idempotencyKey: String,
    ): PaymentRecord

    suspend fun markPending(id: UUID)

    suspend fun markCaptured(id: UUID, externalId: String?)

    suspend fun markDeclined(id: UUID, reason: String)

    suspend fun markRefunded(id: UUID, externalId: String?)

    suspend fun findByPayload(payload: String): PaymentRecord?
}
