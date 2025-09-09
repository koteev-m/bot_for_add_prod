package com.example.bot.security.rbac

/**
 * Represents a role assigned to a user with a particular scope.
 *
 * @property code the role code
 * @property scope scope of the role
 */
data class RoleAssignment(val code: RoleCode, val scope: Scope)
