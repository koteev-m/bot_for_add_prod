package com.example.bot.guestlists

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object QrGuestListCodec {
    data class Decoded(
        val listId: Long,
        val entryId: Long,
        val issuedAt: Instant,
    )

    private const val PAYLOAD_PARTS = 3
    private const val TOKEN_PARTS = 4

    fun encode(
        listId: Long,
        entryId: Long,
        issuedAt: Instant,
        secret: String,
    ): String {
        require(secret.isNotBlank()) { "secret must not be blank" }
        val timestamp = issuedAt.epochSecond
        val payload =
            buildString {
                append(listId)
                append(':')
                append(entryId)
                append(':')
                append(timestamp)
            }
        val signature = sign(payload, secret)
        val token =
            buildString {
                append(payload)
                append(':')
                append(Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
            }
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(token.toByteArray(StandardCharsets.UTF_8))
    }

    fun verify(
        token: String,
        now: Instant,
        ttl: Duration,
        secret: String,
    ): Decoded? {
        val parsed =
            if (secret.isBlank() || ttl.isNegative || ttl.isZero) {
                null
            } else {
                parseToken(token)
            }
        return if (parsed != null) {
            val expectedSignature = sign(parsed.payload, secret)
            val signatureValid = MessageDigest.isEqual(expectedSignature, parsed.signature)
            val withinWindow = !parsed.issuedAt.isAfter(now) && Duration.between(parsed.issuedAt, now) <= ttl
            if (signatureValid && withinWindow) {
                Decoded(parsed.listId, parsed.entryId, parsed.issuedAt)
            } else {
                null
            }
        } else {
            null
        }
    }

    private fun sign(
        payload: String,
        secret: String,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(key)
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
    }

    private data class ParsedToken(
        val payload: String,
        val listId: Long,
        val entryId: Long,
        val issuedAt: Instant,
        val signature: ByteArray,
    )

    private fun parseToken(token: String): ParsedToken? {
        val decoded = runCatching { Base64.getUrlDecoder().decode(token) }.getOrNull()
        val components = decoded?.toString(StandardCharsets.UTF_8)?.split(':')
        val payloadParts = components?.takeIf { it.size == TOKEN_PARTS }?.take(PAYLOAD_PARTS)
        val payload = payloadParts?.joinToString(":")
        val signature = components?.lastOrNull()?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }
        val listId = payloadParts?.getOrNull(0)?.toLongOrNull()
        val entryId = payloadParts?.getOrNull(1)?.toLongOrNull()
        val issuedSeconds = payloadParts?.getOrNull(2)?.toLongOrNull()
        val issuedAt = issuedSeconds?.let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() }
        return if (payload != null && signature != null) {
            if (listId != null && entryId != null && issuedAt != null) {
                ParsedToken(payload, listId, entryId, issuedAt, signature)
            } else {
                null
            }
        } else {
            null
        }
    }
}
