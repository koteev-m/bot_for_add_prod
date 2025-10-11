package com.example.bot.webapp

import com.example.bot.plugins.InitDataAuth
import com.example.bot.plugins.InitDataAuth.installInitDataAuth
import com.example.bot.security.auth.TelegramUser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class InitDataAuthTest : FunSpec({
    val botToken = "test-token"
    val validInitData = "valid-init-data"

    beforeTest {
        InitDataAuth.overrideValidatorForTesting { raw, token ->
            if (token == botToken && raw == validInitData) {
                TelegramUser(id = 42L, username = "tester")
            } else {
                null
            }
        }
    }

    afterTest {
        InitDataAuth.resetValidator()
    }

    test("responds 200 when initData is valid") {
        testApplication {
            environment {
                config = MapApplicationConfig("app.BOT_TOKEN" to botToken)
            }
            application {
                install(ContentNegotiation) { json() }
                installInitDataAuth()
                webAppRoutes()
            }

            val response =
                client.get("/miniapp/me") {
                    header("X-Telegram-Init-Data", validInitData)
                }

            response.status shouldBe HttpStatusCode.OK
            val payload = Json.decodeFromString<JsonElement>(response.bodyAsText()).jsonObject
            payload["ok"]?.jsonPrimitive?.boolean shouldBe true
            val user = payload["user"]?.jsonObject
            user?.get("id")?.jsonPrimitive?.long shouldBe 42L
        }
    }

    test("responds 401 when initData is invalid") {
        testApplication {
            environment {
                config = MapApplicationConfig("app.BOT_TOKEN" to botToken)
            }
            application {
                install(ContentNegotiation) { json() }
                installInitDataAuth()
                webAppRoutes()
            }

            val response =
                client.get("/miniapp/me") {
                    header("X-Telegram-Init-Data", "bad-init-data")
                }

            response.status shouldBe HttpStatusCode.Unauthorized
            val payload = Json.decodeFromString<JsonElement>(response.bodyAsText()).jsonObject
            payload["error"]?.jsonPrimitive?.content shouldBe "unauthorized"
        }
    }
})
