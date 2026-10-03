package com.lerchenflo.schneaggchatv3server.authentication

import com.lerchenflo.schneaggchatv3server.authentication.model.SessionResponse
import com.lerchenflo.schneaggchatv3server.core.security.requireAuth
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.*

/**
 * The user's logged-in devices, for the device list in the app settings. Deliberately not under
 * the auth path: that one is permitAll, so an expired access token would get a 403 from
 * requireAuth() instead of the 401 that makes the client refresh and retry.
 */
@RestController
@RequestMapping("/sessions")
class SessionController(
    private val authService: AuthService,
) {

    @GetMapping
    fun getSessions(): List<SessionResponse> {
        val requestingUserId = requireAuth()

        return authService.getSessions(requestingUserId)
    }

    @DeleteMapping("/{sessionId}")
    fun endSession(
        @PathVariable("sessionId") sessionId: String,
    ) {
        val requestingUserId = requireAuth()
        require(ValidationUtils.validateObjectId(sessionId)) { "Invalid session id" }

        authService.endSession(requestingUserId, ObjectId(sessionId))
    }
}
