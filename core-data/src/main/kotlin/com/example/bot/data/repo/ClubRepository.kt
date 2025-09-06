package com.example.bot.data.repo

/**
 * Repository for reading clubs accessible to guests.
 */
interface ClubRepository {
    /**
     * Lists available clubs limited by [limit].
     */
    suspend fun listClubs(limit: Int = 10): List<Club>
}

/**
 * Simple projection of a club used in selection lists.
 */
data class Club(
    val id: Long,
    val name: String,
)
