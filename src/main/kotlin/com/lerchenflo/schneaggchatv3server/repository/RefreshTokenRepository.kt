package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.authentication.model.RefreshToken
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import kotlin.time.Instant

interface RefreshTokenRepository: MongoRepository<RefreshToken, ObjectId> {

    fun findByUserIdAndHashedToken(userId: ObjectId, hashedToken: String): List<RefreshToken>
    fun deleteByUserIdAndHashedToken(userId: ObjectId, hashedToken: String) : Long

    fun findByUserIdAndHashedTokenAndDeletedAtIsNull(userId: ObjectId, hashedToken: String): RefreshToken?


    fun deleteByUserId(userId: ObjectId)

    /**
     * Delete all entries with a deleted at before the passed time
     */
    fun deleteByDeletedAtBefore(time: Instant): Long

}