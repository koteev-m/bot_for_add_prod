package com.example.bot.club

import java.time.Instant

/**
 * Repository managing guest lists and their entries.
 */
interface GuestListRepository {
    @Suppress("LongParameterList")
    suspend fun createList(
        clubId: Long,
        eventId: Long,
        ownerType: GuestListOwnerType,
        ownerUserId: Long,
        title: String,
        capacity: Int,
        arrivalWindowStart: Instant?,
        arrivalWindowEnd: Instant?,
        status: GuestListStatus = GuestListStatus.ACTIVE,
    ): GuestList

    suspend fun getList(id: Long): GuestList?

    suspend fun listListsByClub(clubId: Long, page: Int, size: Int): List<GuestList>

    suspend fun addEntry(
        listId: Long,
        fullName: String,
        phone: String?,
        guestsCount: Int,
        notes: String?,
        status: GuestListEntryStatus = GuestListEntryStatus.PLANNED,
    ): GuestListEntry

    suspend fun setEntryStatus(
        entryId: Long,
        status: GuestListEntryStatus,
        checkedInBy: Long? = null,
        at: Instant? = null,
    ): GuestListEntry?

    suspend fun listEntries(
        listId: Long,
        page: Int,
        size: Int,
        statusFilter: GuestListEntryStatus? = null,
    ): List<GuestListEntry>

    suspend fun bulkImport(listId: Long, rows: List<ParsedGuest>, dryRun: Boolean): BulkImportResult
}

enum class GuestListOwnerType {
    PROMOTER,
    ADMIN,
    MANAGER,
}

enum class GuestListStatus {
    ACTIVE,
    CLOSED,
}

enum class GuestListEntryStatus {
    PLANNED,
    CHECKED_IN,
    NO_SHOW,
}

data class GuestList(
    val id: Long,
    val clubId: Long,
    val eventId: Long,
    val ownerType: GuestListOwnerType,
    val ownerUserId: Long,
    val title: String,
    val capacity: Int,
    val arrivalWindowStart: Instant?,
    val arrivalWindowEnd: Instant?,
    val status: GuestListStatus,
    val createdAt: Instant,
)

data class GuestListEntry(
    val id: Long,
    val listId: Long,
    val fullName: String,
    val phone: String?,
    val guestsCount: Int,
    val notes: String?,
    val status: GuestListEntryStatus,
    val checkedInAt: Instant?,
    val checkedInBy: Long?,
)

data class ParsedGuest(
    val lineNumber: Int,
    val name: String,
    val phone: String?,
    val guestsCount: Int,
    val notes: String?,
)

data class RejectedRow(val line: Int, val reason: String)

data class BulkImportResult(val acceptedCount: Int, val rejected: List<RejectedRow>)
