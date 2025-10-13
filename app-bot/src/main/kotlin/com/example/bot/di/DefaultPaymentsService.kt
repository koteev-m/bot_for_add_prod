package com.example.bot.di

import com.example.bot.payments.finalize.PaymentsFinalizeService
import mu.KotlinLogging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger("PaymentsService")

class DefaultPaymentsService(
    private val finalizeService: PaymentsFinalizeService,
) : PaymentsService {

    private data class CancelEntry(
        val clubId: Long,
        val bookingId: UUID,
        val actorUserId: Long,
    )

    private data class RefundEntry(
        val clubId: Long,
        val bookingId: UUID,
        val actorUserId: Long,
        val amountMinor: Long,
        val result: PaymentsService.RefundResult,
    )

    private data class BookingLedger(
        var status: BookingStatus = BookingStatus.BOOKED,
        var capturedMinor: Long = 0,
        var refundedMinor: Long = 0,
    )

    private enum class BookingStatus { BOOKED, CANCELLED }

    private val cancelRequests = ConcurrentHashMap<String, CancelEntry>()
    private val refundRequests = ConcurrentHashMap<String, RefundEntry>()
    private val ledgers = ConcurrentHashMap<Pair<Long, UUID>, BookingLedger>()

    override suspend fun finalize(
        clubId: Long,
        bookingId: UUID,
        paymentToken: String?,
        idemKey: String,
        actorUserId: Long,
    ): PaymentsService.FinalizeResult {
        val result = finalizeService.finalize(clubId, bookingId, paymentToken, idemKey, actorUserId)
        val ledgerKey = clubId to bookingId
        val ledger = ledgers.computeIfAbsent(ledgerKey) { BookingLedger() }
        ledger.status = BookingStatus.BOOKED
        return PaymentsService.FinalizeResult(result.paymentStatus)
    }

    override suspend fun cancel(
        clubId: Long,
        bookingId: UUID,
        reason: String?,
        idemKey: String,
        actorUserId: Long,
    ) {
        val existing = cancelRequests[idemKey]
        if (existing != null) {
            if (existing.clubId != clubId || existing.bookingId != bookingId) {
                throw PaymentsService.ConflictException("idempotency key mismatch")
            }
            return
        }

        if (reason != null && reason.length > MAX_REASON_LENGTH) {
            throw PaymentsService.ValidationException("reason too long")
        }

        val ledgerKey = clubId to bookingId
        val ledger = ledgers.computeIfAbsent(ledgerKey) { BookingLedger() }
        synchronized(ledger) {
            if (ledger.status == BookingStatus.CANCELLED) {
                cancelRequests[idemKey] = CancelEntry(clubId, bookingId, actorUserId)
                return
            }
            if (ledger.status != BookingStatus.BOOKED) {
                throw PaymentsService.ConflictException("cannot cancel in current state")
            }
            ledger.status = BookingStatus.CANCELLED
        }
        cancelRequests[idemKey] = CancelEntry(clubId, bookingId, actorUserId)
        logger.info { "booking cancelled clubId=$clubId bookingId=$bookingId" }
    }

    override suspend fun refund(
        clubId: Long,
        bookingId: UUID,
        amountMinor: Long?,
        idemKey: String,
        actorUserId: Long,
    ): PaymentsService.RefundResult {
        val existing = refundRequests[idemKey]
        if (existing != null) {
            if (existing.clubId != clubId || existing.bookingId != bookingId) {
                throw PaymentsService.ConflictException("idempotency key mismatch")
            }
            if (existing.amountMinor != (amountMinor ?: existing.amountMinor)) {
                throw PaymentsService.ConflictException("idempotency payload mismatch")
            }
            return existing.result
        }

        val requested = amountMinor ?: -1L
        if (requested < 0 && amountMinor != null) {
            throw PaymentsService.ValidationException("amount must be non-negative")
        }

        val ledgerKey = clubId to bookingId
        val ledger = ledgers.computeIfAbsent(ledgerKey) { BookingLedger() }
        val effectiveAmount: Long
        synchronized(ledger) {
            val remainder = ledger.capturedMinor - ledger.refundedMinor
            if (amountMinor == null && remainder <= 0) {
                throw PaymentsService.ConflictException("nothing to refund")
            }
            if (amountMinor != null && amountMinor < 0) {
                throw PaymentsService.ValidationException("amount must be non-negative")
            }
            val target = amountMinor ?: remainder
            if (target < 0) {
                throw PaymentsService.ValidationException("invalid refund amount")
            }
            if (remainder <= 0 && target > 0) {
                throw PaymentsService.ConflictException("nothing to refund")
            }
            if (target > remainder) {
                throw PaymentsService.UnprocessableException("exceeds remainder")
            }
            effectiveAmount = target
            ledger.refundedMinor += effectiveAmount
        }

        val result = PaymentsService.RefundResult(effectiveAmount)
        refundRequests[idemKey] =
            RefundEntry(
                clubId = clubId,
                bookingId = bookingId,
                actorUserId = actorUserId,
                amountMinor = effectiveAmount,
                result = result,
            )
        logger.info { "payment refunded clubId=$clubId bookingId=$bookingId" }
        return result
    }

    internal fun seedLedger(
        clubId: Long,
        bookingId: UUID,
        status: String,
        capturedMinor: Long,
        refundedMinor: Long,
    ) {
        val bookingStatus = when (status.uppercase()) {
            "BOOKED" -> BookingStatus.BOOKED
            "CANCELLED" -> BookingStatus.CANCELLED
            else -> BookingStatus.BOOKED
        }
        val ledger = ledgers.computeIfAbsent(clubId to bookingId) { BookingLedger() }
        ledger.status = bookingStatus
        ledger.capturedMinor = capturedMinor
        ledger.refundedMinor = refundedMinor
    }

    companion object {
        private const val MAX_REASON_LENGTH = 1024
    }
}
