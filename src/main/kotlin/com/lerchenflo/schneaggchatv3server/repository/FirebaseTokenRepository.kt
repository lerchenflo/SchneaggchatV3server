package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.FirebaseToken
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface FirebaseTokenRepository : MongoRepository<FirebaseToken, ObjectId> {

    fun findAllByUserId(userId: ObjectId): List<FirebaseToken>

    fun findAllByUserIdIn(userIds: Collection<ObjectId>): List<FirebaseToken>

    fun deleteByToken(token: String)

    fun deleteByTokenIn(tokens: Collection<String>)

    fun deleteByTokenAndUserIdNot(token: String, userId: ObjectId)

    fun findByUserIdAndToken(userId: ObjectId, token: String): FirebaseToken?
}