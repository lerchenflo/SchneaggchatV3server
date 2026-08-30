package com.lerchenflo.schneaggchatv3server.core

import com.lerchenflo.schneaggchatv3server.group.GroupService
import com.lerchenflo.schneaggchatv3server.repository.RefreshTokenRepository
import com.lerchenflo.schneaggchatv3server.repository.UserLocationRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import kotlin.time.Clock

@Component
class DatabaseCleanupTask(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val userLocationRepository: UserLocationRepository,
    private val groupService: GroupService,
) {

    /**
     * Runs every minute and clears old database entries for user locations,
     * refresh tokens, and deletes groups (and their connected event, if any)
     * whose expiry timer has passed.
     */
    @Scheduled(fixedDelay = 60_000)
    fun cleanDatabasePeriodically() {
        val deletedTokensCount = refreshTokenRepository.deleteByDeletedAtBefore(Clock.System.now())
        /*if (deletedTokensCount > 0) {
            AppLogger.info("Deleted ${deletedTokensCount} refreshTokens")
        }*/

        // Tokens that simply aged out without ever being rotated/logged out - deletedAt stays
        // null for those, so the sweep above never reaches them. Can't rely on the @Indexed
        // TTL index on expiresAt either (see RefreshTokenRepository.deleteByExpiresAtBefore).
        refreshTokenRepository.deleteByExpiresAtBefore(Clock.System.now())


        val deletedUserlocationsCount = userLocationRepository.deleteByExpiresAtBefore(Clock.System.now())
        /*if (deletedUserlocationsCount > 0) {
            AppLogger.info("Deleted ${deletedUserlocationsCount} user locations")
        }*/

        groupService.deleteExpiredGroups()
    }
}