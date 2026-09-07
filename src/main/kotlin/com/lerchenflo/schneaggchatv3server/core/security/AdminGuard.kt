package com.lerchenflo.schneaggchatv3server.core.security

import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.usermodel.UserRole
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Gate for the admin panel. `requireAuth()` only proves the caller is logged in; this additionally
 * loads the user and checks their role. Reading the role from the DB (rather than a JWT claim)
 * means revoking admin access takes effect on the caller's very next request, not after their
 * access token expires.
 *
 * Returns 404, not 403, for a non-admin - a 403 would confirm the admin surface exists at all.
 */
@Component
class AdminGuard(
    private val userLookupService: UserLookupService,
) {
    fun requireAdmin(): ObjectId {
        val userId = requireAuth()
        val user = userLookupService.findById(userId)
        if (user?.role != UserRole.ADMIN) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return userId
    }
}
