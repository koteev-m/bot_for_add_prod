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
import com.example.bot.webapp.InitDataPrincipalKey
import io.ktor.client.request.get
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TEST_BOT_TOKEN = "111111:TEST_BOT_TOKEN"
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
            application { configureTestApplication(module) }

            val issued = fixedNow.minusSeconds(60)
            val qrToken = encodeQr(QR_SECRET, LIST_ID, ENTRY_ID, issued)
            val userJson = """{"id":$TELEGRAM_USER_ID,"username":"entry_mgr","first_name":"Alex","last_name":"S"}"""
            val initData =
                buildInitData(
                    TEST_BOT_TOKEN,
                    userJson,
                    fixedNow.epochSecond - 120,
                )

            val path = "/api/clubs/$CLUB_ID/checkin/scan"
            meterRegistry().clear()
            AppMetricsBinder.bindAll(meterRegistry())
            val beforeMetrics = currentPrometheusSnapshot()
            val totalBefore = beforeMetrics.metricValue("ui_checkin_scan_total")
            val durationCountBefore = beforeMetrics.metricValue("ui_checkin_scan_duration_ms_seconds_count")
            val first =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-Id", TELEGRAM_USER_ID.toString())
                    header("X-Telegram-Username", "entry_mgr")
                    setBody("""{"qr":"$qrToken"}""")
                }
            val firstBody = first.bodyAsText()

            val second =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-Id", TELEGRAM_USER_ID.toString())
                    header("X-Telegram-Username", "entry_mgr")
                    setBody("""{"qr":"$qrToken"}""")
                }
            val afterMetrics = currentPrometheusSnapshot()
            val totalAfter = afterMetrics.metricValue("ui_checkin_scan_total")
            val durationCountAfter = afterMetrics.metricValue("ui_checkin_scan_duration_ms_seconds_count")

            assertAll(
                { assertEquals(HttpStatusCode.OK, first.status) },
                { assertTrue(firstBody.contains("\"ARRIVED\"")) },
                { assertEquals(HttpStatusCode.OK, second.status) },
                {
                    assertTrue(totalAfter >= totalBefore + 1.0) {
                        "expected ui_checkin_scan_total to grow by at least 1, " +
                            "before=$totalBefore after=$totalAfter"
                    }
                },
                {
                    assertTrue(durationCountAfter >= durationCountBefore + 1.0) {
                        "expected ui_checkin_scan_duration_ms_seconds_count to grow by at least 1, " +
                            "before=$durationCountBefore after=$durationCountAfter"
                    }
                },
            )
        }
    }

    @Test
    fun `malformed or expired qr returns 400`() {
        testApplication {
            val module = baseModule()
            application { configureTestApplication(module) }

            val userJson = """{"id":$TELEGRAM_USER_ID,"username":"entry_mgr"}"""
            val initData =
                buildInitData(
                    TEST_BOT_TOKEN,
                    userJson,
                    fixedNow.epochSecond - 30,
                )
            val path = "/api/clubs/$CLUB_ID/checkin/scan"

            meterRegistry().clear()
            AppMetricsBinder.bindAll(meterRegistry())
            val beforeMetrics = currentPrometheusSnapshot()
            val errorBefore = beforeMetrics.metricValue("ui_checkin_scan_error_total")

            val malformed =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-Id", TELEGRAM_USER_ID.toString())
                    header("X-Telegram-Username", "entry_mgr")
                    setBody("""{"qr":"GL:malformed"}""")
                }

            val expiredIssued = fixedNow.minus(qrTtl).minusSeconds(1)
            val expiredQr = encodeQr(QR_SECRET, LIST_ID, ENTRY_ID, expiredIssued)

            val expired =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-Id", TELEGRAM_USER_ID.toString())
                    header("X-Telegram-Username", "entry_mgr")
                    setBody("""{"qr":"$expiredQr"}""")
                }

            val afterMetrics = currentPrometheusSnapshot()
            val errorAfter = afterMetrics.metricValue("ui_checkin_scan_error_total")

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
            application { configureTestApplication(module) }

            val issued = fixedNow.minusSeconds(30)
            val qrToken = encodeQr(QR_SECRET, LIST_ID, ENTRY_ID, issued)
            val userJson = """{"id":$TELEGRAM_USER_ID,"username":"entry_mgr"}"""
            val initData =
                buildInitData(
                    TEST_BOT_TOKEN,
                    userJson,
                    fixedNow.epochSecond - 30,
                )

            val response =
                client.post("/api/clubs/$CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-Id", TELEGRAM_USER_ID.toString())
                    header("X-Telegram-Username", "entry_mgr")
                    setBody("""{"qr":"$qrToken"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `entry not found returns 404`() {
        testApplication {
            val guestListRepository = TestGuestListRepository()
            guestListRepository.removeEntry(ENTRY_ID)
            val module = baseModule(guestListRepository = guestListRepository)
            application { configureTestApplication(module) }

            val issued = fixedNow.minusSeconds(30)
            val qrToken = encodeQr(QR_SECRET, LIST_ID, ENTRY_ID, issued)
            val userJson = """{"id":$TELEGRAM_USER_ID,"username":"entry_mgr"}"""
            val initData =
                buildInitData(
                    TEST_BOT_TOKEN,
                    userJson,
                    fixedNow.epochSecond - 30,
                )

            val response =
                client.post("/api/clubs/$CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-Id", TELEGRAM_USER_ID.toString())
                    header("X-Telegram-Username", "entry_mgr")
                    setBody("""{"qr":"$qrToken"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `entry list mismatch returns 400`() {
        testApplication {
            val guestListRepository = TestGuestListRepository()
            guestListRepository.updateEntry(
                guestListRepository.currentEntry().copy(listId = LIST_ID + 1),
            )
            val module = baseModule(guestListRepository = guestListRepository)
            application { configureTestApplication(module) }

            val issued = fixedNow.minusSeconds(30)
            val qrToken = encodeQr(QR_SECRET, LIST_ID, ENTRY_ID, issued)
            val userJson = """{"id":$TELEGRAM_USER_ID,"username":"entry_mgr"}"""
            val initData =
                buildInitData(
                    TEST_BOT_TOKEN,
                    userJson,
                    fixedNow.epochSecond - 30,
                )

            val response =
                client.post("/api/clubs/$CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-Id", TELEGRAM_USER_ID.toString())
                    header("X-Telegram-Username", "entry_mgr")
                    setBody("""{"qr":"$qrToken"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `scope mismatch returns 403`() {
        testApplication {
            val module =
                baseModule(
                    userRoleRepository = TestUserRoleRepository(clubIds = setOf(CLUB_ID + 1)),
                )
            application { configureTestApplication(module) }

            val issued = fixedNow.minusSeconds(30)
            val qrToken = encodeQr(QR_SECRET, LIST_ID, ENTRY_ID, issued)
            val userJson = """{"id":$TELEGRAM_USER_ID,"username":"entry_mgr"}"""
            val initData =
                buildInitData(
                    TEST_BOT_TOKEN,
                    userJson,
                    fixedNow.epochSecond - 30,
                )

            val response =
                client.post("/api/clubs/$CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-Id", TELEGRAM_USER_ID.toString())
                    header("X-Telegram-Username", "entry_mgr")
                    setBody("""{"qr":"$qrToken"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `missing or invalid init data returns 401`() {
        testApplication {
            val module = baseModule()
            application { configureTestApplication(module) }

            val issued = fixedNow.minusSeconds(30)
            val qrToken = encodeQr(QR_SECRET, LIST_ID, ENTRY_ID, issued)
            val userJson = """{"id":$TELEGRAM_USER_ID,"username":"entry_mgr"}"""
            val validInitData =
                buildInitData(
                    TEST_BOT_TOKEN,
                    userJson,
                    fixedNow.epochSecond - 30,
                )
            val invalidInitData = tamperLastCharacter(validInitData)

            val missingHeader =
                client.post("/api/clubs/$CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    header("X-Telegram-Id", TELEGRAM_USER_ID.toString())
                    header("X-Telegram-Username", "entry_mgr")
                    setBody("""{"qr":"$qrToken"}""")
                }

            val invalidHeader =
                client.post("/api/clubs/$CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    header("X-Telegram-Init-Data", invalidInitData)
                    header("X-Telegram-Id", TELEGRAM_USER_ID.toString())
                    header("X-Telegram-Username", "entry_mgr")
                    setBody("""{"qr":"$qrToken"}""")
                }

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
    install(RbacPlugin) {
        userRepository = get()
        userRoleRepository = get()
        auditLogRepository = get()
        principalExtractor = { call ->
            val initDataPrincipal =
                if (call.attributes.contains(InitDataPrincipalKey)) {
                    call.attributes[InitDataPrincipalKey]
                } else {
                    null
                }
            if (initDataPrincipal != null) {
                TelegramPrincipal(initDataPrincipal.userId, initDataPrincipal.username)
            } else {
                call.request.header("X-Telegram-Id")?.toLongOrNull()?.let { id ->
                    TelegramPrincipal(id, call.request.header("X-Telegram-Username"))
                }
            }
        }
    }
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
): Module {
    return module {
        single { guestListRepository }
        single { userRepository }
        single { userRoleRepository }
        single { auditLogRepository }
    }
}

private class TestGuestListRepository : GuestListRepository {
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
    ): GuestList = throw UnsupportedOperationException("Not required for smoke test")

    override suspend fun getList(id: Long): GuestList? {
        return if (list?.id == id) list else null
    }

    override suspend fun findEntry(id: Long): GuestListEntry? {
        return if (entry?.id == id) entry else null
    }

    override suspend fun listListsByClub(
        clubId: Long,
        page: Int,
        size: Int,
    ): List<GuestList> = throw UnsupportedOperationException("Not required for smoke test")

    override suspend fun addEntry(
        listId: Long,
        fullName: String,
        phone: String?,
        guestsCount: Int,
        notes: String?,
        status: GuestListEntryStatus,
    ): GuestListEntry = throw UnsupportedOperationException("Not required for smoke test")

    override suspend fun setEntryStatus(
        entryId: Long,
        status: GuestListEntryStatus,
        checkedInBy: Long?,
        at: Instant?,
    ): GuestListEntry? = throw UnsupportedOperationException("Not required for smoke test")

    override suspend fun listEntries(
        listId: Long,
        page: Int,
        size: Int,
        statusFilter: GuestListEntryStatus?,
    ): List<GuestListEntry> = throw UnsupportedOperationException("Not required for smoke test")

    override suspend fun markArrived(
        entryId: Long,
        at: Instant,
    ): Boolean {
        return entry?.id == entryId
    }

    override suspend fun bulkImport(
        listId: Long,
        rows: List<ParsedGuest>,
        dryRun: Boolean,
    ): com.example.bot.club.BulkImportResult = throw UnsupportedOperationException("Not required for smoke test")

    override suspend fun searchEntries(
        filter: GuestListEntrySearch,
        page: Int,
        size: Int,
    ): GuestListEntryPage = throw UnsupportedOperationException("Not required for smoke test")

    fun removeList(id: Long) {
        if (list?.id == id) {
            list = null
        }
    }

    fun removeEntry(id: Long) {
        if (entry?.id == id) {
            entry = null
        }
    }

    fun updateEntry(updated: GuestListEntry) {
        entry = updated
    }

    fun currentEntry(): GuestListEntry {
        return requireNotNull(entry) { "Entry not configured" }
    }

    companion object {
        private fun defaultList(): GuestList {
            return GuestList(
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
        }

        private fun defaultEntry(): GuestListEntry {
            return GuestListEntry(
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
}

private class TestUserRepository : UserRepository {
    private val user = User(id = INTERNAL_USER_ID, telegramId = TELEGRAM_USER_ID, username = "entry_mgr")

    override suspend fun getByTelegramId(id: Long): User? {
        return if (id == TELEGRAM_USER_ID) user else null
    }
}

private class TestUserRoleRepository(
    private val roles: Set<Role> = setOf(Role.ENTRY_MANAGER),
    private val clubIds: Set<Long> = setOf(CLUB_ID),
) : UserRoleRepository {
    override suspend fun listRoles(userId: Long): Set<Role> = roles

    override suspend fun listClubIdsFor(userId: Long): Set<Long> = clubIds
}

private fun relaxedAuditRepository(): AuditLogRepository {
    return mockk(relaxed = true)
}

private fun hmacSha256(
    key: ByteArray,
    data: ByteArray,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun hexLower(bytes: ByteArray): String {
    val builder = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        val high = value shr 4
        val low = value and 0x0F
        builder.append("0123456789abcdef"[high])
        builder.append("0123456789abcdef"[low])
    }
    return builder.toString()
}

private fun buildInitData(
    botToken: String,
    userJson: String,
    authDateEpoch: Long,
): String {
    val parameters =
        linkedMapOf(
            "user" to userJson,
            "auth_date" to authDateEpoch.toString(),
        )
    val secretKey =
        hmacSha256(
            botToken.toByteArray(StandardCharsets.UTF_8),
            "WebAppData".toByteArray(StandardCharsets.UTF_8),
        )
    val dataCheckString =
        parameters
            .toSortedMap()
            .entries
            .joinToString("\n") { (key, value) -> "$key=$value" }
    val hash =
        hexLower(
            hmacSha256(
                secretKey,
                dataCheckString.toByteArray(StandardCharsets.UTF_8),
            ),
        )
    val encodedPairs =
        parameters.entries.joinToString("&") { (key, value) ->
            val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8)
            val encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8)
            "$encodedKey=$encodedValue"
        }
    return "$encodedPairs&hash=$hash"
}

private fun encodeQr(
    secret: String,
    listId: Long,
    entryId: Long,
    issued: Instant,
): String {
    return QrGuestListCodec.encode(listId, entryId, issued, secret)
}

private fun currentPrometheusSnapshot(): String {
    val registry = meterRegistry()
    return (registry as? PrometheusMeterRegistry)?.scrape() ?: ""
}

private fun tamperLastCharacter(value: String): String {
    if (value.isEmpty()) {
        return value
    }
    val replacement = if (value.last() == '0') '1' else '0'
    return value.dropLast(1) + replacement
}

private fun String.metricValue(name: String): Double {
    return lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith(name) }
        ?.substringAfter(' ')
        ?.trim()
        ?.toDoubleOrNull()
        ?: 0.0
}
