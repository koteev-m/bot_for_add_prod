package com.example.bot.guestlist

import com.example.bot.testing.createInitData
import com.example.bot.testing.defaultRequest
import com.example.bot.testing.header
import com.example.bot.testing.withInitData
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListRepository
import com.example.bot.club.GuestListStatus
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.club.GuestListCsvParser
import com.example.bot.data.club.GuestListRepositoryImpl
import com.example.bot.data.security.Role
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.plugins.configureSecurity
import com.example.bot.routes.guestListRoutes
import com.example.bot.webapp.TEST_BOT_TOKEN
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class GuestListRoutesTest : StringSpec({
    lateinit var dataSource: JdbcDataSource
    lateinit var database: Database
    lateinit var repository: GuestListRepository
    val parser = GuestListCsvParser()

    beforeTest {
        val setup = prepareDatabase()
        dataSource = setup.dataSource
        database = setup.database
        repository = GuestListRepositoryImpl(database)
    }

    afterTest {
        DataSourceHolder.dataSource = null
    }

    fun Application.testModule() {
        DataSourceHolder.dataSource = dataSource
        install(ContentNegotiation) { json() }
        configureSecurity()
        guestListRoutes(
            repository = repository,
            parser = parser,
            initDataAuth = { botTokenProvider = { TEST_BOT_TOKEN } },
        )
    }

    fun ApplicationTestBuilder.authenticatedClient(
        telegramId: Long,
        username: String = "user$telegramId",
    ): HttpClient {
        return defaultRequest {
            withInitData(createInitData(userId = telegramId, username = username))
            header("X-Telegram-Id", telegramId.toString())
            if (username.isNotBlank()) {
                header("X-Telegram-Username", username)
            }
        }
    }

    suspend fun createClub(name: String): Long {
        return transaction(database) {
            ClubsTable.insert {
                it[ClubsTable.name] = name
                it[timezone] = "Europe/Moscow"
                it[description] = null
                it[adminChannelId] = null
                it[bookingsTopicId] = null
                it[checkinTopicId] = null
                it[qaTopicId] = null
            } get ClubsTable.id
        }
    }

    suspend fun createEvent(
        clubId: Long,
        title: String,
    ): Long {
        val start = Instant.parse("2024-07-01T18:00:00Z")
        val end = Instant.parse("2024-07-02T02:00:00Z")
        return transaction(database) {
            EventsTable.insert {
                it[EventsTable.clubId] = clubId
                it[EventsTable.title] = title
                it[startAt] = start.atOffset(ZoneOffset.UTC)
                it[endAt] = end.atOffset(ZoneOffset.UTC)
            } get EventsTable.id
        }
    }

    suspend fun createDomainUser(username: String): Long {
        return transaction(database) {
            UsersTable.insert {
                it[telegramUserId] = null
                it[UsersTable.username] = username
                it[displayName] = username
                it[phone] = null
            } get UsersTable.id
        }
    }

    suspend fun registerRbacUser(
        telegramId: Long,
        roles: Set<Role>,
        clubs: Set<Long>,
    ): Long {
        return transaction(database) {
            roles.forEach { role ->
                if (RolesTable.selectAll().none { it[RolesTable.code] == role.name }) {
                    RolesTable.insert { it[code] = role.name }
                }
            }
            val userId =
                UsersTable.insert {
                    it[UsersTable.telegramUserId] = telegramId
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
            userId
        }
    }

    "import dry run returns json report" {
        val clubId = createClub("Nebula")
        val eventId = createEvent(clubId, "Launch")
        val ownerId = createDomainUser("owner1")
        val list =
            repository.createList(
                clubId = clubId,
                eventId = eventId,
                ownerType = GuestListOwnerType.MANAGER,
                ownerUserId = ownerId,
                title = "VIP",
                capacity = 50,
                arrivalWindowStart = null,
                arrivalWindowEnd = null,
                status = GuestListStatus.ACTIVE,
            )
        registerRbacUser(telegramId = 100L, roles = setOf(Role.MANAGER), clubs = setOf(clubId))

        testApplication {
            application { testModule() }
            val authedClient = authenticatedClient(telegramId = 100L)
            val response =
                authedClient.post("/api/guest-lists/${list.id}/import?dry_run=true") {
                    contentType(ContentType.Text.CSV)
                    setBody("name,phone,guests_count,notes\nAlice,+123456789,2,VIP\n")
                }
            response.status shouldBe HttpStatusCode.OK
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            json["accepted"]!!.toString() shouldBe "1"
            json["rejected"]!!.jsonArray.shouldHaveSize(0)
            repository.listEntries(list.id, page = 0, size = 10) shouldHaveSize 0
        }
    }

    "commit import persists and returns csv" {
        val clubId = createClub("Orion")
        val eventId = createEvent(clubId, "Opening")
        val ownerId = createDomainUser("owner2")
        val list =
            repository.createList(
                clubId = clubId,
                eventId = eventId,
                ownerType = GuestListOwnerType.MANAGER,
                ownerUserId = ownerId,
                title = "Friends",
                capacity = 30,
                arrivalWindowStart = null,
                arrivalWindowEnd = null,
                status = GuestListStatus.ACTIVE,
            )
        registerRbacUser(telegramId = 200L, roles = setOf(Role.MANAGER), clubs = setOf(clubId))

        testApplication {
            application { testModule() }
            val authedClient = authenticatedClient(telegramId = 200L)
            val response =
                authedClient.post("/api/guest-lists/${list.id}/import") {
                    header(HttpHeaders.Accept, ContentType.Text.CSV.toString())
                    contentType(ContentType.Text.CSV)
                    setBody("name,phone,guests_count,notes\nBob,+123456700,3,\n")
                }
            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.ContentType]!!.startsWith("text/csv") shouldBe true
            repository.listEntries(list.id, page = 0, size = 10) shouldHaveSize 1
        }
    }

    "manager sees only own club" {
        val clubA = createClub("Nova")
        val clubB = createClub("Pulse")
        val eventA = createEvent(clubA, "Night A")
        val eventB = createEvent(clubB, "Night B")
        val ownerA = createDomainUser("managerA")
        val ownerB = createDomainUser("managerB")
        val listA =
            repository.createList(
                clubId = clubA,
                eventId = eventA,
                ownerType = GuestListOwnerType.MANAGER,
                ownerUserId = ownerA,
                title = "List A",
                capacity = 20,
                arrivalWindowStart = null,
                arrivalWindowEnd = null,
                status = GuestListStatus.ACTIVE,
            )
        val listB =
            repository.createList(
                clubId = clubB,
                eventId = eventB,
                ownerType = GuestListOwnerType.MANAGER,
                ownerUserId = ownerB,
                title = "List B",
                capacity = 20,
                arrivalWindowStart = null,
                arrivalWindowEnd = null,
                status = GuestListStatus.ACTIVE,
            )
        repository.addEntry(listA.id, "Alice", "+100", 2, null)
        repository.addEntry(listB.id, "Clare", "+200", 1, null)
        registerRbacUser(telegramId = 300L, roles = setOf(Role.MANAGER), clubs = setOf(clubA))

        testApplication {
            application { testModule() }
            val authedClient = authenticatedClient(telegramId = 300L)
            val response = authedClient.get("/api/guest-lists")
            response.status shouldBe HttpStatusCode.OK
            val items = Json.parseToJsonElement(response.bodyAsText()).jsonObject["items"]!!.jsonArray
            items.shouldHaveSize(1)
            items.first().jsonObject["clubId"].toString() shouldBe clubA.toString()

            val forbidden = authedClient.get("/api/guest-lists?club=$clubB")
            forbidden.status shouldBe HttpStatusCode.Forbidden
        }
    }

    "promoter filtered by owner" {
        val club = createClub("Pulse")
        val event = createEvent(club, "Promo")
        val promoterUserId = registerRbacUser(telegramId = 400L, roles = setOf(Role.PROMOTER), clubs = setOf(club))
        val managerOwner = createDomainUser("manager")
        val promoterList =
            repository.createList(
                clubId = club,
                eventId = event,
                ownerType = GuestListOwnerType.PROMOTER,
                ownerUserId = promoterUserId,
                title = "Promo",
                capacity = 15,
                arrivalWindowStart = null,
                arrivalWindowEnd = null,
                status = GuestListStatus.ACTIVE,
            )
        val managerList =
            repository.createList(
                clubId = club,
                eventId = event,
                ownerType = GuestListOwnerType.MANAGER,
                ownerUserId = managerOwner,
                title = "Manager",
                capacity = 15,
                arrivalWindowStart = null,
                arrivalWindowEnd = null,
                status = GuestListStatus.ACTIVE,
            )
        repository.addEntry(promoterList.id, "Promo Guest", "+111", 1, null)
        repository.addEntry(managerList.id, "Club Guest", "+222", 1, null)

        testApplication {
            application { testModule() }
            val authedClient = authenticatedClient(telegramId = 400L)
            val response = authedClient.get("/api/guest-lists")
            response.status shouldBe HttpStatusCode.OK
            val items = Json.parseToJsonElement(response.bodyAsText()).jsonObject["items"]!!.jsonArray
            items.shouldHaveSize(1)
            items.first().jsonObject["listId"].toString() shouldBe promoterList.id.toString()
        }
    }

    "head manager export returns csv" {
        val club = createClub("Zenith")
        val event = createEvent(club, "Gala")
        val owner = createDomainUser("head")
        val list =
            repository.createList(
                clubId = club,
                eventId = event,
                ownerType = GuestListOwnerType.MANAGER,
                ownerUserId = owner,
                title = "Gala",
                capacity = 40,
                arrivalWindowStart = null,
                arrivalWindowEnd = null,
                status = GuestListStatus.ACTIVE,
            )
        repository.addEntry(list.id, "Guest One", "+500", 2, "VIP")
        registerRbacUser(telegramId = 500L, roles = setOf(Role.HEAD_MANAGER), clubs = emptySet())

        testApplication {
            application { testModule() }
            val authedClient = authenticatedClient(telegramId = 500L)
            val response = authedClient.get("/api/guest-lists/export")
            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.ContentType]!!.startsWith("text/csv") shouldBe true
            response.bodyAsText().contains("Guest One") shouldBe true
        }
    }
})

private data class DbSetup(val dataSource: JdbcDataSource, val database: Database)

private fun prepareDatabase(): DbSetup {
    val dbName = "guestlists_${UUID.randomUUID()}"
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
    return DbSetup(dataSource, database)
}

private object ClubsTable : Table("clubs") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val description = text("description").nullable()
    val timezone = text("timezone")
    val adminChannelId = long("admin_channel_id").nullable()
    val bookingsTopicId = integer("bookings_topic_id").nullable()
    val checkinTopicId = integer("checkin_topic_id").nullable()
    val qaTopicId = integer("qa_topic_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object UsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id").nullable()
    val username = text("username").nullable()
    val displayName = text("display_name").nullable()
    val phone = text("phone_e164").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object RolesTable : Table("roles") {
    val code = text("code")
    override val primaryKey = PrimaryKey(code)
}

private object UserRolesTable : Table("user_roles") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val roleCode = text("role_code")
    val scopeType = text("scope_type")
    val scopeClubId = long("scope_club_id").nullable()
    override val primaryKey = PrimaryKey(id)
}
