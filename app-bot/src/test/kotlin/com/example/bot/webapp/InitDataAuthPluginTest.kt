package com.example.bot.webapp

import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration

class InitDataAuthPluginTest {
    @Test
    fun `responds with 200 for valid init data`() =
        testApplication {
            val currentEpochSeconds = System.currentTimeMillis() / 1000L
            val validHeader =
                WebAppInitDataTestHelper.createInitData(
                    TEST_BOT_TOKEN,
                    linkedMapOf(
                        "user" to WebAppInitDataTestHelper.encodeUser(id = 7L, username = "agent"),
                        "auth_date" to currentEpochSeconds.toString(),
                    ),
                )
            application {
                routing {
                    route("/webapp-test") {
                        install(InitDataAuthPlugin) {
                            botTokenProvider = { TEST_BOT_TOKEN }
                            maxAge = Duration.ofHours(24)
                            clock = Clock.systemUTC()
                        }
                        get {
                            if (call.attributes.contains(InitDataPrincipalKey)) {
                                call.respond(HttpStatusCode.OK)
                            } else {
                                call.respond(HttpStatusCode.Unauthorized)
                            }
                        }
                    }
                }
            }
            val success = client.get("/webapp-test") { header("X-Telegram-Init-Data", validHeader) }
            success.status shouldBe HttpStatusCode.OK

            val missing = client.get("/webapp-test")
            missing.status shouldBe HttpStatusCode.Unauthorized

            val invalid =
                client.get("/webapp-test") {
                    header("X-Telegram-Init-Data", validHeader.replace("a", "b"))
                }
            invalid.status shouldBe HttpStatusCode.Unauthorized
        }
}
