package com.lerchenflo.schneaggchatv3server.core.security

import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException

fun requireAuth(): ObjectId =
    optionalAuth() ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not logged in")

/**
 * The authenticated user, or null when the request carries no valid access token. Only for
 * endpoints that must still work for a client whose access token already expired (logout).
 */
fun optionalAuth(): ObjectId? =
    (SecurityContextHolder.getContext().authentication?.principal as? String)?.let { ObjectId(it) }
