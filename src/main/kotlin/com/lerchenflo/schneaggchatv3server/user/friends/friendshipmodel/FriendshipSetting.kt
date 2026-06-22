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

@TypeAlias("friendshipsetting")
@Document("friendship_settings")
@CompoundIndex(name = "friendshipId_userId_unique_idx", def = "{'friendshipId': 1, 'userId': 1}", unique = true)
data class FriendshipSetting(
    @Id val id: ObjectId = ObjectId.get(),

    @Indexed val friendshipId: ObjectId, // Which friendship does this setting belong to

    @Indexed val userId: ObjectId, // Which user of the friendship does this setting belong to


    var shareLocation: Boolean = false, //Does this user share this property with the other user
    var shareLastSeen: Boolean = false, //Does this user share this property with the other user
    var nickName: String? = null, //Nickname from this user for the other user
    var muted: Boolean = false, //Did this user mute the other


    var createdAt: Instant = Clock.System.now(),
    var updatedAt: Instant = Clock.System.now()
)