package com.example.bot.booking.payments

import com.example.bot.booking.BookingService
import com.example.bot.booking.BookingError
import com.example.bot.booking.ConfirmRequest
import com.example.bot.booking.PaymentPolicy
import com.example.bot.booking.Either
import com.example.bot.payments.PaymentConfig
import com.example.bot.payments.PaymentsRepository
import java.math.BigDecimal
import java.util.UUID

/**
 * Service orchestrating booking confirmation with optional payments.
 */
class PaymentsService(
    private val bookingService: BookingService,
    private val paymentsRepo: PaymentsRepository,
    private val config: PaymentConfig,
) {
    /**
     * Starts confirmation flow respecting [policy].
     */
    suspend fun startConfirmation(
        input: ConfirmInput,
        contact: ContactInfo?,
        policy: PaymentPolicy,
        idemKey: String,
    ): Either<BookingError, ConfirmResult> {
        val total = input.minDeposit.multiply(BigDecimal(input.guestsCount))
        return when (policy.mode) {
            PaymentMode.NONE -> {
                val req = ConfirmRequest(
                    holdId = null,
                    clubId = input.clubId,
                    eventStartUtc = input.eventStartUtc,
                    tableId = input.tableId,
                    guestsCount = input.guestsCount,
                    guestUserId = null,
                    guestName = contact?.tgUsername,
                    phoneE164 = contact?.phoneE164,
                )
                when (val res = bookingService.confirm(req, idemKey)) {
                    is Either.Left -> Either.Left(res.value)
                    is Either.Right -> Either.Right(ConfirmResult.Confirmed(res.value))
                }
            }
            PaymentMode.PROVIDER_DEPOSIT -> {
                val totalMinor = total.multiply(BigDecimal(100)).longValueExact()
                val payload = UUID.randomUUID().toString()
                paymentsRepo.createInitiated(
                    bookingId = null,
                    provider = "PROVIDER",
                    currency = policy.currency,
                    amountMinor = totalMinor,
                    payload = payload,
                    idempotencyKey = idemKey,
                )
                val invoice = InvoiceInfo(
                    invoiceId = payload,
                    payload = payload,
                    totalMinor = totalMinor,
                    currency = policy.currency,
                    invoiceLink = null,
                )
                Either.Right(ConfirmResult.PendingPayment(invoice))
            }
            PaymentMode.STARS_DIGITAL -> {
                val totalMinor = total.longValueExact()
                val payload = UUID.randomUUID().toString()
                paymentsRepo.createInitiated(
                    bookingId = null,
                    provider = "STARS",
                    currency = "XTR",
                    amountMinor = totalMinor,
                    payload = payload,
                    idempotencyKey = idemKey,
                )
                val invoice = InvoiceInfo(
                    invoiceId = payload,
                    payload = payload,
                    totalMinor = totalMinor,
                    currency = "XTR",
                    invoiceLink = null,
                )
                Either.Right(ConfirmResult.PendingPayment(invoice))
            }
        }
    }
}

