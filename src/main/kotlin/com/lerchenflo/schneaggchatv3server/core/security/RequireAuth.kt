package com.lerchenflo.schneaggchatv3server.core.security

import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException

fun requireAuth(): ObjectId {
    val id = SecurityContextHolder.getContext().authentication?.principal as? String
        ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not logged in")
    return ObjectId(id)
}