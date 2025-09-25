package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.guestlists.GuestListRepository
import com.example.bot.guestlists.QrGuestListCodec
import com.example.bot.security.rbac.ClubScope
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.clubScoped
import com.example.bot.webapp.InitDataAuthPlugin
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
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
private val INIT_DATA_MAX_AGE: Duration = Duration.ofHours(24)

@Serializable
private data class ScanPayload(val qr: String)

fun Application.checkinRoutes(
    repository: GuestListRepository,
    initDataBotTokenProvider: () -> String = {
        System.getenv("TELEGRAM_BOT_TOKEN") ?: error("TELEGRAM_BOT_TOKEN missing")
    },
    qrSecretProvider: () -> String = {
        System.getenv("QR_SECRET") ?: error("QR_SECRET missing")
    },
    clock: Clock = Clock.systemUTC(),
    qrTtl: Duration = DEFAULT_QR_TTL,
) {
    val logger = LoggerFactory.getLogger("CheckinRoutes")

    routing {
        route("/api/clubs/{clubId}/checkin") {
            install(InitDataAuthPlugin) {
                botTokenProvider = initDataBotTokenProvider
                maxAge = INIT_DATA_MAX_AGE
                this.clock = clock
            }
            authorize(Role.CLUB_ADMIN, Role.MANAGER, Role.ENTRY_MANAGER) {
                clubScoped(ClubScope.Own) {
                    post("/scan") {
                        val clubId =
                            call.parameters["clubId"]?.toLongOrNull()
                                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid clubId")

                        val payload = call.receiveScanPayloadOrNull()
                        if (payload == null) {
                            return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                        }

                        val qr = payload.qr.trim()
                        if (qr.isEmpty()) {
                            return@post call.respond(HttpStatusCode.BadRequest, "Empty QR")
                        }

                        val secret = qrSecretProvider()
                        val verificationInstant = Instant.now(clock)
                        val decoded =
                            runCatching {
                                QrGuestListCodec.verify(qr, verificationInstant, qrTtl, secret)
                            }.getOrNull()
                        if (decoded == null) {
                            logger.warn("checkin: invalid or expired QR")
                            return@post call.respond(HttpStatusCode.BadRequest, "Invalid or expired QR")
                        }

                        val list =
                            withContext(Dispatchers.IO) { repository.getList(decoded.listId) }
                                ?: return@post call.respond(HttpStatusCode.NotFound, "List not found")
                        if (list.clubId != clubId) {
                            logger.warn(
                                "checkin: club scope mismatch list.clubId={} path.clubId={}",
                                list.clubId,
                                clubId,
                            )
                            return@post call.respond(HttpStatusCode.Forbidden, "club scope mismatch")
                        }

                        val entry =
                            withContext(Dispatchers.IO) { repository.findEntry(decoded.entryId) }
                                ?: return@post call.respond(HttpStatusCode.NotFound, "Entry not found")
                        if (entry.listId != list.id) {
                            logger.warn("checkin: entry {} not in list {}", entry.id, list.id)
                            return@post call.respond(HttpStatusCode.BadRequest, "Entry-list mismatch")
                        }

                        val marked =
                            withContext(Dispatchers.IO) {
                                repository.markArrived(entry.id, Instant.now(clock))
                            }
                        if (!marked) {
                            return@post call.respond(HttpStatusCode.NotFound, "Entry not found")
                        }

                        logger.info(
                            "checkin: ARRIVED clubId={} listId={} entryId={}",
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

private suspend fun ApplicationCall.receiveScanPayloadOrNull(): ScanPayload? {
    return runCatching { receive<ScanPayload>() }.getOrNull()
}
