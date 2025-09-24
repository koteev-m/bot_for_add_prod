package com.example.bot.routes

import com.example.bot.booking.BookingCmdResult
import com.example.bot.booking.BookingService
import com.example.bot.data.security.Role
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.plugins.configureSecurity
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

private data class DatabaseSetup(val dataSource: JdbcDataSource, val database: Database)

private object UsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id").nullable()
    val username = text("username").nullable()
    val displayName = text("display_name").nullable()
    val phone = text("phone_e164").nullable()

    override val primaryKey = PrimaryKey(id)
}

private object UserRolesTable : Table("user_roles") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val roleCode = text("role_code")
    val scopeType = text("scope_type")
    val scopeClubId = long("scope_club_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

class SecuredBookingRoutesTest : StringSpec({
    lateinit var setup: DatabaseSetup
    val json = Json { ignoreUnknownKeys = true }

    beforeTest {
        setup = prepareDatabase()
    }

    afterTest {
        DataSourceHolder.dataSource = null
    }

    fun Application.testModule(service: BookingService) {
        DataSourceHolder.dataSource = setup.dataSource
        install(ContentNegotiation) { json() }
        configureSecurity()
        routing { securedBookingRoutes(service) }
    }

    suspend fun registerUser(
        telegramId: Long,
        roles: Set<Role>,
        clubs: Set<Long>,
    ) {
        transaction(setup.database) {
            val userId =
                UsersTable.insert {
                    it[telegramUserId] = telegramId
                    it[username] = "user$telegramId"
                    it[displayName] = "user$telegramId"
                    it[phone] = null
                } get UsersTable.id
            roles.forEach { role ->
                if (clubs.isEmpty()) {
                    UserRolesTable.insert {
                        it[UserRolesTable.userId] = userId
                        it[roleCode] = role.name
                        it[scopeType] = "GLOBAL"
                        it[scopeClubId] = null
                    }
                } else {
                    clubs.forEach { clubId ->
                        UserRolesTable.insert {
                            it[UserRolesTable.userId] = userId
                            it[roleCode] = role.name
                            it[scopeType] = "CLUB"
                            it[scopeClubId] = clubId
                        }
                    }
                }
            }
        }
    }

    "returns 401 when principal missing" {
        val bookingService = mockk<BookingService>()
        testApplication {
            application { testModule(bookingService) }
            val response =
                client.post("/api/clubs/1/bookings/hold") {
                    contentType(ContentType.Application.Json)
                    header("Idempotency-Key", "idem-unauth")
                    setBody(
                        """
                        {
                          "tableId": 10,
                          "slotStart": "2025-04-01T10:00:00Z",
                          "slotEnd": "2025-04-01T12:00:00Z",
                          "guestsCount": 2,
                          "ttlSeconds": 900
                        }
                        """
                            .trimIndent(),
                    )
                }
            response.status shouldBe HttpStatusCode.Unauthorized
        }
        coVerify(exactly = 0) { bookingService.hold(any(), any()) }
    }

    "returns 403 when club scope violated" {
        val bookingService = mockk<BookingService>()
        registerUser(telegramId = 200L, roles = setOf(Role.MANAGER), clubs = setOf(2L))
        testApplication {
            application { testModule(bookingService) }
            val response =
                client.post("/api/clubs/1/bookings/hold") {
                    header("X-Telegram-Id", "200")
                    header("Idempotency-Key", "idem-scope")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "tableId": 10,
                          "slotStart": "2025-04-01T10:00:00Z",
                          "slotEnd": "2025-04-01T12:00:00Z",
                          "guestsCount": 2,
                          "ttlSeconds": 900
                        }
                        """
                            .trimIndent(),
                    )
                }
            response.status shouldBe HttpStatusCode.Forbidden
        }
        coVerify(exactly = 0) { bookingService.hold(any(), any()) }
    }

    "returns 400 when Idempotency-Key missing" {
        val bookingService = mockk<BookingService>()
        registerUser(telegramId = 300L, roles = setOf(Role.MANAGER), clubs = setOf(1L))
        testApplication {
            application { testModule(bookingService) }
            val response =
                client.post("/api/clubs/1/bookings/hold") {
                    header("X-Telegram-Id", "300")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "tableId": 10,
                          "slotStart": "2025-04-01T10:00:00Z",
                          "slotEnd": "2025-04-01T12:00:00Z",
                          "guestsCount": 2,
                          "ttlSeconds": 900
                        }
                        """
                            .trimIndent(),
                    )
                }
            response.status shouldBe HttpStatusCode.BadRequest
        }
        coVerify(exactly = 0) { bookingService.hold(any(), any()) }
    }

    "happy path returns 200 for hold and confirm" {
        val bookingService = mockk<BookingService>()
        registerUser(telegramId = 400L, roles = setOf(Role.MANAGER), clubs = setOf(1L))
        val holdId = UUID.randomUUID()
        val bookingId = UUID.randomUUID()
        coEvery { bookingService.hold(any(), "idem-hold") } returns BookingCmdResult.HoldCreated(holdId)
        coEvery { bookingService.confirm(holdId, "idem-confirm") } returns BookingCmdResult.Booked(bookingId)

        testApplication {
            application { testModule(bookingService) }
            val holdResponse =
                client.post("/api/clubs/1/bookings/hold") {
                    header("X-Telegram-Id", "400")
                    header("Idempotency-Key", "idem-hold")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "clubId": 1,
                          "tableId": 25,
                          "slotStart": "2025-04-01T10:00:00Z",
                          "slotEnd": "2025-04-01T12:00:00Z",
                          "guestsCount": 3,
                          "ttlSeconds": 600
                        }
                        """
                            .trimIndent(),
                    )
                }
            holdResponse.status shouldBe HttpStatusCode.OK
            val holdJson = json.parseToJsonElement(holdResponse.bodyAsText()).jsonObject
            holdJson["status"]!!.jsonPrimitive.content shouldBe "hold_created"
            holdJson["holdId"]!!.jsonPrimitive.content shouldBe holdId.toString()

            val confirmResponse =
                client.post("/api/clubs/1/bookings/confirm") {
                    header("X-Telegram-Id", "400")
                    header("Idempotency-Key", "idem-confirm")
                    contentType(ContentType.Application.Json)
                    setBody("""{"clubId":1,"holdId":"$holdId"}""")
                }
            confirmResponse.status shouldBe HttpStatusCode.OK
            val confirmJson = json.parseToJsonElement(confirmResponse.bodyAsText()).jsonObject
            confirmJson["status"]!!.jsonPrimitive.content shouldBe "booked"
            confirmJson["bookingId"]!!.jsonPrimitive.content shouldBe bookingId.toString()
        }

        coVerify(exactly = 1) {
            bookingService.hold(
                match {
                    it.clubId == 1L &&
                        it.tableId == 25L &&
                        it.guestsCount == 3 &&
                        it.slotStart == Instant.parse("2025-04-01T10:00:00Z") &&
                        it.slotEnd == Instant.parse("2025-04-01T12:00:00Z")
                },
                "idem-hold",
            )
        }
        coVerify(exactly = 1) { bookingService.confirm(holdId, "idem-confirm") }
    }

    "duplicate active booking returns 409" {
        val bookingService = mockk<BookingService>()
        registerUser(telegramId = 500L, roles = setOf(Role.MANAGER), clubs = setOf(1L))
        coEvery { bookingService.hold(any(), "idem-dup") } returns BookingCmdResult.DuplicateActiveBooking

        testApplication {
            application { testModule(bookingService) }
            val response =
                client.post("/api/clubs/1/bookings/hold") {
                    header("X-Telegram-Id", "500")
                    header("Idempotency-Key", "idem-dup")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "tableId": 99,
                          "slotStart": "2025-04-01T10:00:00Z",
                          "slotEnd": "2025-04-01T12:00:00Z",
                          "guestsCount": 2,
                          "ttlSeconds": 900
                        }
                        """
                            .trimIndent(),
                    )
                }
            response.status shouldBe HttpStatusCode.Conflict
        }
    }

    "hold expired returns 410" {
        val bookingService = mockk<BookingService>()
        registerUser(telegramId = 600L, roles = setOf(Role.MANAGER), clubs = setOf(1L))
        val holdId = UUID.randomUUID()
        coEvery { bookingService.confirm(holdId, "idem-expire") } returns BookingCmdResult.HoldExpired

        testApplication {
            application { testModule(bookingService) }
            val response =
                client.post("/api/clubs/1/bookings/confirm") {
                    header("X-Telegram-Id", "600")
                    header("Idempotency-Key", "idem-expire")
                    contentType(ContentType.Application.Json)
                    setBody("""{"holdId":"$holdId"}""")
                }
            response.status shouldBe HttpStatusCode.Gone
        }
    }

    "confirm not found returns 404" {
        val bookingService = mockk<BookingService>()
        registerUser(telegramId = 700L, roles = setOf(Role.MANAGER), clubs = setOf(1L))
        val holdId = UUID.randomUUID()
        coEvery { bookingService.confirm(holdId, "idem-missing") } returns BookingCmdResult.NotFound

        testApplication {
            application { testModule(bookingService) }
            val response =
                client.post("/api/clubs/1/bookings/confirm") {
                    header("X-Telegram-Id", "700")
                    header("Idempotency-Key", "idem-missing")
                    contentType(ContentType.Application.Json)
                    setBody("""{"holdId":"$holdId"}""")
                }
            response.status shouldBe HttpStatusCode.NotFound
        }
    }
})

private fun prepareDatabase(): DatabaseSetup {
    val dbName = "secured_booking_${UUID.randomUUID()}"
    val dataSource =
        JdbcDataSource().apply {
            setURL("jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
    Flyway
        .configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/common", "classpath:db/migration/h2")
        .target("9")
        .load()
        .migrate()
    val database = Database.connect(dataSource)
    transaction(database) {
        listOf("action", "result").forEach { column ->
            exec("""ALTER TABLE audit_log ALTER COLUMN $column RENAME TO "$column"""")
        }
        exec("ALTER TABLE audit_log ALTER COLUMN resource_id DROP NOT NULL")
    }
    return DatabaseSetup(dataSource, database)
}
