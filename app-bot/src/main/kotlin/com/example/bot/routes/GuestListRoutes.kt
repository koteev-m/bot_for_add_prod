package com.example.bot.routes

import com.example.bot.club.GuestList
import com.example.bot.club.GuestListEntrySearch
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListEntryView
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListRepository
import com.example.bot.club.RejectedRow
import com.example.bot.data.club.GuestListCsvParser
import com.example.bot.data.security.Role
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.RbacContext
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.acceptItems
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.use

/** Registers guest list management routes. */
fun Application.guestListRoutes(repository: GuestListRepository, parser: GuestListCsvParser) {
    routing {
        authorize(
            Role.OWNER,
            Role.GLOBAL_ADMIN,
            Role.HEAD_MANAGER,
            Role.CLUB_ADMIN,
            Role.MANAGER,
            Role.ENTRY_MANAGER,
            Role.PROMOTER,
        ) {
            get("/api/guest-lists") {
                val context = call.rbacContext()
                val query = call.extractSearch(context)
                if (query.forbidden) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                    return@get
                }
                if (query.empty) {
                    call.respond(GuestListPageResponse(emptyList(), total = 0, page = query.page, size = query.size))
                    return@get
                }
                val result =
                    repository.searchEntries(
                        query.filter!!,
                        page = query.page,
                        size = query.size,
                    )
                val response =
                    GuestListPageResponse(
                        items = result.items.map { it.toResponse() },
                        total = result.total,
                        page = query.page,
                        size = query.size,
                    )
                call.respond(response)
            }

            get("/api/guest-lists/export") {
                val context = call.rbacContext()
                val query = call.extractSearch(context)
                if (query.forbidden) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                    return@get
                }
                val items =
                    if (query.empty) {
                        emptyList()
                    } else {
                        repository.searchEntries(query.filter!!, page = query.page, size = query.size).items
                    }
                val csv = items.toExportCsv()
                call.respondText(csv, ContentType.Text.CSV)
            }

            post("/api/guest-lists/{listId}/import") {
                val listIdParam = call.parameters.getOrFail("listId")
                val listId = listIdParam.toLongOrNull() ?: throw BadRequestException("Invalid listId")
                val list = repository.getList(listId) ?: throw BadRequestException("List not found")
                val context = call.rbacContext()
                if (!context.canAccess(list)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                    return@post
                }
                val dryRun = call.request.queryParameters["dry_run"].toBooleanStrictOrNull() ?: false
                val type = call.request.contentType()
                if (!type.match(ContentType.Text.CSV) && type != TSV_CONTENT_TYPE) {
                    throw BadRequestException("Expected text/csv body")
                }
                val payload = call.receiveText()
                if (payload.isBlank()) {
                    throw BadRequestException("Empty body")
                }
                val report =
                    runCatching {
                        performGuestListImport(
                            repository = repository,
                            parser = parser,
                            listId = listId,
                            input = payload.byteInputStream(StandardCharsets.UTF_8),
                            dryRun = dryRun,
                        )
                    }.getOrElse { ex ->
                        throw BadRequestException(ex.message ?: "Import failed")
                    }
                val wantsCsv = call.wantsCsv()
                if (wantsCsv) {
                    call.respondText(report.toCsv(), ContentType.Text.CSV)
                } else {
                    call.respond(report.toResponse())
                }
            }
        }
    }
}

internal data class GuestListImportReport(val accepted: Int, val rejected: List<RejectedRow>)

internal suspend fun performGuestListImport(
    repository: GuestListRepository,
    parser: GuestListCsvParser,
    listId: Long,
    input: InputStream,
    dryRun: Boolean,
): GuestListImportReport {
    return input.use { stream ->
        val parsed = parser.parse(stream)
        val importResult = repository.bulkImport(listId, parsed.rows, dryRun)
        val rejected =
            if (parsed.rejected.isEmpty()) {
                importResult.rejected
            } else {
                parsed.rejected + importResult.rejected
            }
        GuestListImportReport(importResult.acceptedCount, rejected)
    }
}

internal fun GuestListImportReport.toSummary(dryRun: Boolean): String {
    val prefix = if (dryRun) {
        "Dry run: $accepted rows would be imported"
    } else {
        "Imported $accepted rows"
    }
    return if (rejected.isEmpty()) {
        "$prefix. No errors."
    } else {
        "$prefix. Rejected ${rejected.size} rows."
    }
}

internal fun GuestListImportReport.toCsv(): String {
    val builder = StringBuilder()
    builder.appendLine("accepted_count,rejected_count")
    builder.appendLine("$accepted,${rejected.size}")
    if (rejected.isNotEmpty()) {
        builder.appendLine("line,reason")
        rejected.forEach { row ->
            val reason = row.reason.replace("\"", "\"\"")
            builder.appendLine("${row.line},\"$reason\"")
        }
    }
    return builder.toString()
}

@Serializable
private data class GuestListEntryResponse(
    val id: Long,
    val listId: Long,
    val listTitle: String,
    val clubId: Long,
    val ownerType: String,
    val ownerUserId: Long,
    val fullName: String,
    val phone: String?,
    val guestsCount: Int,
    val notes: String?,
    val status: String,
    val listCreatedAt: String,
)

@Serializable
private data class GuestListPageResponse(
    val items: List<GuestListEntryResponse>,
    val total: Long,
    val page: Int,
    val size: Int,
)

@Serializable
private data class ImportReportResponse(val accepted: Int, val rejected: List<RejectedRowResponse>)

