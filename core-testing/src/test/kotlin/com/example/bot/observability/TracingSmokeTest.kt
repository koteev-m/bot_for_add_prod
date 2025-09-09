package com.example.bot.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.bot.plugins.installLogging
import com.example.bot.plugins.installMetrics
import com.example.bot.plugins.installTracing
import com.example.bot.routes.observabilityRoutes
import io.ktor.client.request.get
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class TracingSmokeTest {
    @Test
    fun `tracing disabled`() = testApplication {
        val registry = MetricsProvider.prometheusRegistry()
        val provider = MetricsProvider(registry)
        application {
            install(ContentNegotiation) { json() }
            installMetrics(registry)
            installLogging()
            routing { observabilityRoutes(provider, object : HealthService {
                override suspend fun health() = HealthReport(CheckStatus.UP, emptyList())
                override suspend fun readiness() = HealthReport(CheckStatus.UP, emptyList())
            }) }
        }
        assertEquals(200, client.get("/metrics").status.value)
    }

    @Test
    fun `tracing enabled`() = testApplication {
        val registry = MetricsProvider.prometheusRegistry()
        val provider = MetricsProvider(registry)
        val exporter = InMemorySpanExporter.create()
        val tracer = TracingProvider.create(exporter).tracer
        val list = ListAppender<ILoggingEvent>()
        val logger = LoggerFactory.getLogger("io.ktor.test") as Logger
        list.start(); logger.addAppender(list)
        application {
            install(ContentNegotiation) { json() }
            installMetrics(registry)
            installLogging()
            installTracing(tracer)
            routing { observabilityRoutes(provider, object : HealthService {
                override suspend fun health() = HealthReport(CheckStatus.UP, emptyList())
                override suspend fun readiness() = HealthReport(CheckStatus.UP, emptyList())
            }) }
        }
        client.get("/metrics")
        assertTrue(exporter.finishedSpanItems.isNotEmpty(), "spans")
        val event = list.list.firstOrNull { it.mdcPropertyMap.containsKey("traceId") }
        assertTrue(event != null && event.mdcPropertyMap["traceId"]?.isNotEmpty() == true)
        logger.detachAppender(list)
    }
}

