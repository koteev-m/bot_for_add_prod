package com.example.bot.data.notifications

import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime

data class NotifySegment(
    val id: Long,
    val title: String,
    val definition: JsonElement,
    val createdBy: Long,
    val createdAt: OffsetDateTime,
)

class NotifySegmentsRepository(private val db: Database) {
    suspend fun insert(title: String, definition: JsonElement, createdBy: Long): Long =
        newSuspendedTransaction(db = db) {
            NotifySegments.insert {
                it[NotifySegments.title] = title
                it[NotifySegments.definition] = definition
                it[NotifySegments.createdBy] = createdBy
            }[NotifySegments.id]
        }

    suspend fun find(id: Long): NotifySegment? = newSuspendedTransaction(db = db) {
        NotifySegments.select { NotifySegments.id eq id }
            .map { toSegment(it) }
            .singleOrNull()
    }

    private fun toSegment(row: ResultRow): NotifySegment = NotifySegment(
        id = row[NotifySegments.id],
        title = row[NotifySegments.title],
        definition = row[NotifySegments.definition],
        createdBy = row[NotifySegments.createdBy],
        createdAt = row[NotifySegments.createdAt],
    )
}

data class NotifyCampaign(
    val id: Long,
    val title: String,
    val status: String,
    val kind: String,
    val clubId: Long?,
    val messageThreadId: Int?,
    val segmentId: Long?,
    val scheduleCron: String?,
    val startsAt: OffsetDateTime?,
    val createdBy: Long,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

class NotifyCampaignsRepository(private val db: Database) {
    suspend fun insert(campaign: NotifyCampaign): Long = newSuspendedTransaction(db = db) {
        NotifyCampaigns.insert {
            it[NotifyCampaigns.title] = campaign.title
            it[NotifyCampaigns.status] = campaign.status
            it[NotifyCampaigns.kind] = campaign.kind
            it[NotifyCampaigns.clubId] = campaign.clubId
            it[NotifyCampaigns.messageThreadId] = campaign.messageThreadId
            it[NotifyCampaigns.segmentId] = campaign.segmentId
            it[NotifyCampaigns.scheduleCron] = campaign.scheduleCron
            it[NotifyCampaigns.startsAt] = campaign.startsAt
            it[NotifyCampaigns.createdBy] = campaign.createdBy
        }[NotifyCampaigns.id]
    }

    suspend fun find(id: Long): NotifyCampaign? = newSuspendedTransaction(db = db) {
        NotifyCampaigns.select { NotifyCampaigns.id eq id }
            .map { toCampaign(it) }
            .singleOrNull()
    }

    private fun toCampaign(row: ResultRow): NotifyCampaign = NotifyCampaign(
        id = row[NotifyCampaigns.id],
        title = row[NotifyCampaigns.title],
        status = row[NotifyCampaigns.status],
        kind = row[NotifyCampaigns.kind],
        clubId = row[NotifyCampaigns.clubId],
        messageThreadId = row[NotifyCampaigns.messageThreadId],
        segmentId = row[NotifyCampaigns.segmentId],
        scheduleCron = row[NotifyCampaigns.scheduleCron],
        startsAt = row[NotifyCampaigns.startsAt],
        createdBy = row[NotifyCampaigns.createdBy],
        createdAt = row[NotifyCampaigns.createdAt],
        updatedAt = row[NotifyCampaigns.updatedAt],
    )
}

data class UserSubscription(
    val userId: Long,
    val clubId: Long?,
    val topic: String,
    val optIn: Boolean,
    val lang: String,
)

class UserSubscriptionsRepository(private val db: Database) {
    suspend fun insert(sub: UserSubscription) = newSuspendedTransaction(db = db) {
        UserSubscriptions.insert {
            it[UserSubscriptions.userId] = sub.userId
            it[UserSubscriptions.clubId] = sub.clubId
            it[UserSubscriptions.topic] = sub.topic
            it[UserSubscriptions.optIn] = sub.optIn
            it[UserSubscriptions.lang] = sub.lang
        }
    }

    suspend fun find(userId: Long, clubId: Long?, topic: String): UserSubscription? = newSuspendedTransaction(db = db) {
        UserSubscriptions
            .select {
                (UserSubscriptions.userId eq userId) and
                    (UserSubscriptions.topic eq topic) and
                    (clubId?.let { UserSubscriptions.clubId eq it } ?: UserSubscriptions.clubId.isNull())
            }
            .map { toSubscription(it) }
            .singleOrNull()
    }

    private fun toSubscription(row: ResultRow): UserSubscription = UserSubscription(
        userId = row[UserSubscriptions.userId],
        clubId = row[UserSubscriptions.clubId],
        topic = row[UserSubscriptions.topic],
        optIn = row[UserSubscriptions.optIn],
        lang = row[UserSubscriptions.lang],
    )
}

data class OutboxRecord(
    val id: Long,
    val recipientType: String,
    val recipientId: Long,
    val dedupKey: String?,
    val priority: Int,
    val campaignId: Long?,
    val method: String,
    val payload: JsonElement,
    val createdAt: OffsetDateTime,
)

class NotificationsOutboxRepository(private val db: Database) {
    suspend fun insert(record: OutboxRecord) = newSuspendedTransaction(db = db) {
        NotificationsOutbox.insert {
            it[NotificationsOutbox.recipientType] = record.recipientType
            it[NotificationsOutbox.recipientId] = record.recipientId
            it[NotificationsOutbox.dedupKey] = record.dedupKey
            it[NotificationsOutbox.priority] = record.priority
            it[NotificationsOutbox.campaignId] = record.campaignId
            it[NotificationsOutbox.method] = record.method
            it[NotificationsOutbox.payload] = record.payload
        }
    }

    suspend fun find(id: Long): OutboxRecord? = newSuspendedTransaction(db = db) {
        NotificationsOutbox.select { NotificationsOutbox.id eq id }
            .map { toOutbox(it) }
            .singleOrNull()
    }

    private fun toOutbox(row: ResultRow): OutboxRecord = OutboxRecord(
        id = row[NotificationsOutbox.id],
        recipientType = row[NotificationsOutbox.recipientType],
        recipientId = row[NotificationsOutbox.recipientId],
        dedupKey = row[NotificationsOutbox.dedupKey],
        priority = row[NotificationsOutbox.priority],
        campaignId = row[NotificationsOutbox.campaignId],
        method = row[NotificationsOutbox.method],
        payload = row[NotificationsOutbox.payload],
        createdAt = row[NotificationsOutbox.createdAt],
    )
}
