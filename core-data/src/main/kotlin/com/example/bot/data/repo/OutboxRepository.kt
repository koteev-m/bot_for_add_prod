package com.example.bot.data.repo

import com.example.bot.data.notifications.NotificationsOutbox
import com.example.bot.notifications.NotifyMessage
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

class OutboxRepository(private val db: Database) {

    data class Record(val id: Long, val message: NotifyMessage, val dedupKey: String?, val attempts: Int)

    private val json = Json

    suspend fun enqueue(msg: NotifyMessage, campaignId: Long? = null, priority: Int = 100, dedupKey: String? = null) {
        return newSuspendedTransaction(db = db) {
            NotificationsOutbox.insertIgnore {
                it[targetChatId] = msg.chatId
                it[messageThreadId] = msg.messageThreadId
                it[kind] = msg.method.name
                it[payload] = json.encodeToJsonElement(NotifyMessage.serializer(), msg)
                it[status] = "PENDING"
                it[nextRetryAt] = OffsetDateTime.now()
                it[recipientType] = "chat"
                it[recipientId] = msg.chatId
                it[NotificationsOutbox.dedupKey] = dedupKey ?: msg.dedupKey
                it[NotificationsOutbox.priority] = priority
                it[NotificationsOutbox.campaignId] = campaignId
                it[method] = msg.method.name
                it[parseMode] = msg.parseMode?.name
            }
        }
    }

    suspend fun pickBatch(now: OffsetDateTime, limit: Int): List<Record> {
        return newSuspendedTransaction(db = db) {
            NotificationsOutbox
                .select {
                    (NotificationsOutbox.status eq "PENDING") and
                        (NotificationsOutbox.nextRetryAt lessEq now)
                }.orderBy(
                    NotificationsOutbox.priority to SortOrder.ASC,
                    NotificationsOutbox.createdAt to SortOrder.ASC,
                ).forUpdate()
                .limit(limit)
                .map {
                    Record(
                        id = it[NotificationsOutbox.id],
                        message =
                        json.decodeFromJsonElement(
                            NotifyMessage.serializer(),
                            it[NotificationsOutbox.payload],
                        ),
                        dedupKey = it[NotificationsOutbox.dedupKey],
                        attempts = it[NotificationsOutbox.attempts],
                    )
                }
        }
    }

    suspend fun markSent(id: Long, messageId: Long?) {
        return newSuspendedTransaction(db = db) {
            NotificationsOutbox.update({ NotificationsOutbox.id eq id }) {
                it[status] = "SENT"
                it[lastError] = null
                it[nextRetryAt] = null
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it[attempts] = attempts + 1
                }
            }
        }
    }

    suspend fun markFailed(id: Long, error: String?, nextRetryAt: OffsetDateTime) {
        return newSuspendedTransaction(db = db) {
            NotificationsOutbox.update({ NotificationsOutbox.id eq id }) {
                it[status] = "PENDING"
                it[lastError] = error
                it[NotificationsOutbox.nextRetryAt] = nextRetryAt
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it[attempts] = attempts + 1
                }
            }
        }
    }

    suspend fun postpone(id: Long, nextRetryAt: OffsetDateTime) {
        return newSuspendedTransaction(db = db) {
            NotificationsOutbox.update({ NotificationsOutbox.id eq id }) {
                it[status] = "PENDING"
                it[lastError] = null
                it[NotificationsOutbox.nextRetryAt] = nextRetryAt
            }
        }
    }

    suspend fun markPermanentFailure(id: Long, error: String?) {
        return newSuspendedTransaction(db = db) {
            NotificationsOutbox.update({ NotificationsOutbox.id eq id }) {
                it[status] = "FAILED"
                it[lastError] = error
                it[NotificationsOutbox.nextRetryAt] = null
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it[attempts] = attempts + 1
                }
            }
        }
    }

    suspend fun isSent(dedupKey: String): Boolean {
        return newSuspendedTransaction(db = db) {
            NotificationsOutbox
                .select {
                    (NotificationsOutbox.dedupKey eq dedupKey) and
                        (NotificationsOutbox.status eq "SENT")
                }.empty()
                .not()
        }
    }
}
