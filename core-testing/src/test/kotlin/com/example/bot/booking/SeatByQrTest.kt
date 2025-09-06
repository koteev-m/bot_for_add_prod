package com.example.bot.booking

import com.example.bot.data.booking.InMemoryBookingRepository
import com.example.bot.data.outbox.InMemoryOutboxService
import com.example.bot.booking.ConfirmRequest
import com.example.bot.booking.EventDto
import com.example.bot.booking.TableDto
import com.example.bot.booking.Either
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class SeatByQrTest : StringSpec({
    "seat moves booking to SEATED" {
        val repo = InMemoryBookingRepository()
        val outbox = InMemoryOutboxService()
        val service = BookingService(repo, repo, outbox)
        val event = EventDto(1, 1, Instant.now(), Instant.now().plusSeconds(3600))
        val table = TableDto(1, 1, 2, BigDecimal("10"), true)
        repo.seed(event, table)
        val req = ConfirmRequest(null, 1, event.startUtc, 1, 1, null, null, null)
        val booking = (service.confirm(req, "a") as Either.Right).value
        val res = service.seatByQr(booking.qrSecret, 1, "b")
        (res as Either.Right).value.status shouldBe "SEATED"
    }
})
