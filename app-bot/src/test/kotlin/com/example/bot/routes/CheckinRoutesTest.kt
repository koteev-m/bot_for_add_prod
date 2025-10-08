package com.example.bot.routes

import com.example.bot.club.GuestList
import com.example.bot.club.GuestListEntry
import com.example.bot.club.GuestListEntryPage
import com.example.bot.club.GuestListEntrySearch
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListStatus
import com.example.bot.club.ParsedGuest
import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.guestlists.GuestListRepository
import com.example.bot.guestlists.QrGuestListCodec
import com.example.bot.metrics.AppMetricsBinder
import com.example.bot.plugins.installMetrics
import com.example.bot.plugins.meterRegistry
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.testing.applicationDev
import com.example.bot.testing.withInitData
import com.example.bot.webapp.InitDataPrincipalKey
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
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
import io.ktor.server.request.header
import io.ktor.server.testing.testApplication
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private const val QR_SECRET = "qr_test_secret"
private const val TELEGRAM_USER_ID = 123456789L
private const val INTERNAL_USER_ID = 5000L
private const val CLUB_ID = 1L
private const val LIST_ID = 100L
private const val ENTRY_ID = 200L

private val fixedNow: Instant = Instant.parse("2024-06-01T10:15:30Z")
private val fixedClock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
private val qrTtl: Duration = Duration.ofHours(12)

class EntryManagerCheckInSmokeTest {
    @Test
    fun `happy path ARRIVED`() {
        testApplication {
            val guestListRepository = TestGuestListRepository()
            val module = baseModule(guestListRepository = guestListRepository)
            applicationDev { configureTestApplication(module) }

            val issued = fixedNow.minusSeconds(60)
            val qrToken = encodeQr(QR_SECRET, LIST_ID, ENTRY_ID, issued)
            val initData = createInitData()

            val path = "/api/clubs/$CLUB_ID/checkin/scan"
            meterRegistry().clear()
            AppMetricsBinder.bindAll(meterRegistry())
            val beforeMetrics = currentPrometheusSnapshot()
            val totalBefore = beforeMetrics.metricValue("ui_checkin_scan_total")
            val durationCountBefore = beforeMetrics.metricValue("ui_checkin_scan_duration_ms_seconds_count")

            val first =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    withInitData(initData)
                    setBody("""{"qr":"$qrToken"}""")
                }
            val firstBody = first.bodyAsText()
            println("DBG happy path: status1=${first.status} body1=$firstBody")

            val second =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    withInitData(initData)
                    setBody("""{"qr":"$qrToken"}""")
                }
            println("DBG happy path: status2=${second.status}")

            val afterMetrics = currentPrometheusSnapshot()
            val totalAfter = afterMetrics.metricValue("ui_checkin_scan_total")
            val durationCountAfter = afterMetrics.metricValue("ui_checkin_scan_duration_ms_seconds_count")

