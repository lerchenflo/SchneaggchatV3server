package com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations

import com.lerchenflo.schneaggchatv3server.repository.UserLocationRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.UserLocation
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsLookupService
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.Instant

@Service
class UserLocationService(
    private val userLocationRepository: UserLocationRepository,
    private val friendsLookupService: FriendsLookupService,
    private val userLookupService: UserLookupService,
) {

    private val locationExpiryMs = 24/* h */ * 60 * 60 * 1000


    /**
     * Save [userId]'s current location, then return the latest known locations of all
     * friends who currently share their location with [userId].
     * A friend's location is only included if BOTH:
     *  - their global master switch (User.locationShared) is enabled, AND
     *  - their per-friend setting shares their location towards [userId].
     */
    fun updateAndGetFriendLocations(userId: ObjectId, location: LatLong): List<UserLocation> {
        saveUserLocation(location, userId)

        // Friends who share their location WITH userId (per-friend, ACCEPTED friendships only)
        val sharingFriendIds = friendsLookupService.getFriendsForUserUpdate(userId)
            .filter { it.shareLocation }
            .map { it.friendId }
        if (sharingFriendIds.isEmpty()) return emptyList()

        // Gate on each friend's global master switch
        val globallySharingFriendIds = userLookupService.findAllById(sharingFriendIds)
            .filter { it.locationShared }
            .map { it.id }

        // Latest known location per remaining friend (friends with no stored location are omitted)
        return globallySharingFriendIds.mapNotNull {
            userLocationRepository.findTopByUserIdOrderByLocationTimeDesc(it)
        }
    }


    fun saveUserLocation(location: LatLong, userId: ObjectId): UserLocation {
        val  now = Clock.System.now()
        val expiresAt = now.toEpochMilliseconds() + locationExpiryMs

        return userLocationRepository.save(
            UserLocation(
                userId = userId,
                location = location,
                locationTime = now,
                expiresAt = Instant.fromEpochMilliseconds(expiresAt)
            )
        )
    }



}
