package com.example.bot.webhook

import io.ktor.server.application.ApplicationCall

/**
 * HTTP header containing the secret token sent by Telegram on each webhook call.
 */
const val TELEGRAM_SECRET_HEADER: String = "X-Telegram-Bot-Api-Secret-Token"

/**
 * Ensures that the secret token in the request matches [expected].
 * Throws [UnauthorizedWebhook] if the token is missing or invalid.
 */
suspend fun ApplicationCall.verifyWebhookSecret(expected: String) {
    val provided = request.headers[TELEGRAM_SECRET_HEADER]
    if (provided == null || provided != expected) throw UnauthorizedWebhook
}

/** Exception used to interrupt request processing after an unauthorized webhook. */
object UnauthorizedWebhook : RuntimeException()

