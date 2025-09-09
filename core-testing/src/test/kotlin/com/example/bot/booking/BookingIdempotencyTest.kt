package com.example.bot.booking

import com.example.bot.data.booking.InMemoryBookingRepository
import com.example.bot.data.outbox.InMemoryOutboxService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class BookingIdempotencyTest : StringSpec({
    "repeated confirm with same key returns same booking" {
        val repo = InMemoryBookingRepository()
        val outbox = InMemoryOutboxService()
        val service = BookingService(repo, repo, outbox)
        val event = EventDto(1, 1, Instant.parse("2025-01-01T20:00:00Z"), Instant.parse("2025-01-01T23:00:00Z"))
        val table = TableDto(1, 1, 4, BigDecimal("10"), true)
        repo.seed(event, table)
        val req = ConfirmRequest(null, 1, event.startUtc, 1, 2, null, null, null)
        val first = service.confirm(req, "key")
        val second = service.confirm(req, "key")
        (first as Either.Right).value.id shouldBe (second as Either.Right).value.id
    }
})
