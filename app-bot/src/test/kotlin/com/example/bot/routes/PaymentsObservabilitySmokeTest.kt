package com.example.bot.routes

import com.example.bot.di.DefaultPaymentsService
import com.example.bot.di.PaymentsService
import com.example.bot.observability.MetricsProvider
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.plugins.metricsRoute
import com.example.bot.telemetry.PaymentsMetrics
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.ktor.server.routing.routing
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.util.UUID

private const val TEST_TOKEN = "test-token"

class PaymentsObservabilitySmokeTest : StringSpec() {
    private val user = TelegramMiniUser(id = 999L, username = "observer")

    override suspend fun beforeEach(testCase: TestCase) {
        PaymentsMetrics.resetForTest()
        overrideMiniAppValidatorForTesting { _, _ -> user }
    }

    override suspend fun afterEach(testCase: TestCase, result: TestResult) {
        PaymentsMetrics.resetForTest()
        resetMiniAppValidator()
    }

    init {
        "exports metrics for cancel idempotency and refund errors" {
            val registry = MetricsProvider.prometheusRegistry()
            val metricsProvider = MetricsProvider(registry)
            val service = DefaultPaymentsService(
                finalizeService = FakeFinalizeService(),
                metricsProvider = metricsProvider,
                tracer = null,
            )

            val bookingId = UUID.randomUUID()
            service.seedLedger(
                clubId = 1L,
                bookingId = bookingId,
                status = "BOOKED",
                capturedMinor = 1_500,
                refundedMinor = 0,
            )

            testApplication {
                application {
                    configurePaymentsTestApp(
                        paymentsService = service,
                        metricsProvider = metricsProvider,
                        registry = registry,
                    )
                }

                val cancelPayload = Json.encodeToString(CancelRequest(reason = "test"))
                val cancelUrl = "/api/clubs/1/bookings/${bookingId}/cancel"
                val refundUrl = "/api/clubs/1/bookings/${bookingId}/refund"

                client.post(cancelUrl) {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    header("Idempotency-Key", "idem-cancel")
                    header("X-Telegram-Init-Data", "stub")
                    setBody(cancelPayload)
                }.status shouldBe HttpStatusCode.OK

                client.post(cancelUrl) {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    header("Idempotency-Key", "idem-cancel")
                    header("X-Telegram-Init-Data", "stub")
                    setBody(cancelPayload)
                }.status shouldBe HttpStatusCode.OK

                client.post(refundUrl) {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    header("Idempotency-Key", "idem-refund")
                    header("X-Telegram-Init-Data", "stub")
                    setBody(Json.encodeToString(RefundRequest(amountMinor = 2_000)))
                }.status shouldBe HttpStatusCode.UnprocessableEntity

                val metricsBody =
                    client.get("/metrics") {
                        header("X-Telegram-Init-Data", "stub")
                    }.bodyAsText()

                metricsBody.shouldContain("payments_cancel_duration_seconds_count{path=\"cancel\",result=\"ok\",source=\"miniapp\"")
                metricsBody.shouldContain("payments_idempotent_hit_total{path=\"cancel\"")
                val errorLineOne = "payments_errors_total{kind=\"unprocessable\",path=\"refund\""
                val errorLineTwo = "payments_errors_total{path=\"refund\",kind=\"unprocessable\""
                (metricsBody.contains(errorLineOne) || metricsBody.contains(errorLineTwo)) shouldBe true
            }
        }
    }
}

private fun Application.configurePaymentsTestApp(
    paymentsService: PaymentsService,
    metricsProvider: MetricsProvider,
    registry: PrometheusMeterRegistry,
) {
    install(ContentNegotiation) { json() }
    install(CallId) {
        generate { UUID.randomUUID().toString() }
    }
    install(Koin) {
        modules(
            module {
                single { paymentsService }
                single { metricsProvider }
            },
        )
    }
    routing {
        withMiniAppAuth { TEST_TOKEN }
        paymentsCancelRefundRoutes { TEST_TOKEN }
        metricsRoute(registry)
    }
}

private class FakeFinalizeService : com.example.bot.payments.finalize.PaymentsFinalizeService {
    override suspend fun finalize(
        clubId: Long,
        bookingId: UUID,
        paymentToken: String?,
        idemKey: String,
        actorUserId: Long,
    ): com.example.bot.payments.finalize.PaymentsFinalizeService.FinalizeResult {
        return com.example.bot.payments.finalize.PaymentsFinalizeService.FinalizeResult(paymentStatus = "TEST")
    }
}

@kotlinx.serialization.Serializable
private data class CancelRequest(val reason: String? = null)

@kotlinx.serialization.Serializable
private data class RefundRequest(val amountMinor: Long? = null)
