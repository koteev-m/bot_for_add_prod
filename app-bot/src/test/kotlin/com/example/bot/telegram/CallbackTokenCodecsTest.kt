package com.example.bot.telegram

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ClubTokenCodecTest {
    @Test
    fun `encode decode round trip`() {
        val original = 42L
        val token = ClubTokenCodec.encode(original)
        assertEquals(original, ClubTokenCodec.decode(token))
        assertTrue(token.length <= 59)
        assertTrue(("club:$token").length < 64)
    }

    @Test
    fun `encode rejects negative ids`() {
        assertFailsWith<IllegalArgumentException> { ClubTokenCodec.encode(-1) }
    }

    @Test
    fun `decode rejects invalid prefix`() {
        assertNull(ClubTokenCodec.decode("x123"))
    }

    @Test
    fun `decode rejects empty payload`() {
        assertNull(ClubTokenCodec.decode("c"))
    }

    @Test
    fun `decode rejects invalid characters`() {
        assertNull(ClubTokenCodec.decode("c!@#"))
    }

    @Test
    fun `decode rejects negative payload`() {
        val negative = "c-1"
        assertNull(ClubTokenCodec.decode(negative))
    }

    @Test
    fun `decode rejects oversized token`() {
        val oversized = "c" + "a".repeat(60)
        assertTrue(oversized.length > 59)
        assertNull(ClubTokenCodec.decode(oversized))
    }
}

class NightTokenCodecTest {
    @Test
    fun `encode decode round trip without nanos`() {
        val clubId = 512L
        val start = Instant.parse("2024-03-10T18:45:00Z")
        val token = NightTokenCodec.encode(clubId, start)
        val decoded = NightTokenCodec.decode(token)
        assertEquals(clubId, decoded?.first)
        assertEquals(start, decoded?.second)
        assertTrue(("night:$token").length < 64)
    }

    @Test
    fun `encode decode round trip with nanos`() {
        val clubId = 7L
        val start = Instant.ofEpochSecond(1_700_000_000L, 123_000_000L)
        val token = NightTokenCodec.encode(clubId, start)
        val decoded = NightTokenCodec.decode(token)
        assertEquals(clubId, decoded?.first)
        assertEquals(start, decoded?.second)
    }

    @Test
    fun `encode rejects negative club id`() {
        assertFailsWith<IllegalArgumentException> { NightTokenCodec.encode(-1, Instant.EPOCH) }
    }

    @Test
    fun `decode rejects invalid prefix`() {
        assertNull(NightTokenCodec.decode("xabc"))
    }

    @Test
    fun `decode rejects missing separator`() {
        assertNull(NightTokenCodec.decode("n123"))
    }

    @Test
    fun `decode rejects empty parts`() {
        assertNull(NightTokenCodec.decode("n-"))
        assertNull(NightTokenCodec.decode("n123-"))
        assertNull(NightTokenCodec.decode("n-abc"))
    }

    @Test
    fun `decode rejects invalid numbers`() {
        assertNull(NightTokenCodec.decode("nzz-!"))
        assertNull(NightTokenCodec.decode("nzz-abc~!"))
    }

    @Test
    fun `decode rejects negative nanos`() {
        assertNull(NightTokenCodec.decode("nzz-abc~-1"))
    }

    @Test
    fun `decode rejects oversized token`() {
        val oversized = "n" + "a".repeat(60)
        assertTrue(oversized.length > 58)
        assertNull(NightTokenCodec.decode(oversized))
    }

    @Test
    fun `night callback data stays short for boundary values`() {
        val token = NightTokenCodec.encode(Long.MAX_VALUE, Instant.MAX)
        assertTrue(token.length <= 58)
        val callback = "night:$token"
        assertTrue(callback.length < 64)
    }
}

class MenuCallbacksHandlerTokensTest {
    @Test
    fun `club callback helper embeds token`() {
        val clubId = 999L
        val callback = MenuCallbacksHandler.clubCallbackData(clubId)
        assertTrue(callback.startsWith("club:"))
        assertTrue(callback.length < 64)
        val token = callback.removePrefix("club:")
        assertEquals(clubId, ClubTokenCodec.decode(token))
    }

    @Test
    fun `night callback helper embeds token`() {
        val start = Instant.parse("2024-05-01T21:00:00Z")
        val callback = MenuCallbacksHandler.nightCallbackData(77L, start)
        assertTrue(callback.startsWith("night:"))
        assertTrue(callback.length < 64)
        val token = callback.removePrefix("night:")
        val decoded = NightTokenCodec.decode(token)
        assertEquals(77L, decoded?.first)
        assertEquals(start, decoded?.second)
    }
}
