@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.user.usermodel

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Document("users")
data class User(
    @Id val id: ObjectId = ObjectId.get(),

    @Indexed(unique = true)
    val username: String,
    val hashedPassword: String,

    @Indexed
    val email: String,
    val emailVerifiedAt: Instant? = null,

    val userDescription: String,
    val userStatus: String,
    val birthDate: String,

    val createdAt: Instant,
    val updatedAt: Instant,

    val profilePicUpdatedAt: Instant = updatedAt,  // New field with default

    val locationShared: Boolean = false // Global master switch - does this user share their location at all
)