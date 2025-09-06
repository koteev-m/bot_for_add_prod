package com.example.bot.app

import com.example.bot.availability.AvailabilityRepository
import com.example.bot.availability.AvailabilityService
import com.example.bot.policy.CutoffPolicy
import com.example.bot.routes.availabilityRoutes
import com.example.bot.telemetry.Telemetry.configureMonitoring
import com.example.bot.time.OperatingRulesResolver
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate


@Serializable
data class TelegramMessage(val text: String? = null)

@Serializable
data class TelegramUpdate(val updateId: Long, val message: TelegramMessage?)

fun Application.module() {
    ConfigLoader.load()

    install(ContentNegotiation) {
        json()
    }

    val corsHosts = environment.config.propertyOrNull("ktor.cors.hosts")?.getList() ?: emptyList()
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.ContentType)
        if (corsHosts.contains("*")) {
            anyHost()
        } else {
            corsHosts.forEach { host -> allowHost(host, schemes = listOf("http", "https")) }
        }
    }

    val routes = environment.config.config("ktor.routing")
    val webhookPath = routes.property("webhook").getString()
    val metricsPath = routes.property("metrics").getString()
    val healthPath = routes.property("health").getString()
    val apiBase = routes.property("api").getString()

    configureMonitoring(metricsPath, healthPath)

    val repository = DummyAvailabilityRepository
    val resolver = OperatingRulesResolver(repository)
    val cutoffPolicy = CutoffPolicy()
    val availabilityService = AvailabilityService(repository, resolver, cutoffPolicy)

    routing {
        route(apiBase) { availabilityRoutes(availabilityService) }
        post(webhookPath) {
            val update = call.receive<TelegramUpdate>()
            val reply = update.message?.text ?: ""
            call.respondText(reply)
        }
    }
}

fun main(args: Array<String>) = EngineMain.main(args)

/**
 * Minimal in-memory repository used for running the application without a real database.
 */
private object DummyAvailabilityRepository : AvailabilityRepository {
    override suspend fun findClub(clubId: Long) = null
    override suspend fun listClubHours(clubId: Long) = emptyList<com.example.bot.time.ClubHour>()
    override suspend fun listHolidays(
        clubId: Long,
        from: LocalDate,
        to: LocalDate,
    ) = emptyList<com.example.bot.time.ClubHoliday>()

    override suspend fun listExceptions(
        clubId: Long,
        from: LocalDate,
        to: LocalDate,
    ) = emptyList<com.example.bot.time.ClubException>()

    override suspend fun listEvents(
        clubId: Long,
        from: Instant,
        to: Instant,
    ) = emptyList<com.example.bot.time.Event>()

    override suspend fun findEvent(clubId: Long, startUtc: Instant) = null

    override suspend fun listTables(clubId: Long) = emptyList<com.example.bot.availability.Table>()

    override suspend fun listActiveHoldTableIds(eventId: Long, now: Instant) = emptySet<Long>()

    override suspend fun listActiveBookingTableIds(eventId: Long) = emptySet<Long>()
}

