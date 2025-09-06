package com.example.bot.checkin

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Utility for encoding and decoding guest list QR codes.
 *
 * QR format: `GL1:<clubId>:<eventId>:E:<entryId>:S:<hmac>` where `hmac`
 * is a Base64URL-encoded HMAC-SHA256 of the base string
 * `GL1:<clubId>:<eventId>:E:<entryId>` using the provided key.
 */
class QrCodec(private val key: ByteArray) {

    /** Data extracted from a QR code. */
    data class Data(val clubId: Long, val eventId: Long, val entryId: Long)

    private fun mac(): Mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(key, algorithm))
    }

    /** Encodes the given [data] into a QR string. */
    fun encode(data: Data): String {
        val base = "GL1:${data.clubId}:${data.eventId}:E:${data.entryId}"
        val hmac = mac().doFinal(base.toByteArray(StandardCharsets.UTF_8))
        val hmacBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac)
        return "$base:S:$hmacBase64"
    }

    /**
     * Decodes and verifies the given [qr] string.
     * Returns [Data] on success or `null` if the QR is invalid or signature mismatch.
     */
    fun decode(qr: String): Data? {
        val parts = qr.split(":")
        if (parts.size != 7) return null
        if (parts[0] != "GL1" || parts[3] != "E" || parts[5] != "S") return null

        val clubId = parts[1].toLongOrNull() ?: return null
        val eventId = parts[2].toLongOrNull() ?: return null
        val entryId = parts[4].toLongOrNull() ?: return null
        val hmacPart = parts[6]

        val base = parts.take(5).joinToString(":")
        val expected = mac().doFinal(base.toByteArray(StandardCharsets.UTF_8))
        val provided = try {
            Base64.getUrlDecoder().decode(hmacPart)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!MessageDigest.isEqual(provided, expected)) return null
        return Data(clubId, eventId, entryId)
    }
}

