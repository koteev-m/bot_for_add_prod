package com.example.bot.routes

import com.example.bot.di.notifyModule
import com.example.bot.notifications.NotifyMessage
import com.example.bot.notifications.NotifyMethod
import com.example.bot.notifications.ParseMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.error.NoBeanDefFoundException
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin

class NotifyRoutesWiringTest : StringSpec({
    "notify routes enqueue transactional message under /api" {
        lateinit var txService: TxNotifyService

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Koin) { modules(notifyModule) }

                notifyRoutes(
                    tx = get<TxNotifyService>().also { txService = it },
                    campaigns = get(),
                )
            }

            val payload =
                Json.encodeToString(
                    NotifyMessage(
                        chatId = 1,
                        messageThreadId = null,
                        method = NotifyMethod.TEXT,
                        text = "hello",
                        parseMode = ParseMode.MARKDOWNV2,
                        photoUrl = null,
                        album = null,
                        buttons = null,
                        dedupKey = null,
                    ),
                )

            val response =
                client.post("/api/notify/tx") {
                    setBody(TextContent(payload, ContentType.Application.Json))
                }

            response.status shouldBe HttpStatusCode.Accepted
            response.bodyAsText() shouldBe "{\"status\":\"queued\"}"
            txService.size() shouldBe 1
        }
    }

    "notify routes fail fast when TxNotifyService binding missing" {
        shouldThrow<NoBeanDefFoundException> {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(Koin) { modules(module {}) }

                    notifyRoutes(
                        tx = get(),
                        campaigns = get(),
                    )
                }
            }
        }
    }
})
