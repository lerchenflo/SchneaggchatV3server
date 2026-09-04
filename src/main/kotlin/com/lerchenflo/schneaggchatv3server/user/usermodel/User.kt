@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.user.usermodel

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@TypeAlias("user")
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

    // Optional phone number, shared with accepted friends. Null if the user never set one.
    val phoneNumber: String? = null,

    val createdAt: Instant,
    val updatedAt: Instant,

    val profilePicUpdatedAt: Instant = updatedAt,  // New field with default

    val lastSeen: Instant = updatedAt,  // Last time the user's final WebSocket session disconnected

    // Master switch for the wake feature. Opt-in: nobody can wake this user until they turn it on
    // AND enable the specific friend via FriendshipSetting.allowWake.
    val allowWakeGlobal: Boolean = false,

    // Per-user app settings (theme, language, pinned chats, ...), synced across all of this
    // user's devices. See PersonalUserSettings.
    val settings: PersonalUserSettings = PersonalUserSettings(),

    // Admin-panel access. Never auto-granted (usernames are mutable via /users/changeusername, so
    // any username-keyed allowlist would silently break) - set by hand directly in MongoDB. Never
    // expose this on any UserResponse variant.
    val role: UserRole = UserRole.USER,
)