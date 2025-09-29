package com.example.bot.telegram

import com.example.bot.booking.payments.InvoiceInfo
import com.example.bot.payments.PaymentConfig
import com.example.bot.payments.PaymentsRepository
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.PreCheckoutQuery
import com.pengrad.telegrambot.model.request.LabeledPrice
import com.pengrad.telegrambot.request.AnswerPreCheckoutQuery
import com.pengrad.telegrambot.request.SendInvoice
import com.pengrad.telegrambot.response.SendResponse
import java.util.UUID

/**
 * Telegram adapter for handling payment related callbacks.
 *
 * Минимальная обёртка для Bot Payments: отправка инвойса, pre-checkout ok, фиксация успешного платежа.
 * Поддерживает текущую версию pengrad (9.2.0). Для более новой сигнатуры стоит обновить зависимость.
 */
class PaymentsHandlers(
    private val bot: TelegramBot,
    private val config: PaymentConfig,
    private val paymentsRepo: PaymentsRepository,
) {
    /** Отправка инвойса через Bot Payments API. */
    fun sendInvoice(
        chatId: Long,
        invoice: InvoiceInfo,
    ): SendResponse {
        val price = LabeledPrice("deposit", invoice.totalMinor.toInt())

        @Suppress("DEPRECATION")
        val req =
            SendInvoice(
                chatId,                        // Object chatId в текущей версии — Long подходит
                config.invoiceTitlePrefix,     // title
                "",                            // description (можно расширить по необходимости)
                invoice.payload,               // payload (идемпотентность/сопоставление)
                invoice.currency,              // ISO-валюта
                price,                         // vararg LabeledPrice
            ).providerToken(config.providerToken) // провайдер-токен — через сеттер в 9.2.0

        return bot.execute(req)
    }

    /** Ответ на pre-checkout: подтверждаем. */
    fun handlePreCheckout(query: PreCheckoutQuery) {
        // По умолчанию AnswerPreCheckoutQuery ок'ает запрос; при необходимости добавь .errorMessage(...)
        bot.execute(AnswerPreCheckoutQuery(query.id()))
    }

    /** Обработка успешного платежа: маппим payload → запись и помечаем CAPTURED. */
    suspend fun handleSuccessfulPayment(message: Message) {
        val payload = message.successfulPayment()?.invoicePayload ?: return
        val record = paymentsRepo.findByPayload(payload) ?: return
        // В демо — генерим внешний id; в проде сюда кладём ID от платежного провайдера
        paymentsRepo.markCaptured(record.id, UUID.randomUUID().toString())
    }
}
