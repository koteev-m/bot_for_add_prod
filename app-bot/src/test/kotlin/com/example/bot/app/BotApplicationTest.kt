package com.example.bot.app

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

class BotApplicationTest : StringSpec({
    "echoes message" {
        val vars =
            mapOf(
                "BOT_TOKEN" to "x",
                "WEBHOOK_SECRET_TOKEN" to "y",
                "DATABASE_URL" to "jdbc:test",
                "DATABASE_USER" to "user",
                "DATABASE_PASSWORD" to "pass",
                "OWNER_TELEGRAM_ID" to "1",
            )
        vars.forEach { (k, v) -> System.setProperty(k, v) }
        try {
            testApplication {
                val response =
                    client.post("/webhook") {
                        headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
                        setBody("{\"updateId\":1,\"message\":{\"text\":\"hi\"}}")
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "hi"
            }
        } finally {
            vars.keys.forEach { System.clearProperty(it) }
        }
    }
})
