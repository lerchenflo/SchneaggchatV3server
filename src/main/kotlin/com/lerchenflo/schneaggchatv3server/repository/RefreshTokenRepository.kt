package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.authentication.AuthController
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

    /**
     * A token with `deletedAt == null` is the current, un-rotated token for its device (see
     * [RefreshToken.deletedAt] / [RefreshToken.replacedByToken] - rotation sets both together on
     * the old entry) - one such row per logged-in device, so this counts active devices per type.
     */
    fun countByDeletedAtIsNullAndDeviceType(deviceType: AuthController.DEVICETYPE): Long

    /**
     * Active devices whose token predates the [RefreshToken.deviceType] field.
     */
    fun countByDeletedAtIsNullAndDeviceTypeIsNull(): Long

}