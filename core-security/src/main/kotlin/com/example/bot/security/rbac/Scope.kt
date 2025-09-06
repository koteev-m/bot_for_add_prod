package com.example.bot.security.rbac

/**
 * Scope of a role assignment.
 */
sealed class Scope {
    /** Global scope grants rights across all clubs. */
    data object Global : Scope()

    /** Club scope restricts rights to a particular club. */
    data class Club(val clubId: Long) : Scope()
}
