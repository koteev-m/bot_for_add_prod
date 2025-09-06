package com.example.bot.security.rbac

/**
 * Repository capable of loading role assignments for a user.
 */
interface RoleRepository {
    /**
     * Loads all role assignments for the given user identifier.
     */
    suspend fun findRoles(userId: Long): Set<RoleAssignment>
}
