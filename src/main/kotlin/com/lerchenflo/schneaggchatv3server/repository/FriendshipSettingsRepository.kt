package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipSetting
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface FriendshipSettingsRepository : MongoRepository<FriendshipSetting, ObjectId> {

    fun findByFriendshipId(friendshipId: ObjectId): FriendshipSetting?
}