@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.group.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@TypeAlias("group")
@Document("groups")
data class Group(
    @Id val id: ObjectId = ObjectId.get(),
    val name: String,
    val description: String,

    val updatedAt: Instant,
    val profilePicUpdatedAt: Instant = updatedAt,

    val expiresAt: Instant? = null,
    val deleted: Boolean = false,

    /**
     * True: every member may add their own friends (in an event's group that only invites them to
     * the event). False: only admins add. Default keeps groups stored before this field as admin-only.
     */
    val membersCanAdd: Boolean = false,

    val createdAt: Instant,
    val creatorId: ObjectId

)