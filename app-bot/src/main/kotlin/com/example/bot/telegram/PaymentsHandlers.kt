package com.example.bot.telegram

import com.example.bot.booking.payments.InvoiceInfo
import com.example.bot.payments.PaymentConfig
import com.example.bot.payments.PaymentsRepository
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.PreCheckoutQuery
import com.pengrad.telegrambot.request.AnswerPreCheckoutQuery
import com.pengrad.telegrambot.request.SendInvoice
import com.pengrad.telegrambot.response.SendResponse
import java.util.UUID

/**
 * Telegram adapter for handling payment related callbacks.
 *
 * This implementation is intentionally minimal and does not cover all edge
 * cases of the Telegram Bot Payments API; it is sufficient for unit testing
 * scenarios within this repository.
 */
class PaymentsHandlers(
    private val bot: TelegramBot,
    private val config: PaymentConfig,
    private val paymentsRepo: PaymentsRepository,
) {
    /** Sends invoice using Bot Payments API. */
    fun sendInvoice(chatId: Long, invoice: InvoiceInfo): SendResponse {
        val price =
            com.pengrad.telegrambot.model.request
                .LabeledPrice("deposit", invoice.totalMinor.toInt())
        val req =
            SendInvoice(chatId, config.invoiceTitlePrefix, "", invoice.payload, invoice.currency, price)
                .providerToken(config.providerToken)
        return bot.execute(req)
    }

    /** Handles pre-checkout query; currently always approves. */
    fun handlePreCheckout(query: PreCheckoutQuery) {
        bot.execute(AnswerPreCheckoutQuery(query.id()))
    }

    /** Handles successful payment message. */
    suspend fun handleSuccessfulPayment(message: Message) {
        val payload = message.successfulPayment()?.invoicePayload ?: return
        val payment = paymentsRepo.findByPayload(payload) ?: return
        paymentsRepo.markCaptured(payment.id, UUID.randomUUID().toString())
    }
}
