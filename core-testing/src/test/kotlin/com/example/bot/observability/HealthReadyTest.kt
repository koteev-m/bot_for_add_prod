package com.example.bot.observability

import com.example.bot.plugins.MigrationState
import com.example.bot.plugins.installMetrics
import com.example.bot.routes.observabilityRoutes
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HealthReadyTest {
    @Test
    fun `health ok and ready down`() =
        testApplication {
            val registry = MetricsProvider.prometheusRegistry()
            val provider = MetricsProvider(registry)
            MigrationState.migrationsApplied = true
            application {
                install(ContentNegotiation) { json() }
                installMetrics(registry)
                routing {
                    observabilityRoutes(
                        provider,
                        object : HealthService {
                            override suspend fun health() =
                                HealthReport(
                                    CheckStatus.UP,
                                    listOf(HealthCheck("db", CheckStatus.UP)),
                                )

                            override suspend fun readiness() =
                                HealthReport(
                                    CheckStatus.DOWN,
                                    listOf(HealthCheck("outbox", CheckStatus.DOWN)),
                                )
                        },
                    )
                }
            }
            val h = client.get("/health")
            assertEquals(HttpStatusCode.OK, h.status)
            assertTrue(h.bodyAsText().contains("\"status\":\"UP\""))
            val r = client.get("/ready")
            assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
            assertTrue(r.bodyAsText().contains("outbox"))
        }
}
