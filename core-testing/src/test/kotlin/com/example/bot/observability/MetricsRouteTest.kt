package com.example.bot.observability

import com.example.bot.plugins.installMetrics
import com.example.bot.routes.observabilityRoutes
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetricsRouteTest {
    @Test
    fun `exposes metrics`() = testApplication {
        val registry = MetricsProvider.prometheusRegistry()
        val provider = MetricsProvider(registry)
        provider.registerBuildInfo("1.0", "test", "abc")
        provider.counter("custom.counter").increment()
        application {
            install(ContentNegotiation) { json() }
            installMetrics(registry)
            routing {
                observabilityRoutes(provider, object : HealthService {
                    override suspend fun health() = HealthReport(CheckStatus.UP, emptyList())
                    override suspend fun readiness() = HealthReport(CheckStatus.UP, emptyList())
                })
            }
        }
        val res = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, res.status)
        val contentType = res.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.contains("text/plain") == true)
        val body = res.bodyAsText()
        assertTrue(body.contains("http_server_requests"))
        assertTrue(body.contains("app_build_info"))
        assertTrue(body.contains("custom_counter_total"))
    }
}

