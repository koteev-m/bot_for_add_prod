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
        testApplication {
            application { module() }
            val response =
                client.post("/webhook") {
                    headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
                    setBody("{\"updateId\":1,\"message\":{\"text\":\"hi\"}}")
                }
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "hi"
        }
    }
})
