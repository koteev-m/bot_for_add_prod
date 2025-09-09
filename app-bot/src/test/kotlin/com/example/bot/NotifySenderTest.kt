package com.example.bot

import com.example.bot.telegram.NotifySender
import com.example.bot.telegram.NotifySender.Media
import com.example.bot.telegram.NotifySender.PhotoContent
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.ResponseParameters
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMediaGroup
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.SendPhoto
import com.pengrad.telegrambot.response.MessagesResponse
import com.pengrad.telegrambot.response.SendResponse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

class NotifySenderTest :
    StringSpec({
        val bot = mockk<TelegramBot>()
        val sender = NotifySender(bot)

        "sendMessage passes thread id and returns ok" {
            val resp = mockk<SendResponse> { every { isOk } returns true }
            val slot = slot<SendMessage>()
            every { bot.execute(capture(slot)) } returns resp
            val result = sender.sendMessage(12345L, "hi", ParseMode.Markdown, threadId = 7)
            result shouldBe NotifySender.Result.Ok
            slot.captured.getParameters()["message_thread_id"] shouldBe 7
        }

        "sendMessage handles retry after" {
            val params = mockk<ResponseParameters> { every { retryAfter() } returns 5 }
            val resp = mockk<SendResponse> {
                every { isOk } returns false
                every { errorCode() } returns 429
                every { description() } returns "Too Many Requests"
                every { parameters() } returns params
            }
            every { bot.execute(any<SendMessage>()) } returns resp
            val result = sender.sendMessage(1L, "x")
            result shouldBe NotifySender.Result.RetryAfter(5)
        }

        "sendMediaGroup falls back to sequential sendPhoto" {
            val media = listOf(Media(PhotoContent.Url("u1")), Media(PhotoContent.Url("u2")))
            val groupResp = mockk<MessagesResponse> {
                every { isOk } returns false
                every { errorCode() } returns 400
                every { description() } returns "thread error"
                every { parameters() } returns null
            }
            val photoResp = mockk<SendResponse> { every { isOk } returns true }
            var groupReq: SendMediaGroup? = null
            val photoCaptures = mutableListOf<SendPhoto>()
            every { bot.execute(any<BaseRequest<*, *>>()) } answers {
                val req = firstArg<BaseRequest<*, *>>()
                when (req) {
                    is SendMediaGroup -> {
                        groupReq = req
                        groupResp
                    }
                    is SendPhoto -> {
                        photoCaptures += req
                        photoResp
                    }
                    else -> photoResp
                }
            }
            val result = sender.sendMediaGroup(10L, media, threadId = 9)
            result shouldBe NotifySender.Result.Ok
            groupReq!!.getParameters()["message_thread_id"] shouldBe 9
            photoCaptures.size shouldBe 2
        }
    })