@Serializable
private data class RejectedRowResponse(val line: Int, val reason: String)

private data class SearchContext(
    val filter: GuestListEntrySearch?,
    val page: Int,
    val size: Int,
    val empty: Boolean,
    val forbidden: Boolean,
)

private fun ApplicationCall.extractSearch(context: RbacContext): SearchContext {
    val params = request.queryParameters
    val page = params.get("page")?.toIntOrNull()?.let { if (it >= 0) it else null } ?: 0
    val size = params.get("size")?.toIntOrNull()?.let { if (it > 0) it else null } ?: 50
    val name = params.get("name")?.takeIf { it.isNotBlank() }
    val phone = params.get("phone")?.takeIf { it.isNotBlank() }
    val status =
        params.get("status")?.let {
            runCatching { GuestListEntryStatus.valueOf(it.uppercase()) }
                .getOrElse { throw BadRequestException("Invalid status") }
        }
    val clubParam = params.get("club")?.toLongOrNull()
    val from = parseInstant(params.get("from"))
    val to = parseInstant(params.get("to"))

    val baseFilter =
        GuestListEntrySearch(
            nameQuery = name,
            phoneQuery = phone,
            status = status,
            createdFrom = from,
            createdTo = to,
        )
    val globalRoles = setOf(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER)
    val hasGlobal = context.roles.any { it in globalRoles }
    var forbidden = false
    val clubIds: Set<Long>? =
        when {
            hasGlobal -> clubParam?.let { setOf(it) }
            Role.PROMOTER in context.roles -> clubParam?.let { setOf(it) }
            else -> {
                val allowed = context.clubIds
                if (clubParam != null && clubParam !in allowed) {
                    forbidden = true
                    emptySet()
                } else if (clubParam != null) {
                    setOf(clubParam)
                } else {
                    allowed
                }
            }
        }
    val ownerId = if (Role.PROMOTER in context.roles) context.user.id else null
    val empty = clubIds?.isEmpty() == true
    val filter =
        if (empty || forbidden) {
            null
        } else {
            baseFilter.copy(
                clubIds = clubIds?.takeIf { it.isNotEmpty() },
                ownerUserId = ownerId,
            )
        }
    return SearchContext(filter, page, size, empty, forbidden)
}

private fun parseInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value) }
        .getOrElse {
            val date = runCatching { LocalDate.parse(value) }.getOrElse { throw BadRequestException("Invalid date") }
            date.atStartOfDay().toInstant(ZoneOffset.UTC)
        }
}

private fun GuestListEntryView.toResponse(): GuestListEntryResponse {
    return GuestListEntryResponse(
        id = id,
        listId = listId,
        listTitle = listTitle,
        clubId = clubId,
        ownerType = ownerType.name,
        ownerUserId = ownerUserId,
        fullName = fullName,
        phone = phone,
        guestsCount = guestsCount,
        notes = notes,
        status = status.name,
        listCreatedAt = listCreatedAt.toString(),
    )
}

private fun GuestListImportReport.toResponse(): ImportReportResponse {
    return ImportReportResponse(
        accepted = accepted,
        rejected = rejected.map { RejectedRowResponse(it.line, it.reason) },
    )
}

private fun List<GuestListEntryView>.toExportCsv(): String {
    val builder = StringBuilder()
    builder.appendLine("entry_id,list_id,club_id,list_title,owner_type,owner_user_id,full_name,phone,guests_count,status,notes,list_created_at")
    for (item in this) {
        builder.append(item.id)
        builder.append(',')
        builder.append(item.listId)
        builder.append(',')
        builder.append(item.clubId)
        builder.append(',')
        builder.append(escapeCsv(item.listTitle))
        builder.append(',')
        builder.append(item.ownerType.name)
        builder.append(',')
        builder.append(item.ownerUserId)
        builder.append(',')
        builder.append(escapeCsv(item.fullName))
        builder.append(',')
        builder.append(item.phone ?: "")
        builder.append(',')
        builder.append(item.guestsCount)
        builder.append(',')
        builder.append(item.status.name)
        builder.append(',')
        builder.append(escapeCsv(item.notes))
        builder.append(',')
        builder.append(item.listCreatedAt.toString())
        builder.append('\n')
    }
    return builder.toString()
}

private fun escapeCsv(value: String?): String {
    if (value.isNullOrEmpty()) return ""
    val escaped = value.replace("\"", "\"\"")
    return "\"$escaped\""
}

private fun com.example.bot.security.rbac.RbacContext.canAccess(list: GuestList): Boolean {
    val globalRoles = setOf(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER)
    if (roles.any { it in globalRoles }) {
        return true
    }
    return when {
        Role.PROMOTER in roles -> list.ownerType == GuestListOwnerType.PROMOTER && list.ownerUserId == user.id
        else -> list.clubId in clubIds
    }
}

private fun ApplicationCall.wantsCsv(): Boolean {
    if (request.queryParameters["format"]?.equals("csv", ignoreCase = true) == true) {
        return true
    }
    return request.acceptItems().any { header -> header.value.equals(ContentType.Text.CSV.toString(), ignoreCase = true) }
}

private fun String?.toBooleanStrictOrNull(): Boolean? {
    return this?.let {
        when {
            it.equals("true", ignoreCase = true) -> true
            it.equals("false", ignoreCase = true) -> false
            else -> null
        }
    }
}

private val TSV_CONTENT_TYPE: ContentType = ContentType.parse("text/tab-separated-values")
