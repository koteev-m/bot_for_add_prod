package com.example.bot.security.rbac

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory cache for role assignments with a fixed TTL.
 */
class RoleCache(private val repository: RoleRepository, private val ttl: Duration = Duration.ofSeconds(60)) {
    private data class Cached(val roles: Set<RoleAssignment>, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<Long, Cached>()

    /**
     * Returns role assignments for a user, loading from repository if cache entry expired.
     */
    suspend fun rolesFor(userId: Long): Set<RoleAssignment> {
        val now = Instant.now()
        val existing = cache[userId]
        if (existing != null && existing.expiresAt.isAfter(now)) {
            return existing.roles
        }
        val loaded = repository.findRoles(userId)
        cache[userId] = Cached(loaded, now.plus(ttl))
        return loaded
    }

    /**
     * Invalidates cached roles for the given user.
     */
    fun invalidate(userId: Long) {
        cache.remove(userId)
    }
}
