package com.example.bot.data.repo

import com.example.bot.data.db.Clubs
import com.example.bot.data.db.HqNotify
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

data class HqEndpoints(
    val chatId: Long,
    val generalTopicId: Int?,
    val bookingsTopicId: Int?,
    val listsTopicId: Int?,
    val qaTopicId: Int?,
    val systemTopicId: Int?,
)

data class ClubNotify(
    val id: Int,
    val name: String,
    val adminChatId: Long?,
    val generalTopicId: Int?,
    val bookingsTopicId: Int?,
    val listsTopicId: Int?,
    val qaTopicId: Int?,
    val mediaTopicId: Int?,
    val systemTopicId: Int?,
)

class NotifyEndpointRepository(private val db: Database) {
    suspend fun loadHq(): HqEndpoints? {
        return newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            HqNotify
                .selectAll()
                .limit(1)
                .firstOrNull()
                ?.toHqEndpoints()
        }
    }

    suspend fun listClubs(): List<ClubNotify> {
        return newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            Clubs
                .selectAll()
                .map { it.toClubNotify() }
        }
    }

    private fun ResultRow.toHqEndpoints(): HqEndpoints {
        return HqEndpoints(
            chatId = this[HqNotify.chatId],
            generalTopicId = this[HqNotify.generalTopicId],
            bookingsTopicId = this[HqNotify.bookingsTopicId],
            listsTopicId = this[HqNotify.listsTopicId],
            qaTopicId = this[HqNotify.qaTopicId],
            systemTopicId = this[HqNotify.systemTopicId],
        )
    }

    private fun ResultRow.toClubNotify(): ClubNotify {
        return ClubNotify(
            id = this[Clubs.id].value,
            name = this[Clubs.name],
            adminChatId = this[Clubs.adminChatId],
            generalTopicId = this[Clubs.generalTopicId],
            bookingsTopicId = this[Clubs.bookingsTopicId],
            listsTopicId = this[Clubs.listsTopicId],
            qaTopicId = this[Clubs.qaTopicId],
            mediaTopicId = this[Clubs.mediaTopicId],
            systemTopicId = this[Clubs.systemTopicId],
        )
    }
}
