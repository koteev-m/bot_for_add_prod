package com.example.bot.routes

import com.example.bot.di.PaymentsService
import com.example.bot.observability.MetricsProvider
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.envBool
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.ClubScope
import com.example.bot.data.security.Role
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.clubScoped
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.koin.core.Koin
import org.koin.ktor.ext.getKoin
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger("PaymentsCancelRefundRoutes")

private typealias RbacRouteWrapper = io.ktor.server.routing.Route.() -> Unit

@Serializable
private data class CancelRequest(val reason: String? = null)

@Serializable
data class CancelResponse(val status: String, val bookingId: String)

@Serializable
private data class RefundRequest(val amountMinor: Long? = null)

@Serializable
data class RefundResponse(val status: String, val bookingId: String, val refundAmountMinor: Long)

fun Application.paymentsCancelRefundRoutes(miniAppBotTokenProvider: () -> String) {
    val cancelEnabled = envBool("CANCEL_ENABLED", default = true)
    val refundEnabled = envBool("REFUND_ENABLED", default = true)

    if (!cancelEnabled && !refundEnabled) {
        logger.info { "[payments] cancel/refund routes disabled" }
        return
    }

    val rbacEnabled = envBool("RBAC_ENABLED", default = false)
    val rbacAvailable = rbacEnabled && pluginOrNull(RbacPlugin) != null
    val koin = getKoin()

    routing {
        route("/api/clubs/{clubId}/bookings") {
            withMiniAppAuth(miniAppBotTokenProvider)

            val registerHandlers: RbacRouteWrapper =
                if (rbacAvailable) {
                    {
                        authorize(
                            Role.PROMOTER,
                            Role.CLUB_ADMIN,
                            Role.MANAGER,
                            Role.GUEST,
                        ) {
                            clubScoped(ClubScope.Own) {
                                registerCancelRefundHandlers(cancelEnabled, refundEnabled, koin)
                            }
                        }
                    }
                } else {
                    { registerCancelRefundHandlers(cancelEnabled, refundEnabled, koin) }
                }

            registerHandlers.invoke(this)
        }
    }
}

