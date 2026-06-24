@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations

import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.repository.UserLocationRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.UserLocation
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.toPayload
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipStatus
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.MAX_SNAIL_TRAIL_HOURS
import com.lerchenflo.schneaggchatv3server.util.GeoUtils
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Optional driving telemetry sent alongside a location update. Only lat/long are mandatory. */
data class LocationTelemetry(
    val speed: Double? = null,
    val heading: Double? = null,
    val altitude: Double? = null,
    val batteryLevel: Int? = null,
)

/** One sampled point of a friend's trail (see `FriendshipSetting.snailTrailHours`). */
data class SnailTrailPoint(
    val coordinates: LatLong,
    val locationTime: Instant,
    val speed: Double?,
    val heading: Double?,
)

/** A friend's location as visible to the requester, with every optional field already privacy-gated. */
data class FriendLocationView(
    val userId: ObjectId,
    val coordinates: LatLong,
    val locationTime: Instant,
    val speed: Double?,
    val heading: Double?,
    val altitude: Double?,
    val batteryLevel: Int?,
    val distanceTraveled24hMeters: Double?,
    val snailTrail: List<SnailTrailPoint>,
)

@Service
class UserLocationService(
    private val userLocationRepository: UserLocationRepository,
    private val friendsLookupService: FriendsLookupService,
    private val userLookupService: UserLookupService,
    private val notificationService: NotificationService,
    private val mongoTemplate: MongoTemplate,
) {

    private val locationExpiryMs = 24/* h */ * 60 * 60 * 1000


    /**
     * Handle a location frame a client pushed over the socket. Saves [userId]'s current location,
     * echoes their own 24h distance back to them, and pushes the new location to every friend who
     * BOTH may see it AND is currently connected.
     *
     * Gating is from the SENDER's side here (the push direction): a friend F receives the update
     * only if [userId]'s global `locationShared` is on AND [userId]'s per-friend `shareLocation`
     * towards F is on. Which extra fields F sees (speed/heading via shareSpeedHeading, snailTrail
     * via snailTrailHours) is likewise decided by [userId]'s own per-friend settings towards F;
     * altitude/battery/distance are always included once F may see the location.
     *
     * Invalid frames are dropped silently - a socket has no per-message error response channel.
     */
    fun handleInboundLocationUpdate(userId: ObjectId, location: LatLong, telemetry: LocationTelemetry) {
        if (!ValidationUtils.validateLatLong(location.lat, location.long)) return
        telemetry.speed?.let { if (!ValidationUtils.validateSpeed(it)) return }
        telemetry.heading?.let { if (!ValidationUtils.validateHeading(it)) return }
        telemetry.altitude?.let { if (!ValidationUtils.validateAltitude(it)) return }
        telemetry.batteryLevel?.let { if (!ValidationUtils.validateBatteryLevel(it)) return }

        val saved = saveUserLocation(location, userId, telemetry)

        val sender = userLookupService.findById(userId) ?: return
        if (!sender.locationShared) return // global master switch off -> share with nobody

        friendsLookupService.getAllInteractions(userId)
            .filter { it.status == FriendshipStatus.ACCEPTED && it.shareLocation }
            .forEach { interaction ->
                if (!notificationService.isUserConnected(interaction.userId)) return@forEach // skip offline friends
                val view = buildFriendView(userId, interaction.shareSpeedHeading, interaction.snailTrailHours, saved)
                notificationService.notifyFriendLocationChange(interaction.userId, view.toPayload())
            }
    }

    /**
     * Push [userId] the current locations of all friends they may see (initial load on connect).
     * Gating is from the RECEIVER's side here: a friend G is included only if G's global
     * `locationShared` is on AND G's per-friend setting shares location towards [userId]. The extra
     * fields are gated by G's own per-friend settings towards [userId].
     */
    fun sendInitialSnapshot(userId: ObjectId) {
        val sharingFriends = friendsLookupService.getFriendsForUserUpdate(userId)
            .filter { it.shareLocation }
            .associateBy { it.friendId }
        if (sharingFriends.isEmpty()) {
            notificationService.notifyLocationSnapshot(userId, emptyList())
            return
        }

        // Gate on each friend's global master switch
        val globallySharingFriends = userLookupService.findAllById(sharingFriends.keys.toList())
            .filter { it.locationShared }

        val views = globallySharingFriends.mapNotNull { friend ->
            val latest = userLocationRepository.findTopByUserIdOrderByLocationTimeDesc(friend.id)
                ?: return@mapNotNull null
            val settings = sharingFriends.getValue(friend.id)
            buildFriendView(friend.id, settings.shareSpeedHeading, settings.snailTrailHours, latest)
        }

        notificationService.notifyLocationSnapshot(userId, views.map { it.toPayload() })
    }

    private fun buildFriendView(friendId: ObjectId, shareSpeedHeading: Boolean, snailTrailHours: Int?, latest: UserLocation): FriendLocationView {
        // Altitude, battery and 24h distance are always shared once a friend can see your location.
        return FriendLocationView(
            userId = friendId,
            coordinates = latest.location,
            locationTime = latest.locationTime,
            speed = if (shareSpeedHeading) latest.speed else null,
            heading = if (shareSpeedHeading) latest.heading else null,
            altitude = latest.altitude,
            batteryLevel = latest.batteryLevel,
            distanceTraveled24hMeters = latest.distanceTraveled24hMeters,
            snailTrail = buildSnailTrail(friendId, shareSpeedHeading, snailTrailHours),
        )
    }

    /**
     * Samples the friend's trail (~1 point/minute, oldest to newest) from their own location
     * history. [snailTrailHours] decides the window: null = trail not shared (empty), 0 = the
     * full retained history (the 24h TTL window), N = the last N hours. No extra storage - this
     * reads the same history kept for the distance calculation. Per-point speed/heading is included
     * only when [shareSpeedHeading] is set.
     */
    private fun buildSnailTrail(friendId: ObjectId, shareSpeedHeading: Boolean, snailTrailHours: Int?): List<SnailTrailPoint> {
        if (snailTrailHours == null) return emptyList()

        // 0 means "share my whole retained history" (bounded by the 24h TTL); N means last N hours.
        val windowMinutes = if (snailTrailHours == 0) MAX_SNAIL_TRAIL_HOURS * 60 else snailTrailHours * 60
        val since = Clock.System.now().minus(windowMinutes.minutes)
        val history = userLocationRepository.findByUserIdAndLocationTimeGreaterThanEqualOrderByLocationTimeAsc(friendId, since)

        return history
            .groupBy { it.locationTime.toEpochMilliseconds() / 60_000 } // bucket by minute
            .values
            .map { it.last() } // keep the most recent point in each minute bucket
            .sortedBy { it.locationTime }
            .takeLast(windowMinutes)
            .map {
                SnailTrailPoint(
                    coordinates = it.location,
                    locationTime = it.locationTime,
                    speed = if (shareSpeedHeading) it.speed else null,
                    heading = if (shareSpeedHeading) it.heading else null,
                )
            }
    }

    fun saveUserLocation(location: LatLong, userId: ObjectId, telemetry: LocationTelemetry = LocationTelemetry()): UserLocation {
        val now = Clock.System.now()
        val expiresAt = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + locationExpiryMs)

        // Distance from the user's previous point, ignoring GPS jitter on a stationary device.
        val previous = userLocationRepository.findTopByUserIdOrderByLocationTimeDesc(userId)
        val segmentDistance = previous?.let {
            val distance = GeoUtils.haversineMeters(it.location, location)
            if (distance < GeoUtils.MIN_SEGMENT_METERS) 0.0 else distance
        } ?: 0.0

        val distanceTraveled24h = sumDistanceSince(userId, now.minus(24.hours)) + segmentDistance

        return userLocationRepository.save(
            UserLocation(
                userId = userId,
                location = location,
                locationTime = now,
                speed = telemetry.speed,
                heading = telemetry.heading,
                altitude = telemetry.altitude,
                batteryLevel = telemetry.batteryLevel,
                distanceFromPreviousMeters = segmentDistance,
                distanceTraveled24hMeters = distanceTraveled24h,
                expiresAt = expiresAt,
            )
        )
    }

    private data class DistanceSum(val total: Double?)

    /** Sums `distanceFromPreviousMeters` across every stored point for [userId] since [since]. */
    private fun sumDistanceSince(userId: ObjectId, since: Instant): Double {
        val aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where("userId").`is`(userId).and("locationTime").gte(since)),
            Aggregation.group().sum("distanceFromPreviousMeters").`as`("total"),
        )
        val result = mongoTemplate.aggregate(aggregation, "userlocations", DistanceSum::class.java)
        return result.uniqueMappedResult?.total ?: 0.0
    }

}
