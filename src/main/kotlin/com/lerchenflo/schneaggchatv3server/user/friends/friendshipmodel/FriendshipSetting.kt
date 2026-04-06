@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Document("friendship_settings")
data class FriendshipSetting(
    @Id val id: ObjectId = ObjectId.get(),

    @Indexed val friendshipId: ObjectId, // Which friendship does this setting belong to

    @Indexed val userId: ObjectId, // Which user of the friendship does this setting belong to


    var shareLocation: Boolean = false,
    var shareLastSeen: Boolean = false,
    var nickname: String? = null,
    var muted: Boolean = false,


    var createdAt: Instant = Clock.System.now(),
    var updatedAt: Instant = Clock.System.now()
)