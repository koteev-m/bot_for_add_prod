package com.example.bot.routes

import com.example.bot.observability.MetricsProvider
import com.example.bot.payments.finalize.PaymentsFinalizeService
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.promo.PromoAttributionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.koin.ktor.ext.getKoin
import org.koin.ktor.ext.inject
import java.util.UUID
import kotlin.time.TimeSource

private val logger = LoggerFactory.getLogger("PaymentsFinalizeRoute")

@Serializable
data class FinalizeRequest(
    val bookingId: String,
    val paymentToken: String? = null,
    val promoDeepLink: String? = null,
)

@Serializable
data class FinalizeResponse(
    val status: String,
    val bookingId: String,
    val paymentStatus: String,
    val promoAttached: Boolean,
)

fun Application.paymentsFinalizeRoutes(miniAppBotTokenProvider: () -> String) {
    val enabled = System.getenv("FINALIZE_ENABLED")?.toBooleanStrictOrNull() ?: true
    if (!enabled) {
        logger.info("payments.finalize route disabled via FINALIZE_ENABLED=false")
        return
    }
    logger.info("payments.finalize route enabled")

    val paymentsService by inject<PaymentsFinalizeService>()
    val koin = getKoin()

    routing {
        route("/api/clubs/{clubId}/bookings") {
            withMiniAppAuth(miniAppBotTokenProvider)

            post("/finalize") {
                val callId = call.callId ?: "unknown"
                val metricsProvider = koin.getOrNull<MetricsProvider>()
                val started = TimeSource.Monotonic.markNow()

                fun recordDuration() {
                    metricsProvider?.timer("payments.finalize.duration")?.record(
                        started.elapsedNow().inWholeMilliseconds,
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                }
                fun markOk(reason: String) {
                    metricsProvider?.counter("payments.finalize.ok", "reason", reason)?.increment()
                }
                fun markFail(reason: String) {
                    metricsProvider?.counter("payments.finalize.fail", "reason", reason)?.increment()
                }

                val user = call.attributes[MiniAppUserKey]
                val clubId = call.parameters["clubId"]?.toLongOrNull()
                if (clubId == null) {
                    markFail("invalid_club")
                    recordDuration()
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid club id"))
                    return@post
                }

                val idempotencyKey = call.request.headers["Idempotency-Key"]?.trim()
                if (idempotencyKey.isNullOrEmpty()) {
                    markFail("missing_idem")
                    recordDuration()
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Idempotency-Key required"))
                    return@post
                }

                val request = runCatching { call.receive<FinalizeRequest>() }.getOrElse { throwable ->
                    logger.warn(
                        "payments.finalize.invalid_payload callId={} clubId={} bookingId={}",
                        callId,
                        clubId,
                        call.request.queryParameters["bookingId"],
                        throwable,
                    )
                    markFail("invalid_payload")
                    recordDuration()
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid payload"))
                    return@post
                }

                val bookingId = runCatching { UUID.fromString(request.bookingId) }.getOrNull()
                if (bookingId == null) {
                    markFail("invalid_booking")
                    recordDuration()
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid bookingId"))
                    return@post
                }

                logger.info(
                    "payments.finalize.start callId={} clubId={} bookingId={} userId={}",
                    callId,
                    clubId,
                    bookingId,
                    user.id,
                )

                val promoService = koin.getOrNull<PromoAttributionService>()
                try {
                    val result = paymentsService.finalize(
                        clubId = clubId,
                        bookingId = bookingId,
                        paymentToken = request.paymentToken,
                        idemKey = idempotencyKey,
                        actorUserId = user.id,
                    )

                    val promoAttached = if (!request.promoDeepLink.isNullOrBlank() && promoService != null) {
                        runCatching { promoService.attachDeepLink(bookingId, request.promoDeepLink) }
                            .onFailure { throwable ->
                                logger.warn(
                                    "payments.finalize.promo_attach_failed callId={} bookingId={}",
                                    callId,
                                    bookingId,
                                    throwable,
                                )
                            }.getOrDefault(false)
                    } else {
                        false
                    }

                    markOk("ok")
                    recordDuration()
                    logger.info(
                        "payments.finalize.success callId={} clubId={} bookingId={} status={}",
                        callId,
                        clubId,
                        bookingId,
                        result.paymentStatus,
                    )
                    call.respond(
                        HttpStatusCode.OK,
                        FinalizeResponse(
                            status = "OK",
                            bookingId = bookingId.toString(),
                            paymentStatus = result.paymentStatus,
                            promoAttached = promoAttached,
                        ),
                    )
                } catch (conflict: PaymentsFinalizeService.ConflictException) {
                    markFail("conflict")
                    recordDuration()
                    logger.warn(
                        "payments.finalize.conflict callId={} clubId={} bookingId={}",
                        callId,
                        clubId,
                        bookingId,
                        conflict,
                    )
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to conflict.message))
                } catch (validation: PaymentsFinalizeService.ValidationException) {
                    markFail("validation")
                    recordDuration()
                    logger.warn(
                        "payments.finalize.validation callId={} clubId={} bookingId={}",
                        callId,
                        clubId,
                        bookingId,
                        validation,
                    )
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to validation.message))
                } catch (unexpected: Throwable) {
                    markFail("unexpected")
                    recordDuration()
                    logger.error(
                        "payments.finalize.error callId={} clubId={} bookingId={}",
                        callId,
                        clubId,
                        bookingId,
                        unexpected,
                    )
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal"))
                }
            }
        }
    }
}
