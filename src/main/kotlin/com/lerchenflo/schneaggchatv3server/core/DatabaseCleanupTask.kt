package com.lerchenflo.schneaggchatv3server.core

import com.lerchenflo.schneaggchatv3server.repository.RefreshTokenRepository
import com.lerchenflo.schneaggchatv3server.repository.UserLocationRepository
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import kotlin.time.Clock

@Component
class DatabaseCleanupTask(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val userLocationRepository: UserLocationRepository,
) {

    /**
     * Runs every minute and clears old database entries for user locations,
     * refresh tokens and expired groups.
     */
    @Scheduled(fixedDelay = 60_000)
    fun cleanDatabasePeriodically() {
        val deletedTokensCount = refreshTokenRepository.deleteByDeletedAtBefore(Clock.System.now())
        if (deletedTokensCount > 0) {
            AppLogger.info("Deleted ${deletedTokensCount} refreshTokens")
        }


        val deletedUserlocationsCount = userLocationRepository.deleteByExpiresAtBefore(Clock.System.now())
        if (deletedUserlocationsCount > 0) {
            AppLogger.info("Deleted ${deletedUserlocationsCount} user locations")
        }

        //TODO: Delete old Groups
        // groupRepository.deleteExpiredGroups()
    }
}