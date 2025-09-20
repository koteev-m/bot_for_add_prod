package com.example.bot.data.club

import com.example.bot.club.BulkImportResult
import com.example.bot.club.GuestList
import com.example.bot.club.GuestListEntry
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListRepository
import com.example.bot.club.GuestListStatus
import com.example.bot.club.ParsedGuest
import com.example.bot.club.RejectedRow
import com.example.bot.data.db.withTxRetry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class GuestListRepositoryImpl(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : GuestListRepository {
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
    ): GuestList {
        return withTxRetry {
            transaction(database) {
                val createdAt = clock.instant().atOffset(ZoneOffset.UTC)
                GuestListsTable
                    .insert {
                        it[GuestListsTable.clubId] = clubId
                        it[GuestListsTable.eventId] = eventId
                        it[GuestListsTable.ownerType] = ownerType.name
                        it[GuestListsTable.ownerUserId] = ownerUserId
                        it[GuestListsTable.title] = title
                        it[GuestListsTable.capacity] = capacity
                        it[GuestListsTable.arrivalWindowStart] = arrivalWindowStart?.atOffset(ZoneOffset.UTC)
                        it[GuestListsTable.arrivalWindowEnd] = arrivalWindowEnd?.atOffset(ZoneOffset.UTC)
                        it[GuestListsTable.status] = status.name
                        it[GuestListsTable.createdAt] = createdAt
                    }
                    .resultedValues!!
                    .single()
                    .toGuestList()
            }
        }
    }

    override suspend fun getList(id: Long): GuestList? {
        return withTxRetry {
            transaction(database) {
                GuestListsTable
                    .select { GuestListsTable.id eq id }
                    .firstOrNull()
                    ?.toGuestList()
            }
        }
    }

    override suspend fun listListsByClub(clubId: Long, page: Int, size: Int): List<GuestList> {
        require(page >= 0) { "page must be non-negative" }
        require(size > 0) { "size must be positive" }
        val offset = page.toLong() * size
        require(offset <= Int.MAX_VALUE) { "page too large" }
        return withTxRetry {
            transaction(database) {
                GuestListsTable
                    .select { GuestListsTable.clubId eq clubId }
                    .orderBy(GuestListsTable.createdAt, SortOrder.DESC)
                    .limit(size, offset)
                    .map { it.toGuestList() }
            }
        }
    }

    override suspend fun addEntry(
        listId: Long,
        fullName: String,
        phone: String?,
        guestsCount: Int,
        notes: String?,
        status: GuestListEntryStatus,
    ): GuestListEntry {
        val validation = validateEntryInput(fullName, phone, guestsCount, notes, status)
        val valid =
            when (validation) {
                is EntryValidationOutcome.Invalid -> throw IllegalArgumentException(validation.reason)
                is EntryValidationOutcome.Valid -> validation
            }
        return withTxRetry {
            transaction(database) {
                val inserted =
                    GuestListEntriesTable
                        .insert {
                            it[GuestListEntriesTable.guestListId] = listId
                            it[GuestListEntriesTable.fullName] = valid.name
                            it[GuestListEntriesTable.phone] = valid.phone
                            it[GuestListEntriesTable.plusOnesAllowed] = valid.guestsCount - MIN_GUESTS_PER_ENTRY
                            it[GuestListEntriesTable.plusOnesUsed] = DEFAULT_PLUS_ONES_USED
                            it[GuestListEntriesTable.category] = DEFAULT_CATEGORY
                            it[GuestListEntriesTable.comment] = valid.notes
                            it[GuestListEntriesTable.status] = valid.status.name
                            if (valid.status == GuestListEntryStatus.CHECKED_IN) {
                                it[GuestListEntriesTable.checkedInAt] = clock.instant().atOffset(ZoneOffset.UTC)
                                it[GuestListEntriesTable.checkedInBy] = null
                            } else {
                                it[GuestListEntriesTable.checkedInAt] = null
                                it[GuestListEntriesTable.checkedInBy] = null
                            }
                        }
                        .resultedValues!!
                        .single()
                inserted.toGuestListEntry()
            }
        }
    }

    override suspend fun setEntryStatus(
        entryId: Long,
        status: GuestListEntryStatus,
        checkedInBy: Long?,
        at: Instant?,
    ): GuestListEntry? {
        return withTxRetry {
            transaction(database) {
                val checkedAt =
                    if (status == GuestListEntryStatus.CHECKED_IN) {
                        (at ?: clock.instant()).atOffset(ZoneOffset.UTC)
                    } else {
                        null
                    }
                val actorId = checkedInBy
                val updated =
                    GuestListEntriesTable.update({ GuestListEntriesTable.id eq entryId }) {
                        it[GuestListEntriesTable.status] = status.name
                        it[checkedInAt] = checkedAt
                        it[GuestListEntriesTable.checkedInBy] = if (checkedAt != null) actorId else null
                    }
                if (updated == 0) {
                    null
                } else {
                    GuestListEntriesTable
                        .select { GuestListEntriesTable.id eq entryId }
                        .single()
                        .toGuestListEntry()
                }
            }
        }
    }

    override suspend fun listEntries(
        listId: Long,
        page: Int,
        size: Int,
        statusFilter: GuestListEntryStatus?,
    ): List<GuestListEntry> {
        require(page >= 0) { "page must be non-negative" }
        require(size > 0) { "size must be positive" }
        val offset = page.toLong() * size
        require(offset <= Int.MAX_VALUE) { "page too large" }
        return withTxRetry {
            transaction(database) {
                val baseQuery = GuestListEntriesTable.select { GuestListEntriesTable.guestListId eq listId }
                val filteredQuery =
                    if (statusFilter != null) {
                        baseQuery.andWhere { GuestListEntriesTable.status eq statusFilter.name }
                    } else {
                        baseQuery
                    }
                filteredQuery
                    .orderBy(GuestListEntriesTable.id, SortOrder.ASC)
                    .limit(size, offset)
                    .map { it.toGuestListEntry() }
            }
        }
    }

    override suspend fun bulkImport(
        listId: Long,
        rows: List<ParsedGuest>,
        dryRun: Boolean,
    ): BulkImportResult {
        return withTxRetry {
            transaction(database) {
                val rejected = mutableListOf<RejectedRow>()
                val validRows = mutableListOf<EntryValidationOutcome.Valid>()
                rows.forEach { row ->
                    val outcome =
                        validateEntryInput(
                            name = row.name,
                            phone = row.phone,
                            guestsCount = row.guestsCount,
                            notes = row.notes,
                            status = GuestListEntryStatus.PLANNED,
                        )
                    when (outcome) {
                        is EntryValidationOutcome.Invalid -> rejected += RejectedRow(row.lineNumber, outcome.reason)
                        is EntryValidationOutcome.Valid -> validRows += outcome
                    }
                }
                if (!dryRun && validRows.isNotEmpty()) {
                    GuestListEntriesTable.batchInsert(validRows) { valid ->
                        this[GuestListEntriesTable.guestListId] = listId
                        this[GuestListEntriesTable.fullName] = valid.name
                        this[GuestListEntriesTable.phone] = valid.phone
                        this[GuestListEntriesTable.plusOnesAllowed] = valid.guestsCount - MIN_GUESTS_PER_ENTRY
                        this[GuestListEntriesTable.plusOnesUsed] = DEFAULT_PLUS_ONES_USED
                        this[GuestListEntriesTable.category] = DEFAULT_CATEGORY
                        this[GuestListEntriesTable.comment] = valid.notes
                        this[GuestListEntriesTable.status] = GuestListEntryStatus.PLANNED.name
                        this[GuestListEntriesTable.checkedInAt] = null
                        this[GuestListEntriesTable.checkedInBy] = null
                    }
                }
                BulkImportResult(validRows.size, rejected.toList())
            }
        }
    }

    private fun ResultRow.toGuestList(): GuestList {
        return GuestList(
            id = this[GuestListsTable.id],
            clubId = this[GuestListsTable.clubId],
            eventId = this[GuestListsTable.eventId],
            ownerType = GuestListOwnerType.valueOf(this[GuestListsTable.ownerType]),
            ownerUserId = this[GuestListsTable.ownerUserId],
            title = this[GuestListsTable.title],
            capacity = this[GuestListsTable.capacity],
            arrivalWindowStart = this[GuestListsTable.arrivalWindowStart]?.toInstant(),
            arrivalWindowEnd = this[GuestListsTable.arrivalWindowEnd]?.toInstant(),
            status = GuestListStatus.valueOf(this[GuestListsTable.status]),
            createdAt = this[GuestListsTable.createdAt].toInstant(),
        )
    }

    private fun ResultRow.toGuestListEntry(): GuestListEntry {
        return GuestListEntry(
            id = this[GuestListEntriesTable.id],
            listId = this[GuestListEntriesTable.guestListId],
            fullName = this[GuestListEntriesTable.fullName],
            phone = this[GuestListEntriesTable.phone],
            guestsCount = this[GuestListEntriesTable.plusOnesAllowed] + MIN_GUESTS_PER_ENTRY,
            notes = this[GuestListEntriesTable.comment],
            status = GuestListEntryStatus.valueOf(this[GuestListEntriesTable.status]),
            checkedInAt = this[GuestListEntriesTable.checkedInAt]?.toInstant(),
            checkedInBy = this[GuestListEntriesTable.checkedInBy],
        )
    }
}
