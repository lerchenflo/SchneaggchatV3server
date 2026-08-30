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
     * Deletes tokens that simply aged out (`expiresAt` passed) without ever being rotated or
     * logged out - [deleteByDeletedAtBefore] alone can't reach these, since `deletedAt` stays
     * null for a token nobody ever refreshed again. The `@Indexed(expireAfter = "0s")` TTL index
     * on [RefreshToken.expiresAt] can't reach them either - every `kotlin.time.Instant` field in
     * this app is stored as a `{epochSeconds, nanosecondsOfSecond}` subdocument, not a BSON Date,
     * so Mongo's TTL monitor silently skips it (see `DatabaseCleanupTask`). Mirrors
     * [UserLocationRepository.deleteByExpiresAtBefore], the same already-working pattern.
     */
    fun deleteByExpiresAtBefore(time: Instant): Long

    /**
     * Active devices whose token predates the [RefreshToken.deviceType] field.
     */
    fun countByDeletedAtIsNullAndDeviceTypeIsNull(): Long

}