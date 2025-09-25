package com.example.bot.guestlists

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class QrGuestListCodecTest {
    private val secret = "s3cr3t"
    private val issuedAt = Instant.parse("2024-05-01T12:00:00Z")
    private val ttl = Duration.ofHours(12)

    @Test
    fun `encode and verify happy path`() {
        val token = QrGuestListCodec.encode(12345, 678, issuedAt, secret)
        val decoded = QrGuestListCodec.verify(token, issuedAt.plusSeconds(10), ttl, secret)
        assertNotNull(decoded)
        decoded!!
        assertEquals(12345L, decoded.listId)
        assertEquals(678L, decoded.entryId)
        assertEquals(issuedAt, decoded.issuedAt)
    }

    @Test
    fun `verify rejects tokens with bad prefix or parts`() {
        val validToken = QrGuestListCodec.encode(1, 1, issuedAt, secret)
        val invalidTokens =
            listOf(
                "",
                "GL",
                "GL:1:2:3",
                "GL:1:2:3:ab",
                "GL::2:3:${"a".repeat(64)}",
                validToken.removePrefix("GL:"),
            )
        val now = issuedAt.plusSeconds(5)
        for (token in invalidTokens) {
            assertNull(QrGuestListCodec.verify(token, now, ttl, secret))
        }
    }

    @Test
    fun `verify rejects tokens with invalid numeric fields`() {
        val baseHmac = "a".repeat(64)
        val tokens =
            listOf(
                "GL:0:1:100:$baseHmac",
                "GL:-1:2:100:$baseHmac",
                "GL:1:-2:100:$baseHmac",
                "GL:1:2:-100:$baseHmac",
                "GL:abc:2:100:$baseHmac",
                "GL:1:xyz:100:$baseHmac",
                "GL:1:2:abc:$baseHmac",
                "GL:999999999999999999999:2:100:$baseHmac",
            )
        val now = issuedAt.plusSeconds(5)
        for (token in tokens) {
            assertNull(QrGuestListCodec.verify(token, now, ttl, secret))
        }
    }

    @Test
    fun `verify rejects token with incorrect hmac`() {
        val token = QrGuestListCodec.encode(10, 20, issuedAt, secret)
        val tampered = token.dropLast(1) + if (token.last() == 'a') 'b' else 'a'
        assertNull(QrGuestListCodec.verify(tampered, issuedAt.plusSeconds(1), ttl, secret))
    }

    @Test
    fun `verify rejects expired token`() {
        val token = QrGuestListCodec.encode(10, 20, issuedAt, secret)
        val now = issuedAt.plus(ttl).plusSeconds(1)
        assertNull(QrGuestListCodec.verify(token, now, ttl, secret))
    }

    @Test
    fun `verify rejects token issued beyond allowed skew`() {
        val futureIssued = issuedAt.plus(Duration.ofMinutes(5))
        val token = QrGuestListCodec.encode(10, 20, futureIssued, secret)
        val now = issuedAt
        assertNull(QrGuestListCodec.verify(token, now, ttl, secret))
    }

    @Test
    fun `verify accepts uppercase hmac`() {
        val token = QrGuestListCodec.encode(123, 456, issuedAt, secret)
        val prefixLength = token.length - 64
        val upperToken = token.substring(0, prefixLength) + token.substring(prefixLength).uppercase()
        val decoded = QrGuestListCodec.verify(upperToken, issuedAt.plusSeconds(2), ttl, secret)
        assertNotNull(decoded)
        decoded!!
        assertEquals(123L, decoded.listId)
        assertEquals(456L, decoded.entryId)
    }

    @Test
    fun `token length stays under 100 characters`() {
        val token = QrGuestListCodec.encode(987654321L, 123456789L, issuedAt, secret)
        assertTrue(token.length < 100)
    }

    @Test
    fun `verify allows tokens within allowed skew`() {
        val skew = Duration.ofMinutes(2)
        val futureIssued = issuedAt.plus(skew)
        val token = QrGuestListCodec.encode(55, 77, futureIssued, secret)
        val decoded = QrGuestListCodec.verify(token, issuedAt, ttl, secret, maxClockSkew = skew)
        assertNotNull(decoded)
    }

    private fun deriveKey(secretValue: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secretValue.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal("QrGuestList".toByteArray(StandardCharsets.UTF_8))
    }

    @Test
    fun `verify rejects token with valid shape but wrong derived hmac`() {
        val message = "1:2:100"
        val derivedKey = deriveKey(secret)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(derivedKey, "HmacSHA256"))
        val validHex =
            mac
                .doFinal(message.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte ->
                    val value = byte.toInt() and 0xFF
                    val high = "0123456789abcdef"[value ushr 4]
                    val low = "0123456789abcdef"[value and 0x0F]
                    "$high$low"
                }
        val alteredMessageToken = "GL:1:2:101:$validHex"
        assertNull(QrGuestListCodec.verify(alteredMessageToken, issuedAt.plusSeconds(5), ttl, secret))
    }
}
