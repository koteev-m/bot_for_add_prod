package com.example.bot.di

import com.example.bot.observability.MetricsProvider
import com.example.bot.payments.finalize.PaymentsFinalizeService
import com.example.bot.telemetry.PaymentsMetrics
import com.example.bot.telemetry.PaymentsTraceMetadata
import com.example.bot.telemetry.maskBookingId
import com.example.bot.telemetry.setRefundAmount
import com.example.bot.telemetry.setResult
import com.example.bot.telemetry.spanSuspending
import io.micrometer.tracing.Tracer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DefaultPaymentsService(
    private val finalizeService: PaymentsFinalizeService,
    private val metricsProvider: MetricsProvider?,
    private val tracer: Tracer?,
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
        val traceMetadata =
            PaymentsTraceMetadata(
                httpRoute = "/api/clubs/{clubId}/bookings/finalize",
                paymentsPath = PaymentsMetrics.Path.Finalize.tag,
                idempotencyKeyPresent = idemKey.isNotBlank(),
                bookingIdMasked = maskBookingId(bookingId),
            )
        return tracer.spanSuspending("payments.finalize", traceMetadata) {
            try {
                val result = finalizeService.finalize(clubId, bookingId, paymentToken, idemKey, actorUserId)
                val ledgerKey = clubId to bookingId
                val ledger = ledgers.computeIfAbsent(ledgerKey) { BookingLedger() }
                ledger.status = BookingStatus.BOOKED
                setResult(PaymentsMetrics.Result.Ok)
                PaymentsService.FinalizeResult(result.paymentStatus)
            } catch (conflict: PaymentsFinalizeService.ConflictException) {
                PaymentsMetrics.incrementErrors(
                    metricsProvider,
                    PaymentsMetrics.Path.Finalize,
                    PaymentsMetrics.ErrorKind.State,
                )
                setResult(PaymentsMetrics.Result.Conflict)
                throw conflict
            } catch (validation: PaymentsFinalizeService.ValidationException) {
                PaymentsMetrics.incrementErrors(
                    metricsProvider,
                    PaymentsMetrics.Path.Finalize,
                    PaymentsMetrics.ErrorKind.Validation,
                )
                setResult(PaymentsMetrics.Result.Validation)
                throw validation
            } catch (unexpected: Throwable) {
                PaymentsMetrics.incrementErrors(
                    metricsProvider,
                    PaymentsMetrics.Path.Finalize,
                    PaymentsMetrics.ErrorKind.Unexpected,
                )
                setResult(PaymentsMetrics.Result.Unexpected)
                throw unexpected
            }
        }
    }

    override suspend fun cancel(
        clubId: Long,
        bookingId: UUID,
        reason: String?,
        idemKey: String,
        actorUserId: Long,
    ) {
        val traceMetadata =
            PaymentsTraceMetadata(
                httpRoute = "/api/clubs/{clubId}/bookings/{bookingId}/cancel",
                paymentsPath = PaymentsMetrics.Path.Cancel.tag,
                idempotencyKeyPresent = idemKey.isNotBlank(),
                bookingIdMasked = maskBookingId(bookingId),
            )
        tracer.spanSuspending("payments.cancel", traceMetadata) {
            try {
                val existing = cancelRequests[idemKey]
                if (existing != null) {
                    if (existing.clubId != clubId || existing.bookingId != bookingId) {
                        PaymentsMetrics.incrementErrors(
                            metricsProvider,
                            PaymentsMetrics.Path.Cancel,
                            PaymentsMetrics.ErrorKind.State,
                        )
                        setResult(PaymentsMetrics.Result.Conflict)
                        throw PaymentsService.ConflictException("idempotency key mismatch")
                    }
                    PaymentsMetrics.incrementIdempotentHit(
                        metricsProvider,
                        PaymentsMetrics.Path.Cancel,
                    )
                    setResult(PaymentsMetrics.Result.Ok)
                    return@spanSuspending
                }

                if (reason != null && reason.length > MAX_REASON_LENGTH) {
                    PaymentsMetrics.incrementErrors(
                        metricsProvider,
                        PaymentsMetrics.Path.Cancel,
                        PaymentsMetrics.ErrorKind.Validation,
                    )
                    setResult(PaymentsMetrics.Result.Validation)
                    throw PaymentsService.ValidationException("reason too long")
                }

                val ledgerKey = clubId to bookingId
                val ledger = ledgers.computeIfAbsent(ledgerKey) { BookingLedger() }
                synchronized(ledger) {
                    if (ledger.status == BookingStatus.CANCELLED) {
                        cancelRequests[idemKey] = CancelEntry(clubId, bookingId, actorUserId)
                        PaymentsMetrics.incrementIdempotentHit(
                            metricsProvider,
                            PaymentsMetrics.Path.Cancel,
                        )
                        setResult(PaymentsMetrics.Result.Ok)
                        return@spanSuspending
                    }
                    if (ledger.status != BookingStatus.BOOKED) {
                        PaymentsMetrics.incrementErrors(
                            metricsProvider,
                            PaymentsMetrics.Path.Cancel,
                            PaymentsMetrics.ErrorKind.State,
                        )
                        setResult(PaymentsMetrics.Result.Conflict)
                        throw PaymentsService.ConflictException("cannot cancel in current state")
                    }
                    ledger.status = BookingStatus.CANCELLED
                }
                cancelRequests[idemKey] = CancelEntry(clubId, bookingId, actorUserId)
                setResult(PaymentsMetrics.Result.Ok)
            } catch (unexpected: Throwable) {
                if (
                    unexpected !is PaymentsService.ValidationException &&
                        unexpected !is PaymentsService.ConflictException &&
                        unexpected !is PaymentsService.UnprocessableException
                ) {
                    PaymentsMetrics.incrementErrors(
                        metricsProvider,
                        PaymentsMetrics.Path.Cancel,
                        PaymentsMetrics.ErrorKind.Unexpected,
                    )
                    setResult(PaymentsMetrics.Result.Unexpected)
                }
                throw unexpected
            }
        }
    }

    override suspend fun refund(
        clubId: Long,
        bookingId: UUID,
        amountMinor: Long?,
        idemKey: String,
        actorUserId: Long,
    ): PaymentsService.RefundResult {
        val traceMetadata =
            PaymentsTraceMetadata(
                httpRoute = "/api/clubs/{clubId}/bookings/{bookingId}/refund",
                paymentsPath = PaymentsMetrics.Path.Refund.tag,
                idempotencyKeyPresent = idemKey.isNotBlank(),
                bookingIdMasked = maskBookingId(bookingId),
            )
        return tracer.spanSuspending("payments.refund", traceMetadata) {
            try {
                val existing = refundRequests[idemKey]
                if (existing != null) {
                    if (existing.clubId != clubId || existing.bookingId != bookingId) {
                        PaymentsMetrics.incrementErrors(
                            metricsProvider,
                            PaymentsMetrics.Path.Refund,
                            PaymentsMetrics.ErrorKind.State,
                        )
                        setResult(PaymentsMetrics.Result.Conflict)
                        throw PaymentsService.ConflictException("idempotency key mismatch")
                    }
                    if (existing.amountMinor != (amountMinor ?: existing.amountMinor)) {
                        PaymentsMetrics.incrementErrors(
                            metricsProvider,
                            PaymentsMetrics.Path.Refund,
                            PaymentsMetrics.ErrorKind.State,
                        )
                        setResult(PaymentsMetrics.Result.Conflict)
                        throw PaymentsService.ConflictException("idempotency payload mismatch")
                    }
                    PaymentsMetrics.incrementIdempotentHit(
                        metricsProvider,
                        PaymentsMetrics.Path.Refund,
                    )
                    setResult(PaymentsMetrics.Result.Ok)
                    return@spanSuspending existing.result
                }

                val requested = amountMinor ?: -1L
                if (requested < 0 && amountMinor != null) {
                    PaymentsMetrics.incrementErrors(
                        metricsProvider,
                        PaymentsMetrics.Path.Refund,
                        PaymentsMetrics.ErrorKind.Validation,
                    )
                    setResult(PaymentsMetrics.Result.Validation)
                    throw PaymentsService.ValidationException("amount must be non-negative")
                }

                val ledgerKey = clubId to bookingId
                val ledger = ledgers.computeIfAbsent(ledgerKey) { BookingLedger() }
                val effectiveAmount: Long
                val remainderAfter: Long
                synchronized(ledger) {
                    val remainder = ledger.capturedMinor - ledger.refundedMinor
                    if (amountMinor == null && remainder <= 0) {
                        PaymentsMetrics.incrementErrors(
                            metricsProvider,
                            PaymentsMetrics.Path.Refund,
                            PaymentsMetrics.ErrorKind.State,
                        )
                        setResult(PaymentsMetrics.Result.Conflict)
                        throw PaymentsService.ConflictException("nothing to refund")
                    }
                    if (amountMinor != null && amountMinor < 0) {
                        PaymentsMetrics.incrementErrors(
                            metricsProvider,
                            PaymentsMetrics.Path.Refund,
                            PaymentsMetrics.ErrorKind.Validation,
                        )
                        setResult(PaymentsMetrics.Result.Validation)
                        throw PaymentsService.ValidationException("amount must be non-negative")
                    }
                    val target = amountMinor ?: remainder
                    if (target < 0) {
                        PaymentsMetrics.incrementErrors(
                            metricsProvider,
                            PaymentsMetrics.Path.Refund,
                            PaymentsMetrics.ErrorKind.Validation,
                        )
                        setResult(PaymentsMetrics.Result.Validation)
                        throw PaymentsService.ValidationException("invalid refund amount")
                    }
                    if (remainder <= 0 && target > 0) {
                        PaymentsMetrics.incrementErrors(
                            metricsProvider,
                            PaymentsMetrics.Path.Refund,
                            PaymentsMetrics.ErrorKind.State,
                        )
                        setResult(PaymentsMetrics.Result.Conflict)
                        throw PaymentsService.ConflictException("nothing to refund")
                    }
                    if (target > remainder) {
                        PaymentsMetrics.incrementErrors(
                            metricsProvider,
                            PaymentsMetrics.Path.Refund,
                            PaymentsMetrics.ErrorKind.Unprocessable,
                        )
                        setResult(PaymentsMetrics.Result.Unprocessable)
                        throw PaymentsService.UnprocessableException("exceeds remainder")
                    }
                    effectiveAmount = target
                    ledger.refundedMinor += effectiveAmount
                    remainderAfter = ledger.capturedMinor - ledger.refundedMinor
                }

                PaymentsMetrics.updateRefundRemainder(
                    metricsProvider,
                    clubId,
                    maskBookingId(bookingId),
                    remainderAfter,
                )

                val result = PaymentsService.RefundResult(effectiveAmount)
                refundRequests[idemKey] =
                    RefundEntry(
                        clubId = clubId,
                        bookingId = bookingId,
                        actorUserId = actorUserId,
                        amountMinor = effectiveAmount,
                        result = result,
                    )
                setResult(PaymentsMetrics.Result.Ok)
                setRefundAmount(effectiveAmount)
                result
            } catch (unexpected: Throwable) {
                if (
                    unexpected !is PaymentsService.ValidationException &&
                        unexpected !is PaymentsService.ConflictException &&
                        unexpected !is PaymentsService.UnprocessableException
                ) {
                    PaymentsMetrics.incrementErrors(
                        metricsProvider,
                        PaymentsMetrics.Path.Refund,
                        PaymentsMetrics.ErrorKind.Unexpected,
                    )
                    setResult(PaymentsMetrics.Result.Unexpected)
                }
                throw unexpected
            }
        }
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
        PaymentsMetrics.updateRefundRemainder(
            metricsProvider,
            clubId,
            maskBookingId(bookingId),
            capturedMinor - refundedMinor,
        )
    }

    companion object {
        private const val MAX_REASON_LENGTH = 1024
    }
}
