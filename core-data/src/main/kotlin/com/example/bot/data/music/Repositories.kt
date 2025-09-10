package com.example.bot.data.music

import com.example.bot.music.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.ZoneOffset

/** Exposed implementation of [MusicItemRepository] and [MusicPlaylistRepository]. */
class MusicItemRepositoryImpl(private val db: Database) : MusicItemRepository {
    override suspend fun create(req: MusicItemCreate, actor: UserId): MusicItemView {
        return newSuspendedTransaction(Dispatchers.IO, db) {
            val row =
                MusicItemsTable
                    .insert {
                        it[clubId] = req.clubId
                        it[title] = req.title
                        it[dj] = req.dj
                        it[sourceType] = req.source.name
                        it[sourceUrl] = req.sourceUrl
                        it[durationSec] = req.durationSec
                        it[coverUrl] = req.coverUrl
                        it[tags] = req.tags?.joinToString(",")
                        it[publishedAt] = req.publishedAt?.atOffset(ZoneOffset.UTC)
                        it[createdBy] = actor
                    }.resultedValues!!
                    .first()
            row.toView()
        }
    }

    override suspend fun listActive(
        clubId: Long?,
        limit: Int,
        offset: Int,
        tag: String?,
        q: String?,
    ): List<MusicItemView> {
        return newSuspendedTransaction(Dispatchers.IO, db) {
            var cond: Op<Boolean> = MusicItemsTable.isActive eq true
            if (clubId != null) cond = cond and (MusicItemsTable.clubId eq clubId)
            // tag filtering omitted for simplicity
            if (!q.isNullOrBlank()) {
                val like = "%$q%"
                cond = cond and ((MusicItemsTable.title like like) or (MusicItemsTable.dj like like))
            }
            MusicItemsTable
                .select { cond }
                .orderBy(MusicItemsTable.publishedAt, SortOrder.DESC)
                .limit(limit, offset.toLong())
                .map { it.toView() }
        }
    }

    private fun ResultRow.toView(): MusicItemView {
        return MusicItemView(
            id = this[MusicItemsTable.id],
            clubId = this[MusicItemsTable.clubId],
            title = this[MusicItemsTable.title],
            dj = this[MusicItemsTable.dj],
            source = MusicSource.valueOf(this[MusicItemsTable.sourceType]),
            sourceUrl = this[MusicItemsTable.sourceUrl],
            telegramFileId = this[MusicItemsTable.telegramFileId],
            durationSec = this[MusicItemsTable.durationSec],
            coverUrl = this[MusicItemsTable.coverUrl],
            tags = this[MusicItemsTable.tags]?.split(",")?.filter { it.isNotBlank() },
            publishedAt = this[MusicItemsTable.publishedAt]?.toInstant(),
        )
    }
}

class MusicPlaylistRepositoryImpl(private val db: Database) : MusicPlaylistRepository {
    override suspend fun create(req: PlaylistCreate, actor: UserId): PlaylistView {
        return newSuspendedTransaction(Dispatchers.IO, db) {
            val row =
                MusicPlaylistsTable
                    .insert {
                        it[clubId] = req.clubId
                        it[title] = req.title
                        it[description] = req.description
                        it[coverUrl] = req.coverUrl
                        it[createdBy] = actor
                    }.resultedValues!!
                    .first()
            row.toView()
        }
    }

    override suspend fun setItems(playlistId: Long, itemIds: List<Long>) {
        return newSuspendedTransaction(Dispatchers.IO, db) {
            MusicPlaylistItemsTable.deleteWhere { MusicPlaylistItemsTable.playlistId eq playlistId }
            itemIds.forEachIndexed { idx, itemId ->
                MusicPlaylistItemsTable.insert {
                    it[this.playlistId] = playlistId
                    it[this.itemId] = itemId
                    it[position] = idx
                }
            }
        }
    }

    override suspend fun getFull(id: Long): PlaylistFullView? {
        return newSuspendedTransaction(Dispatchers.IO, db) {
            val p =
                MusicPlaylistsTable
                    .select { MusicPlaylistsTable.id eq id }
                    .firstOrNull()
                    ?.toView() ?: return@newSuspendedTransaction null
            val items =
                MusicItemsTable
                    .innerJoin(MusicPlaylistItemsTable)
                    .select { MusicPlaylistItemsTable.playlistId eq id }
                    .orderBy(MusicPlaylistItemsTable.position)
                    .map { it.toItemView() }
            PlaylistFullView(p.id, p.clubId, p.title, p.description, p.coverUrl, items)
        }
    }

    private fun ResultRow.toView(): PlaylistView {
        return PlaylistView(
            id = this[MusicPlaylistsTable.id],
            clubId = this[MusicPlaylistsTable.clubId],
            title = this[MusicPlaylistsTable.title],
            description = this[MusicPlaylistsTable.description],
            coverUrl = this[MusicPlaylistsTable.coverUrl],
        )
    }

    private fun ResultRow.toItemView(): MusicItemView {
        return MusicItemView(
            id = this[MusicItemsTable.id],
            clubId = this[MusicItemsTable.clubId],
            title = this[MusicItemsTable.title],
            dj = this[MusicItemsTable.dj],
            source = MusicSource.valueOf(this[MusicItemsTable.sourceType]),
            sourceUrl = this[MusicItemsTable.sourceUrl],
            telegramFileId = this[MusicItemsTable.telegramFileId],
            durationSec = this[MusicItemsTable.durationSec],
            coverUrl = this[MusicItemsTable.coverUrl],
            tags = this[MusicItemsTable.tags]?.split(",")?.filter { it.isNotBlank() },
            publishedAt = this[MusicItemsTable.publishedAt]?.toInstant(),
        )
    }
}
