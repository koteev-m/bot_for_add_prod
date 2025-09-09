package com.example.bot.payments

import com.example.bot.booking.ConfirmInput
import com.example.bot.booking.InvoiceInfo
import com.example.bot.booking.PaymentMode
import com.example.bot.booking.PaymentPolicy
import java.math.BigDecimal
import java.util.UUID

/**
 * Simple factory for generating invoice information used in tests.
 */
object PaymentsService {
    /**
     * Creates invoice based on provided [input] and [policy].
     */
    suspend fun createInvoice(input: ConfirmInput, policy: PaymentPolicy, idemKey: String): InvoiceInfo {
        val total = input.minDeposit.multiply(BigDecimal(input.guestsCount))
        val id = UUID.randomUUID().toString()
        val currency = if (policy.mode == PaymentMode.STARS_DIGITAL) "XTR" else policy.currency
        val totalMinor = total.multiply(BigDecimal(100)).toInt()
        return InvoiceInfo(
            invoiceId = id,
            payload = idemKey,
            totalMinor = totalMinor,
            currency = currency,
            startParameter = id.take(8),
            createLink = if (policy.splitPay) null else null,
        )
    }
}
