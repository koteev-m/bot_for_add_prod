package com.example.bot.webapp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val TEST_BOT_TOKEN = "123:ABC"

internal object WebAppInitDataTestHelper {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class TestUser(
        val id: Long,
        val username: String? = null,
        @SerialName("first_name") val firstName: String? = null,
        @SerialName("last_name") val lastName: String? = null,
    )

    fun encodeUser(
        id: Long,
        username: String? = null,
        firstName: String? = null,
        lastName: String? = null,
    ): String {
        return json.encodeToString(TestUser.serializer(), TestUser(id, username, firstName, lastName))
    }

    fun createInitData(
        botToken: String,
        rawParams: Map<String, String>,
    ): String {
        val secret =
            hmacSha256(
                "WebAppData".toByteArray(StandardCharsets.UTF_8),
                botToken.toByteArray(StandardCharsets.UTF_8),
            )
        val dataCheck = rawParams.toSortedMap().entries.joinToString("\n") { (key, value) -> "$key=$value" }
        val hash = hmacSha256(dataCheck.toByteArray(StandardCharsets.UTF_8), secret).toHex()
        val encodedPairs =
            rawParams.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
        return "$encodedPairs&hash=$hash"
    }

    private fun hmacSha256(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        for (index in indices) {
            val value = this[index].toInt() and 0xFF
            chars[index * 2] = Character.forDigit(value shr 4, 16)
            chars[index * 2 + 1] = Character.forDigit(value and 0x0F, 16)
        }
        return String(chars)
    }

    private fun encode(value: String): String {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}

class WebAppInitDataVerifierTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `returns verified data when signature valid`() {
        val authDate = clock.instant().minusSeconds(60).epochSecond.toString()
        val parameters =
            linkedMapOf(
                "user" to
                    WebAppInitDataTestHelper.encodeUser(
                        id = 42L,
                        username = "neo",
                        firstName = "Thomas",
                        lastName = "Anderson",
                    ),
                "auth_date" to authDate,
                "query_id" to "AAA",
            )
        val initData =
            WebAppInitDataTestHelper.createInitData(TEST_BOT_TOKEN, parameters)

        val verified = WebAppInitDataVerifier.verify(initData, TEST_BOT_TOKEN, Duration.ofHours(24), clock)

        assertNotNull(verified)
        verified!!
        assertEquals(42L, verified.userId)
        assertEquals("neo", verified.username)
        assertEquals("Thomas", verified.firstName)
        assertEquals("Anderson", verified.lastName)
        assertEquals(Instant.ofEpochSecond(authDate.toLong()), verified.authDate)
        assertEquals(parameters, verified.raw)
    }

    @Test
    fun `returns null when hash invalid`() {
        val parameters =
            linkedMapOf(
                "user" to WebAppInitDataTestHelper.encodeUser(id = 1L),
                "auth_date" to clock.instant().epochSecond.toString(),
            )
        val initData =
            WebAppInitDataTestHelper.createInitData(TEST_BOT_TOKEN, parameters)
        val tampered = initData.replaceRange(initData.length - 1, initData.length, "0")

        val verified = WebAppInitDataVerifier.verify(tampered, TEST_BOT_TOKEN, Duration.ofHours(24), clock)

        assertNull(verified)
    }

    @Test
    fun `returns null when hash missing`() {
        val parameters =
            linkedMapOf(
                "user" to WebAppInitDataTestHelper.encodeUser(id = 5L),
                "auth_date" to clock.instant().epochSecond.toString(),
            )
        val encodedPairs =
            parameters.entries.joinToString("&") { (key, value) ->
                "$key=${java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)}"
            }
        val verified = WebAppInitDataVerifier.verify(encodedPairs, TEST_BOT_TOKEN, Duration.ofHours(24), clock)

        assertNull(verified)
    }

    @Test
    fun `returns null when auth date expired`() {
        val expiredAuthDate = clock.instant().minus(Duration.ofDays(2)).epochSecond.toString()
        val parameters =
            linkedMapOf(
                "user" to WebAppInitDataTestHelper.encodeUser(id = 10L),
                "auth_date" to expiredAuthDate,
            )
        val initData =
            WebAppInitDataTestHelper.createInitData(TEST_BOT_TOKEN, parameters)

        val verified = WebAppInitDataVerifier.verify(initData, TEST_BOT_TOKEN, Duration.ofHours(24), clock)

        assertNull(verified)
    }

    @Test
    fun `returns null when header too long`() {
        val longString = "x".repeat(8205)
        val verified = WebAppInitDataVerifier.verify(longString, TEST_BOT_TOKEN, Duration.ofHours(24), clock)

        assertNull(verified)
    }
}