            assertAll(
                { assertEquals(HttpStatusCode.OK, first.status) },
                { assertTrue(firstBody.contains("\"ARRIVED\"")) },
                { assertEquals(HttpStatusCode.OK, second.status) },
                { assertTrue(totalAfter >= totalBefore + 1.0) },
                { assertTrue(durationCountAfter >= durationCountBefore + 1.0) },
            )
        }
    }

    @Test
    fun `malformed or expired qr returns 400`() {
        testApplication {
            val module = baseModule()
            applicationDev { configureTestApplication(module) }

            val initData = createInitData()
            val path = "/api/clubs/$CLUB_ID/checkin/scan"

            meterRegistry().clear()
            AppMetricsBinder.bindAll(meterRegistry())
            val before = currentPrometheusSnapshot()
            val errorBefore = before.metricValue("ui_checkin_scan_error_total")

            val malformed =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    withInitData(initData)
                    setBody("""{"qr":"GL:malformed"}""")
                }
            println("DBG malformed: ${malformed.status} ${malformed.bodyAsText()}")

            val expiredIssued = fixedNow.minus(qrTtl).minusSeconds(1)
            val expiredQr = encodeQr(QR_SECRET, LIST_ID, ENTRY_ID, expiredIssued)

            val expired =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    withInitData(initData)
                    setBody("""{"qr":"$expiredQr"}""")
                }
            println("DBG expired:   ${expired.status} ${expired.bodyAsText()}")

            val after = currentPrometheusSnapshot()
            val errorAfter = after.metricValue("ui_checkin_scan_error_total")

            assertAll(
                { assertEquals(HttpStatusCode.BadRequest, malformed.status) },
                { assertEquals(HttpStatusCode.BadRequest, expired.status) },
                { assertTrue(errorAfter >= errorBefore + 2.0) },
            )
        }
    }

    @Test
    fun `list not found returns 404`() {
        testApplication {
            val guestListRepository = TestGuestListRepository()
            guestListRepository.removeList(LIST_ID)
            val module = baseModule(guestListRepository = guestListRepository)
            applicationDev { configureTestApplication(module) }

            val issued = fixedNow.minusSeconds(30)
            val qrToken = encodeQr(QR_SECRET, LIST_ID, ENTRY_ID, issued)
            val initData = createInitData()

            val response =
                client.post("/api/clubs/$CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    withInitData(initData)
                    setBody("""{"qr":"$qrToken"}""")
                }
            println("DBG list-not-found: ${response.status} ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `entry not found returns 404`() {
        testApplication {
            val guestListRepository = TestGuestListRepository()
            guestListRepository.removeEntry(ENTRY_ID)
            val module = baseModule(guestListRepository = guestListRepository)
            applicationDev { configureTestApplication(module) }

            val issued = fixedNow.minusSeconds(30)
            val qrToken = encodeQr(QR_SECRET, LIST_ID, ENTRY_ID, issued)
            val initData = createInitData()

            val response =
                client.post("/api/clubs/$CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    withInitData(initData)
                    setBody("""{"qr":"$qrToken"}""")
                }
            println("DBG entry-not-found: ${response.status} ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `entry list mismatch returns 400`() {
        testApplication {
            val guestListRepository = TestGuestListRepository()
            guestListRepository.updateEntry(guestListRepository.currentEntry().copy(listId = LIST_ID + 1))
            val module = baseModule(guestListRepository = guestListRepository)
            applicationDev { configureTestApplication(module) }

            val issued = fixedNow.minusSeconds(30)
            val qrToken = encodeQr(QR_SECRET, LIST_ID, ENTRY_ID, issued)
            val initData = createInitData()

            val response =
                client.post("/api/clubs/$CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    withInitData(initData)
                    setBody("""{"qr":"$qrToken"}""")
                }
            println("DBG list-mismatch: ${response.status} ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `scope mismatch returns 403`() {
        testApplication {
            val module = baseModule(userRoleRepository = TestUserRoleRepository(clubIds = setOf(CLUB_ID + 1)))
            applicationDev { configureTestApplication(module) }

            val issued = fixedNow.minusSeconds(30)
            val qrToken = encodeQr(QR_SECRET, LIST_ID, ENTRY_ID, issued)
            val initData = createInitData()

            val response =
                client.post("/api/clubs/$CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    withInitData(initData)
                    setBody("""{"qr":"$qrToken"}""")
                }
            println("DBG scope-mismatch: ${response.status} ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `missing or invalid init data returns 401`() {
        testApplication {
            val module = baseModule()
            applicationDev { configureTestApplication(module) }

            val issued = fixedNow.minusSeconds(30)
            val qrToken = encodeQr(QR_SECRET, LIST_ID, ENTRY_ID, issued)
            val validInitData = createInitData()
            val invalidInitData = tamperLastCharacter(validInitData)

            val missingHeader =
                client.post("/api/clubs/$CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"qr":"$qrToken"}""")
                }

            val invalidHeader =
                client.post("/api/clubs/$CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    withInitData(invalidInitData)
                    setBody("""{"qr":"$qrToken"}""")
                }

            println("DBG missing=${missingHeader.status} ${missingHeader.bodyAsText()}")
            println("DBG invalid=${invalidHeader.status} ${invalidHeader.bodyAsText()}")

            assertAll(
                { assertEquals(HttpStatusCode.Unauthorized, missingHeader.status) },
                { assertEquals(HttpStatusCode.Unauthorized, invalidHeader.status) },
            )
        }
    }
}

private fun Application.configureTestApplication(module: Module) {
    meterRegistry().clear()
    installMetrics()
    AppMetricsBinder.bindAll(meterRegistry())

    install(ContentNegotiation) { json() }
    install(Koin) { modules(module) }

    // RBAC ставим ДО маршрутов
    install(RbacPlugin) {
        userRepository = get()
        userRoleRepository = get()
        auditLogRepository = get()
        principalExtractor = { call ->
            // 1) Если InitDataAuth уже успел положить атрибут — используем его
            val initAttr =
                if (call.attributes.contains(InitDataPrincipalKey)) {
                    call.attributes[InitDataPrincipalKey]
                } else null
            if (initAttr != null) {
                TelegramPrincipal(initAttr.userId, initAttr.username)
            } else {
                // 2) Фоллбэк: читаем initData из любого из популярных заголовков
                val header =
                    sequenceOf(
                        "X-Telegram-Init-Data",
                        "X-Init-Data",
                        "initData",
                        "Init-Data",
                        "x-telegram-init-data", // на всякий случай
                    ).mapNotNull { call.request.header(it) }
                        .firstOrNull()

                val verified = header?.let { com.example.bot.webapp.WebAppInitDataVerifier.verify(it, TEST_BOT_TOKEN) }
                verified?.let { TelegramPrincipal(it.userId, it.username) }
            }
        }
    }

    // Маршруты (внутри них на route навешивается InitDataAuth с тем же токеном)
    checkinRoutes(
        repository = get(),
        qrSecretProvider = { QR_SECRET },
        clock = fixedClock,
        qrTtl = qrTtl,
        initDataAuth = { botTokenProvider = { TEST_BOT_TOKEN } },
    )
}

private fun baseModule(
    guestListRepository: GuestListRepository = TestGuestListRepository(),
    userRepository: UserRepository = TestUserRepository(),
    userRoleRepository: UserRoleRepository = TestUserRoleRepository(),
    auditLogRepository: AuditLogRepository = relaxedAuditRepository(),
): Module =
    module {
        single { guestListRepository }
        single { userRepository }
        single { userRoleRepository }
        single { auditLogRepository }
    }

/* ==== Стабы ==== */

class TestGuestListRepository : GuestListRepository {
    private var list: GuestList? = defaultList()
    private var entry: GuestListEntry? = defaultEntry()

    override suspend fun createList(
        clubId: Long,
        eventId: Long,
        ownerType: GuestListOwnerType,
        ownerUserId: Long,
        title: String,
        capacity: Int,
        arrivalWindowStart: Instant?,
        arrivalWindowEnd: Instant?,
        status: GuestListStatus,
    ): GuestList = throw UnsupportedOperationException()

    override suspend fun getList(id: Long): GuestList? = if (list?.id == id) list else null
    override suspend fun findEntry(id: Long): GuestListEntry? = if (entry?.id == id) entry else null
    override suspend fun listListsByClub(clubId: Long, page: Int, size: Int): List<GuestList> = throw UnsupportedOperationException()
    override suspend fun addEntry(listId: Long, fullName: String, phone: String?, guestsCount: Int, notes: String?, status: GuestListEntryStatus): GuestListEntry =
        throw UnsupportedOperationException()
    override suspend fun setEntryStatus(entryId: Long, status: GuestListEntryStatus, checkedInBy: Long?, at: Instant?): GuestListEntry? =
        throw UnsupportedOperationException()
    override suspend fun listEntries(listId: Long, page: Int, size: Int, statusFilter: GuestListEntryStatus?): List<GuestListEntry> =
        throw UnsupportedOperationException()
    override suspend fun markArrived(entryId: Long, at: Instant): Boolean = entry?.id == entryId
    override suspend fun bulkImport(listId: Long, rows: List<ParsedGuest>, dryRun: Boolean) = throw UnsupportedOperationException()
    override suspend fun searchEntries(filter: GuestListEntrySearch, page: Int, size: Int): GuestListEntryPage =
        throw UnsupportedOperationException()

    fun removeList(id: Long) { if (list?.id == id) list = null }
    fun removeEntry(id: Long) { if (entry?.id == id) entry = null }
    fun updateEntry(updated: GuestListEntry) { entry = updated }
    fun currentEntry(): GuestListEntry = requireNotNull(entry) { "Entry not configured" }

    companion object {
        private fun defaultList(): GuestList =
            GuestList(
                id = LIST_ID,
                clubId = CLUB_ID,
                eventId = 10L,
                ownerType = GuestListOwnerType.MANAGER,
                ownerUserId = INTERNAL_USER_ID,
                title = "VIP",
                capacity = 100,
                arrivalWindowStart = null,
                arrivalWindowEnd = null,
                status = GuestListStatus.ACTIVE,
                createdAt = fixedNow,
            )

        private fun defaultEntry(): GuestListEntry =
            GuestListEntry(
                id = ENTRY_ID,
                listId = LIST_ID,
                fullName = "Guest",
                phone = null,
                guestsCount = 1,
                notes = null,
                status = GuestListEntryStatus.PLANNED,
                checkedInAt = null,
                checkedInBy = null,
            )
    }
}

private class TestUserRepository : UserRepository {
    private val user = User(id = INTERNAL_USER_ID, telegramId = TELEGRAM_USER_ID, username = "entry_mgr")
    override suspend fun getByTelegramId(id: Long): User? = if (id == TELEGRAM_USER_ID) user else null
}

private class TestUserRoleRepository(
    private val roles: Set<Role> = setOf(Role.ENTRY_MANAGER),
    private val clubIds: Set<Long> = setOf(CLUB_ID),
) : UserRoleRepository {
    override suspend fun listRoles(userId: Long): Set<Role> = roles
    override suspend fun listClubIdsFor(userId: Long): Set<Long> = clubIds
}

private fun relaxedAuditRepository(): AuditLogRepository = mockk(relaxed = true)

/* ==== Утилиты ==== */

@Suppress("SameParameterValue")
private fun encodeQr(secret: String, listId: Long, entryId: Long, issued: Instant): String =
    QrGuestListCodec.encode(listId, entryId, issued, secret)

private fun currentPrometheusSnapshot(): String {
    val registry = meterRegistry()
    return (registry as? PrometheusMeterRegistry)?.scrape() ?: ""
}

private fun tamperLastCharacter(value: String): String {
    if (value.isEmpty()) return value
    val replacement = if (value.last() == '0') '1' else '0'
    return value.dropLast(1) + replacement
}

private fun String.metricValue(name: String): Double =
    lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith(name) }
        ?.substringAfter(' ')
        ?.trim()
        ?.toDoubleOrNull()
        ?: 0.0

// Готовый хелпер для корректной подписи initData
private fun createInitData(
    userId: Long = TELEGRAM_USER_ID,
    username: String = "entry_mgr",
): String {
    val params = linkedMapOf(
        "user" to WebAppInitDataTestHelper.encodeUser(id = userId, username = username),
        "auth_date" to Instant.now().epochSecond.toString(),
    )
    return WebAppInitDataTestHelper.createInitData(TEST_BOT_TOKEN, params)
}
