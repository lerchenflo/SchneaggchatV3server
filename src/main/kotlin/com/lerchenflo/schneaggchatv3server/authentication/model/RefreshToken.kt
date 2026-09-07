@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.authentication.model

import com.lerchenflo.schneaggchatv3server.authentication.AuthController
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * One row per device session, rotated IN PLACE: `/auth/refresh` atomically swaps [hashedToken]
 * to the new token's hash and moves the old hash into [previousHashedToken] (see
 * `AuthService.refresh`). A client that never received the refresh response keeps retrying with
 * the old token, matches on [previousHashedToken], and gets the stored [rawToken] back — so the
 * replay grace lasts until the client demonstrably advances the chain (uses the new token),
 * not until some cleanup timer fires.
 *
 * Because rotation never inserts, the collection holds exactly one row per logged-in device
 * (login dedups on userId + deviceName + deviceType), which is what makes the active-device
 * count on the website exact: `count(expiresAt > now)` per [deviceType].
 *
 * The `{userId, hashedToken}` and `{userId, previousHashedToken}` indexes are managed
 * programmatically in `MainController.migrateRefreshTokenChains` (not via annotations) so the
 * legacy `active_user_token` partial index can be dropped without an auto-index-creation
 * conflict at startup.
 */
@TypeAlias("refreshtoken")
@Document("refreshTokens")
data class RefreshToken(
    @Id val id: ObjectId = ObjectId.get(),
    val userId: ObjectId,

    /** Hash of the currently valid refresh token of this device. */
    val hashedToken: String,

    /**
     * Hash of the token this row was last rotated away from — one-hop replay recovery for
     * clients that lost the refresh response. Null until the first rotation.
     */
    val previousHashedToken: String? = null,

    /** Raw current token, returned verbatim on replay recovery. */
    val rawToken: String? = null,

    /**
     * Slides forward to now + refresh validity on every rotation. The `expireAfter` TTL is
     * inert (kotlin.time.Instant is stored as a subdocument, not a BSON Date — see
     * `RefreshTokenRepository.deleteByExpiresAtBefore`); kept unchanged so the existing index
     * options don't conflict at startup. `DatabaseCleanupTask` does the actual reaping.
     */
    @Indexed(expireAfter = "0s")
    val expiresAt: Instant,
    val createdAt: Instant = Clock.System.now(),

    //Nullable since tokens issued before this field was added won't have it set
    val deviceName: String? = null,
    val deviceType: AuthController.DEVICETYPE? = null
)
