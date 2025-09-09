package com.example.bot.music

/** Repository for music items. */
interface MusicItemRepository {
    suspend fun create(req: MusicItemCreate, actor: UserId): MusicItemView

    suspend fun listActive(
        clubId: Long?,
        limit: Int,
        offset: Int,
        tag: String?,
        q: String?,
    ): List<MusicItemView>
}

/** Repository for music playlists. */
interface MusicPlaylistRepository {
    suspend fun create(req: PlaylistCreate, actor: UserId): PlaylistView

    suspend fun setItems(playlistId: Long, itemIds: List<Long>)

    suspend fun getFull(id: Long): PlaylistFullView?
}
