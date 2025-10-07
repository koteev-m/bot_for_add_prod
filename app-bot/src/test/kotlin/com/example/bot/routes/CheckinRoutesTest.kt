package com.example.bot.routes

import com.example.bot.club.GuestList
import com.example.bot.club.GuestListEntry
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListStatus
import com.example.bot.data.security.Role
import com.example.bot.guestlists.GuestListRepository
import com.example.bot.guestlists.QrGuestListCodec
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.plugins.configureSecurity
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import com.example.bot.testing.applicationDev
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private data class CheckinDatabaseSetup(val dataSource: JdbcDataSource, val database: Database)

private object CheckinUsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id").nullable()
    val username = text("username").nullable()
    val displayName = text("display_name").nullable()
    val phone = text("phone_e164").nullable()

    override val primaryKey = PrimaryKey(id)
}

private object CheckinUserRolesTable : Table("user_roles") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val roleCode = text("role_code")
    val scopeType = text("scope_type")
    val scopeClubId = long("scope_club_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

class CheckinRoutesTest : StringSpec({
    lateinit var setup: CheckinDatabaseSetup
    val clock: Clock = Clock.fixed(Instant.parse("2024-05-01T12:00:00Z"), ZoneOffset.UTC)
    val qrSecret = "test-secret"
    val qrTtl = Duration.ofHours(12)
    val telegramId = 915L
    val clubId = 1L

    beforeTest {
        setup = prepareDatabase()
        DataSourceHolder.dataSource = setup.dataSource
        registerUser(setup.database, telegramId, setOf(Role.MANAGER), setOf(clubId))
    }

    afterTest {
        DataSourceHolder.dataSource = null
    }

    fun Application.testModule(repository: GuestListRepository) {
        install(ContentNegotiation) { json() }
        configureSecurity()
        checkinRoutes(
            repository = repository,
            initDataAuth = { botTokenProvider = { TEST_BOT_TOKEN } },
            qrSecretProvider = { qrSecret },
            clock = clock,
            qrTtl = qrTtl,
        )
    }

    fun validInitData(): String {
        val authDate = clock.instant().minusSeconds(30).epochSecond.toString()
        return WebAppInitDataTestHelper.createInitData(
            TEST_BOT_TOKEN,
            linkedMapOf(
                "user" to WebAppInitDataTestHelper.encodeUser(id = telegramId),
                "auth_date" to authDate,
            ),
        )
    }

    fun buildList(
        id: Long,
        club: Long = clubId,
    ): GuestList {
        return GuestList(
            id = id,
            clubId = club,
            eventId = 10L,
            ownerType = GuestListOwnerType.MANAGER,
            ownerUserId = 5L,
            title = "VIP",
            capacity = 100,
            arrivalWindowStart = null,
            arrivalWindowEnd = null,
            status = GuestListStatus.ACTIVE,
            createdAt = clock.instant(),
        )
    }

    fun buildEntry(
        id: Long,
        listId: Long,
    ): GuestListEntry {
        return GuestListEntry(
            id = id,
            listId = listId,
            fullName = "Guest",
            phone = null,
            guestsCount = 2,
            notes = null,
            status = GuestListEntryStatus.PLANNED,
            checkedInAt = null,
            checkedInBy = null,
        )
    }

    "happy path returns 200 and marks arrival" {
        val repository = mockk<GuestListRepository>()
        val listId = 55L
        val entryId = 77L
        val list = buildList(listId)
        val entry = buildEntry(entryId, listId)
        val qr = QrGuestListCodec.encode(listId, entryId, clock.instant().minusSeconds(60), qrSecret)
        val initData = validInitData()

        coEvery { repository.getList(listId) } returns list
        coEvery { repository.findEntry(entryId) } returns entry
        coEvery { repository.markArrived(entryId, any()) } returns true

        testApplication {
            applicationDev { testModule(repository) }
            val response =
                client.post("/api/clubs/$clubId/checkin/scan") {
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-Id", telegramId.toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"qr":"$qr"}""")
                }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["status"]!!.jsonPrimitive.content shouldBe "ARRIVED"
        }

        coVerify(exactly = 1) { repository.markArrived(entryId, any()) }
    }

    "malformed or expired qr returns 400" {
        val repository = mockk<GuestListRepository>(relaxed = true)
        val initData = validInitData()

        testApplication {
            applicationDev { testModule(repository) }
            val malformed =
                client.post("/api/clubs/$clubId/checkin/scan") {
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-Id", telegramId.toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"qr":"not-a-token"}""")
                }
            malformed.status shouldBe HttpStatusCode.BadRequest

            val expiredToken =
                QrGuestListCodec.encode(
                    listId = 99L,
                    entryId = 100L,
                    issuedAt = clock.instant().minus(qrTtl).minusSeconds(1),
                    secret = qrSecret,
                )
            val expired =
                client.post("/api/clubs/$clubId/checkin/scan") {
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-Id", telegramId.toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"qr":"$expiredToken"}""")
                }
            expired.status shouldBe HttpStatusCode.BadRequest
        }

        coVerify(exactly = 0) { repository.getList(any()) }
    }

    "list not found returns 404" {
        val repository = mockk<GuestListRepository>()
        val listId = 10L
        val entryId = 20L
        val qr = QrGuestListCodec.encode(listId, entryId, clock.instant(), qrSecret)
        val initData = validInitData()

        coEvery { repository.getList(listId) } returns null

        testApplication {
            applicationDev { testModule(repository) }
            val response =
                client.post("/api/clubs/$clubId/checkin/scan") {
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-Id", telegramId.toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"qr":"$qr"}""")
                }
            response.status shouldBe HttpStatusCode.NotFound
        }

        coVerify(exactly = 0) { repository.findEntry(any()) }
    }

    "entry not found returns 404" {
        val repository = mockk<GuestListRepository>()
        val listId = 15L
        val entryId = 25L
        val list = buildList(listId)
        val qr = QrGuestListCodec.encode(listId, entryId, clock.instant(), qrSecret)
        val initData = validInitData()

        coEvery { repository.getList(listId) } returns list
        coEvery { repository.findEntry(entryId) } returns null

        testApplication {
            applicationDev { testModule(repository) }
            val response =
                client.post("/api/clubs/$clubId/checkin/scan") {
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-Id", telegramId.toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"qr":"$qr"}""")
                }
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    "entry list mismatch returns 400" {
        val repository = mockk<GuestListRepository>()
        val listId = 30L
        val entryId = 40L
        val list = buildList(listId)
        val entry = buildEntry(entryId, listId + 1)
        val qr = QrGuestListCodec.encode(listId, entryId, clock.instant(), qrSecret)
        val initData = validInitData()

        coEvery { repository.getList(listId) } returns list
        coEvery { repository.findEntry(entryId) } returns entry

        testApplication {
            applicationDev { testModule(repository) }
            val response =
                client.post("/api/clubs/$clubId/checkin/scan") {
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-Id", telegramId.toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"qr":"$qr"}""")
                }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    "club mismatch returns 403" {
        val repository = mockk<GuestListRepository>()
        val listId = 50L
        val entryId = 60L
        val list = buildList(listId, club = clubId + 1)
        val entry = buildEntry(entryId, listId)
        val qr = QrGuestListCodec.encode(listId, entryId, clock.instant(), qrSecret)
        val initData = validInitData()

        coEvery { repository.getList(listId) } returns list
        coEvery { repository.findEntry(entryId) } returns entry
        coEvery { repository.markArrived(entryId, any()) } returns true

        testApplication {
            applicationDev { testModule(repository) }
            val response =
                client.post("/api/clubs/$clubId/checkin/scan") {
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-Id", telegramId.toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"qr":"$qr"}""")
                }
            response.status shouldBe HttpStatusCode.Forbidden
        }
    }

    "idempotent checkin returns 200 twice" {
        val repository = mockk<GuestListRepository>()
        val listId = 70L
        val entryId = 80L
        val list = buildList(listId)
        val entry = buildEntry(entryId, listId)
        val qr = QrGuestListCodec.encode(listId, entryId, clock.instant().minusSeconds(120), qrSecret)
        val initData = validInitData()

        coEvery { repository.getList(listId) } returns list
        coEvery { repository.findEntry(entryId) } returns entry
        coEvery { repository.markArrived(entryId, any()) } returns true

        testApplication {
            applicationDev { testModule(repository) }
            repeat(2) {
                val response =
                    client.post("/api/clubs/$clubId/checkin/scan") {
                        header("X-Telegram-Init-Data", initData)
                        header("X-Telegram-Id", telegramId.toString())
                        contentType(ContentType.Application.Json)
                        setBody("""{"qr":"$qr"}""")
                    }
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["status"]!!.jsonPrimitive.content shouldBe "ARRIVED"
            }
        }

        coVerify(exactly = 2) { repository.markArrived(entryId, any()) }
    }
})

private fun prepareDatabase(): CheckinDatabaseSetup {
    val dbName = "checkin_routes_${System.nanoTime()}"
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
    return CheckinDatabaseSetup(dataSource, database)
}

private fun registerUser(
    database: Database,
    telegramId: Long,
    roles: Set<Role>,
    clubs: Set<Long>,
) {
    transaction(database) {
        val userId =
            CheckinUsersTable.insert {
                it[CheckinUsersTable.telegramUserId] = telegramId
                it[username] = "user$telegramId"
                it[displayName] = "user$telegramId"
                it[phone] = null
            } get CheckinUsersTable.id
        roles.forEach { role ->
            if (clubs.isEmpty()) {
                CheckinUserRolesTable.insert {
                    it[CheckinUserRolesTable.userId] = userId
                    it[roleCode] = role.name
                    it[scopeType] = "GLOBAL"
                    it[scopeClubId] = null
                }
            } else {
                clubs.forEach { clubId ->
                    CheckinUserRolesTable.insert {
                        it[CheckinUserRolesTable.userId] = userId
                        it[roleCode] = role.name
                        it[scopeType] = "CLUB"
                        it[scopeClubId] = clubId
                    }
                }
            }
        }
    }
}