private fun io.ktor.server.routing.Route.registerCancelRefundHandlers(
    cancelEnabled: Boolean,
    refundEnabled: Boolean,
    koin: Koin,
) {
    val metricsProvider = koin.getOrNull<MetricsProvider>()
    val paymentsService = koin.get<PaymentsService>()

    if (cancelEnabled) {
        post("/{bookingId}/cancel") {
            val callId = call.callId ?: "unknown"
            val started = TimeSource.Monotonic.markNow()
            val timer = metricsProvider?.timer("booking.cancel.duration")

            fun recordDuration() {
                timer?.record(started.elapsedNow().inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }

            fun markOk() {
                metricsProvider?.counter("booking.cancel.ok", "reason", "ok")?.increment()
            }

            fun markFail(reason: String) {
                metricsProvider?.counter("booking.cancel.fail", "reason", reason)?.increment()
            }

            val user = call.attributes[MiniAppUserKey]
            val clubId = call.parameters["clubId"]?.toLongOrNull()
            if (clubId == null) {
                markFail("validation")
                recordDuration()
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_club"))
                return@post
            }

            val bookingId = call.parameters["bookingId"]?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            if (bookingId == null) {
                markFail("validation")
                recordDuration()
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_booking"))
                return@post
            }

            val idempotencyKey = call.request.headers["Idempotency-Key"]?.trim()
            if (idempotencyKey.isNullOrEmpty()) {
                markFail("validation")
                recordDuration()
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Idempotency-Key required"))
                return@post
            }

            val payload =
                runCatching { call.receive<CancelRequest>() }
                    .getOrElse { throwable ->
                        logger.warn(throwable) {
                            "[payments] cancel invalid payload callId=$callId clubId=$clubId bookingId=$bookingId"
                        }
                        markFail("validation")
                        recordDuration()
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_payload"))
                        return@post
                    }

            runCatching {
                paymentsService.cancel(
                    clubId = clubId,
                    bookingId = bookingId,
                    reason = payload.reason,
                    idemKey = idempotencyKey,
                    actorUserId = user.id,
                )
            }.onSuccess {
                markOk()
                recordDuration()
                logger.info {
                    "[payments] cancel success callId=$callId clubId=$clubId bookingId=$bookingId"
                }
                call.respond(CancelResponse(status = "CANCELLED", bookingId = bookingId.toString()))
            }.onFailure { throwable ->
                when (throwable) {
                    is PaymentsService.ValidationException -> {
                        markFail("validation")
                        recordDuration()
                        logger.warn(throwable) {
                            "[payments] cancel validation callId=$callId clubId=$clubId bookingId=$bookingId"
                        }
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to throwable.message))
                    }
                    is PaymentsService.ConflictException -> {
                        markFail("conflict")
                        recordDuration()
                        logger.info(throwable) {
                            "[payments] cancel conflict callId=$callId clubId=$clubId bookingId=$bookingId"
                        }
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to throwable.message))
                    }
                    else -> {
                        markFail("unexpected")
                        recordDuration()
                        logger.error(throwable) {
                            "[payments] cancel unexpected callId=$callId clubId=$clubId bookingId=$bookingId"
                        }
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal"))
                    }
                }
            }
        }
    }

    if (refundEnabled) {
        post("/{bookingId}/refund") {
            val callId = call.callId ?: "unknown"
            val started = TimeSource.Monotonic.markNow()
            val timer = metricsProvider?.timer("payments.refund.duration")

            fun recordDuration() {
                timer?.record(started.elapsedNow().inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }

            fun markOk() {
                metricsProvider?.counter("payments.refund.ok", "reason", "ok")?.increment()
            }

            fun markFail(reason: String) {
                metricsProvider?.counter("payments.refund.fail", "reason", reason)?.increment()
            }

            val user = call.attributes[MiniAppUserKey]
            val clubId = call.parameters["clubId"]?.toLongOrNull()
            if (clubId == null) {
                markFail("validation")
                recordDuration()
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_club"))
                return@post
            }

            val bookingId = call.parameters["bookingId"]?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            if (bookingId == null) {
                markFail("validation")
                recordDuration()
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_booking"))
                return@post
            }

            val idempotencyKey = call.request.headers["Idempotency-Key"]?.trim()
            if (idempotencyKey.isNullOrEmpty()) {
                markFail("validation")
                recordDuration()
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Idempotency-Key required"))
                return@post
            }

            val payload =
                runCatching { call.receive<RefundRequest>() }
                    .getOrElse { throwable ->
                        logger.warn(throwable) {
                            "[payments] refund invalid payload callId=$callId clubId=$clubId bookingId=$bookingId"
                        }
                        markFail("validation")
                        recordDuration()
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_payload"))
                        return@post
                    }

            runCatching {
                paymentsService.refund(
                    clubId = clubId,
                    bookingId = bookingId,
                    amountMinor = payload.amountMinor,
                    idemKey = idempotencyKey,
                    actorUserId = user.id,
                )
            }.onSuccess { result ->
                markOk()
                recordDuration()
                logger.info {
                    "[payments] refund success callId=$callId clubId=$clubId bookingId=$bookingId"
                }
                call.respond(
                    RefundResponse(
                        status = "REFUNDED",
                        bookingId = bookingId.toString(),
                        refundAmountMinor = result.refundAmountMinor,
                    ),
                )
            }.onFailure { throwable ->
                when (throwable) {
                    is PaymentsService.ValidationException -> {
                        markFail("validation")
                        recordDuration()
                        logger.warn(throwable) {
                            "[payments] refund validation callId=$callId clubId=$clubId bookingId=$bookingId"
                        }
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to throwable.message))
                    }
                    is PaymentsService.ConflictException -> {
                        markFail("conflict")
                        recordDuration()
                        logger.info(throwable) {
                            "[payments] refund conflict callId=$callId clubId=$clubId bookingId=$bookingId"
                        }
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to throwable.message))
                    }
                    is PaymentsService.UnprocessableException -> {
                        markFail("unprocessable")
                        recordDuration()
                        logger.info(throwable) {
                            "[payments] refund unprocessable callId=$callId clubId=$clubId bookingId=$bookingId"
                        }
                        call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to throwable.message))
                    }
                    else -> {
                        markFail("unexpected")
                        recordDuration()
                        logger.error(throwable) {
                            "[payments] refund unexpected callId=$callId clubId=$clubId bookingId=$bookingId"
                        }
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal"))
                    }
                }
            }
        }
    }
}
