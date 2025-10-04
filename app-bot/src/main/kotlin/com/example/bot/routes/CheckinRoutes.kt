package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.guestlists.GuestListRepository
import com.example.bot.guestlists.QrGuestListCodec
import com.example.bot.metrics.UiCheckinMetrics
import com.example.bot.security.rbac.ClubScope
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.clubScoped
import com.example.bot.webapp.InitDataAuthConfig
import com.example.bot.webapp.InitDataAuthPlugin
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val DEFAULT_QR_TTL: Duration = Duration.ofHours(12)

@Serializable
private data class ScanPayload(val qr: String)

fun Application.checkinRoutes(
    repository: GuestListRepository,
    initDataAuth: InitDataAuthConfig.() -> Unit,
    qrSecretProvider: () -> String = {
        System.getenv("QR_SECRET") ?: error("QR_SECRET missing")
    },
    clock: Clock = Clock.systemUTC(),
    qrTtl: Duration = DEFAULT_QR_TTL,
) {
    val logger = LoggerFactory.getLogger("CheckinRoutes")

    routing {
        route("/api/clubs/{clubId}/checkin") {
            install(InitDataAuthPlugin, initDataAuth)
            authorize(Role.CLUB_ADMIN, Role.MANAGER, Role.ENTRY_MANAGER) {
                clubScoped(ClubScope.Own) {
                    post("/scan") {
                        UiCheckinMetrics.incTotal()
                        UiCheckinMetrics.timeScan {
                            val clubId =
                                call.parameters["clubId"]?.toLongOrNull()
                                    ?: run {
                                        UiCheckinMetrics.incError()
                                        call.respond(HttpStatusCode.BadRequest, "Invalid clubId")
                                        return@timeScan
                                    }

                            val payload = call.receiveScanPayloadOrNull()
                            if (payload == null) {
                                UiCheckinMetrics.incError()
                                logger.warn("checkin.error reason={} clubId={}", "malformed_json", clubId)
                                call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                                return@timeScan
                            }

                            val qr = payload.qr.trim()
                            if (qr.isEmpty()) {
                                UiCheckinMetrics.incError()
                                logger.warn("checkin.error reason={} clubId={}", "missing_qr", clubId)
                                call.respond(HttpStatusCode.BadRequest, "Empty QR")
                                return@timeScan
                            }

                            val secret = qrSecretProvider()
                            val verificationInstant = Instant.now(clock)
                            val decoded =
                                runCatching {
                                    QrGuestListCodec.verify(qr, verificationInstant, qrTtl, secret)
                                }.getOrNull()
                            if (decoded == null) {
                                UiCheckinMetrics.incError()
                                logger.warn(
                                    "checkin.error reason={} clubId={}",
                                    "invalid_or_expired_qr",
                                    clubId,
                                )
                                call.respond(HttpStatusCode.BadRequest, "Invalid or expired QR")
                                return@timeScan
                            }

                            val list =
                                withContext(Dispatchers.IO) { repository.getList(decoded.listId) }
                                    ?: run {
                                        UiCheckinMetrics.incError()
                                        logger.warn(
                                            "checkin.error reason={} clubId={}",
                                            "list_not_found",
                                            clubId,
                                        )
                                        call.respond(HttpStatusCode.NotFound, "List not found")
                                        return@timeScan
                                    }
                            if (list.clubId != clubId) {
                                UiCheckinMetrics.incError()
                                logger.warn(
                                    "checkin.error reason={} clubId={}",
                                    "scope_mismatch",
                                    clubId,
                                )
                                call.respond(HttpStatusCode.Forbidden, "club scope mismatch")
                                return@timeScan
                            }

                            val entry =
                                withContext(Dispatchers.IO) { repository.findEntry(decoded.entryId) }
                                    ?: run {
                                        UiCheckinMetrics.incError()
                                        logger.warn(
                                            "checkin.error reason={} clubId={}",
                                            "entry_not_found",
                                            clubId,
                                        )
                                        call.respond(HttpStatusCode.NotFound, "Entry not found")
                                        return@timeScan
                                    }
                            if (entry.listId != list.id) {
                                UiCheckinMetrics.incError()
                                logger.warn(
                                    "checkin.error reason={} clubId={} listId={} entryId={}",
                                    "entry_not_found",
                                    clubId,
                                    list.id,
                                    entry.id,
                                )
                                call.respond(HttpStatusCode.BadRequest, "Entry-list mismatch")
                                return@timeScan
                            }

                            val marked =
                                withContext(Dispatchers.IO) {
                                    repository.markArrived(entry.id, Instant.now(clock))
                                }
                            if (!marked) {
                                UiCheckinMetrics.incError()
                                logger.warn(
                                    "checkin.error reason={} clubId={} listId={} entryId={}",
                                    "entry_not_found",
                                    clubId,
                                    list.id,
                                    entry.id,
                                )
                                call.respond(HttpStatusCode.NotFound, "Entry not found")
                                return@timeScan
                            }

                            logger.info(
                                "checkin.arrived clubId={} listId={} entryId={}",
                                clubId,
                                list.id,
                                entry.id,
                            )
                            call.respond(HttpStatusCode.OK, mapOf("status" to "ARRIVED"))
                        }
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.receiveScanPayloadOrNull(): ScanPayload? {
    return runCatching { receive<ScanPayload>() }.getOrNull()
}
