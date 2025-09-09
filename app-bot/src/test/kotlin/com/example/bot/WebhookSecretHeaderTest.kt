package com.example.bot

import com.example.bot.dedup.UpdateDeduplicator
import com.example.bot.telegram.TelegramClient
import com.example.bot.webhook.UnauthorizedWebhook
import com.example.bot.webhook.webhookRoute
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

class WebhookSecretHeaderTest : StringSpec({
    val secret = "token"
    val dedup = UpdateDeduplicator()
    val tgClient = TelegramClient("x")

    fun io.ktor.server.application.Application.testModule() {
        install(ContentNegotiation) { json() }
        install(StatusPages) {
            exception<UnauthorizedWebhook> { call, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Bad webhook secret"))
            }
        }
        routing { webhookRoute(secret, dedup, handler = { null }, client = tgClient) }
    }

    "missing header returns 401" {
        testApplication {
            application { testModule() }
            val response = client.post("/webhook") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("{\"update_id\":1}")
            }
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "wrong header returns 401" {
        testApplication {
            application { testModule() }
            val response = client.post("/webhook") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header("X-Telegram-Bot-Api-Secret-Token", "bad")
                setBody("{\"update_id\":1}")
            }
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "valid header returns 200" {
        testApplication {
            application { testModule() }
            val response = client.post("/webhook") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header("X-Telegram-Bot-Api-Secret-Token", secret)
                setBody("{\"update_id\":1}")
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }
})
