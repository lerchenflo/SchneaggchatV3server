@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class FriendshipStatus { PENDING, ACCEPTED, DECLINED, BLOCKED }

@TypeAlias("friendship")
@Document("friendships")
@CompoundIndex(name = "userId1_userId2_unique_idx", def = "{'userId1': 1, 'userId2': 1}", unique = true)
data class Friendship(
    //Each friendship has an ID as PK
    @Id val id: ObjectId = ObjectId(),

    //For each friendship there are two users, indexed for faster search
    @Indexed val userId1: ObjectId, //Min value (First user)
    @Indexed val userId2: ObjectId, //Max value (Bigger userid)

    // who initiated the latest action
    val requesterId: ObjectId,
    //Status after the last change
    var status: FriendshipStatus = FriendshipStatus.PENDING,

    var createdAt: Instant = Clock.System.now(),
    var updatedAt: Instant = Clock.System.now()
)
