package com.example.ktor

import com.example.bot.observability.DefaultHealthService
import com.example.bot.observability.MetricsProvider
import com.example.bot.plugins.installMetrics
import com.example.bot.routes.observabilityRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutesSmokeTest {
    @Test
    fun `health and metrics exposed`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            val registry = MetricsProvider.prometheusRegistry()
            installMetrics(registry)
            routing { observabilityRoutes(MetricsProvider(registry), DefaultHealthService()) }
            registry.counter("custom.counter").increment()
        }
        val health = client.get("/health")
        assertEquals(HttpStatusCode.OK, health.status)
        val metrics = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, metrics.status)
        val ctype = metrics.headers[HttpHeaders.ContentType]
        assertTrue(ctype?.contains("text/plain") == true)
        val body = metrics.bodyAsText()
        assertTrue(body.contains("http_server_requests_seconds"))
        assertTrue(body.contains("custom_counter"))
    }
}
