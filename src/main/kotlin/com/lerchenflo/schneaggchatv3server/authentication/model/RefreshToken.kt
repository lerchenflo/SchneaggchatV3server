@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.authentication.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@TypeAlias("refreshtoken")
@Document("refreshTokens")
@CompoundIndex(
    name = "active_user_token",
    def = "{'userId': 1, 'hashedToken': 1}",
    unique = true,
    partialFilter = "{'deletedAt': null}"  // Only enforce uniqueness on active tokens
)

data class RefreshToken(
    @Id val id: ObjectId = ObjectId.get(),
    val userId: ObjectId,
    //@Indexed(unique = true)
    val hashedToken: String,

    val rawToken: String? = null,

    @Indexed(expireAfter = "0s")
    val expiresAt: Instant,
    val createdAt: Instant = Clock.System.now(),

    val deletedAt: Instant? = null,
    val replacedByToken: ObjectId? = null
)
