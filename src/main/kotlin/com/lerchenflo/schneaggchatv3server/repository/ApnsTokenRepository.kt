package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.notifications.apns.model.ApnsToken
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface ApnsTokenRepository : MongoRepository<ApnsToken, ObjectId> {

    fun findAllByUserId(userId: ObjectId): List<ApnsToken>

    fun findAllByUserIdIn(userIds: Collection<ObjectId>): List<ApnsToken>

    fun deleteByToken(token: String)

    fun deleteByTokenIn(tokens: Collection<String>)

    fun deleteByTokenAndUserIdNot(token: String, userId: ObjectId)

    fun findByUserIdAndToken(userId: ObjectId, token: String): ApnsToken?
}
