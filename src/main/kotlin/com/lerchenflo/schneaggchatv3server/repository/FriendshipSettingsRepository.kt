package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipSettings
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface FriendshipSettingsRepository : MongoRepository<FriendshipSettings, ObjectId> {
}