package com.example.bot.routes

import com.example.bot.booking.BookingCmdResult
import com.example.bot.booking.BookingService
import com.example.bot.booking.dto.ConfirmRequest
import com.example.bot.booking.dto.HoldRequest
import com.example.bot.data.security.Role
import com.example.bot.security.rbac.ClubScope
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.clubScoped
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.securedBookingRoutes(bookingService: BookingService) {
    route("/api/clubs/{clubId}/bookings") {
        authorize(Role.PROMOTER, Role.CLUB_ADMIN, Role.MANAGER, Role.GUEST) {
            clubScoped(ClubScope.Own) {
                post("/hold") {
                    val clubIdPath =
                        call.parameters["clubId"]?.toLongOrNull()
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_club"))
                    val idempotencyKey =
                        call.request.headers["Idempotency-Key"]
                            ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "missing_idempotency_key"),
                            )
                    val payload =
                        runCatching { call.receive<HoldRequest>() }
                            .getOrElse { throwable ->
                                call.application.environment.log.warn(
                                    "Failed to decode hold request",
                                    throwable,
                                )
                                return@post call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to "invalid_payload"),
                                )
                            }
                    if (payload.clubId != null && payload.clubId != clubIdPath) {
                        return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "club_mismatch"))
                    }
                    val command =
                        runCatching { payload.toCommand(clubIdPath) }
                            .getOrElse { throwable ->
                                call.application.environment.log.warn(
                                    "Failed to map hold request",
                                    throwable,
                                )
                                return@post call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to "invalid_payload"),
                                )
                            }
                    val result = withContext(Dispatchers.IO) { bookingService.hold(command, idempotencyKey) }
                    respondBookingResult(call, result)
                }

                post("/confirm") {
                    val clubIdPath =
                        call.parameters["clubId"]?.toLongOrNull()
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_club"))
                    val idempotencyKey =
                        call.request.headers["Idempotency-Key"]
                            ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "missing_idempotency_key"),
                            )
                    val payload =
                        runCatching { call.receive<ConfirmRequest>() }
                            .getOrElse { throwable ->
                                call.application.environment.log.warn(
                                    "Failed to decode confirm request",
                                    throwable,
                                )
                                return@post call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to "invalid_payload"),
                                )
                            }
                    if (payload.clubId != null && payload.clubId != clubIdPath) {
                        return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "club_mismatch"))
                    }
                    val holdId =
                        runCatching { payload.holdUuid() }
                            .getOrElse { throwable ->
                                call.application.environment.log.warn(
                                    "Failed to parse hold id",
                                    throwable,
                                )
                                return@post call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to "invalid_payload"),
                                )
                            }
                    val result = withContext(Dispatchers.IO) { bookingService.confirm(holdId, idempotencyKey) }
                    respondBookingResult(call, result)
                }
            }
        }
    }
}

private suspend fun respondBookingResult(
    call: ApplicationCall,
    result: BookingCmdResult,
) {
    when (result) {
        is BookingCmdResult.HoldCreated ->
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "status" to "hold_created",
                    "holdId" to result.holdId.toString(),
                ),
            )

        is BookingCmdResult.Booked ->
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "status" to "booked",
                    "bookingId" to result.bookingId.toString(),
                ),
            )

        is BookingCmdResult.AlreadyBooked ->
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "status" to "already_booked",
                    "bookingId" to result.bookingId.toString(),
                ),
            )

        BookingCmdResult.DuplicateActiveBooking ->
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "duplicate_active_booking"))

        BookingCmdResult.HoldExpired ->
            call.respond(HttpStatusCode.Gone, mapOf("error" to "hold_expired"))

        BookingCmdResult.IdempotencyConflict ->
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "idempotency_conflict"))

        BookingCmdResult.NotFound ->
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found"))
    }
}
