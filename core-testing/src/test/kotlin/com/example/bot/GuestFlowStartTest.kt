package com.example.bot

import com.example.bot.i18n.BotTexts
import com.example.bot.telegram.GuestFlowHandler
import com.example.bot.telegram.Keyboards
import com.google.gson.Gson
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.mockk

class GuestFlowStartTest : StringSpec({
    val texts = BotTexts()
    val keyboards = Keyboards(texts)
    val sent = mutableListOf<Any>()
    val handler = GuestFlowHandler({ req ->
        sent += req
        mockk<BaseResponse>(relaxed = true)
    }, texts, keyboards)

    "start command sends menu with four buttons" {
        val json = """{"update_id":1,"message":{"message_id":1,"chat":{"id":42},"from":{"id":1,"language_code":"en"},"text":"/start"}}"""
        val update = Gson().fromJson(json, Update::class.java)
        handler.handle(update)
        val req = sent.single() as SendMessage
        req.getParameters()["text"] shouldBe texts.greeting("en")
        val markup = req.getParameters()["reply_markup"] as com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
        val buttons = markup.inlineKeyboard().flatMap { it.toList() }
        buttons.shouldHaveSize(4)
        buttons.forEach { btn -> btn.callbackData?.length?.let { it shouldBeLessThan 64 } }
    }
})
