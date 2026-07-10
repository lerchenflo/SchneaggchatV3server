@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations

import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.repository.UserLocationRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.FriendLocationPayload
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.FriendLocationSnapshot
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.SnailTrailPointPayload
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.UserLocation
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** A snail-trail point is only added once the user has moved more than this from the last kept point. */
private const val SNAIL_TRAIL_MIN_DISTANCE_METERS = 10.0

/** Optional driving telemetry sent alongside a location update. Only lat/long are mandatory. */
data class LocationTelemetry(
    val speed: Double? = null,
    val heading: Double? = null,
    val altitude: Double? = null,
    val batteryLevel: Int? = null,
)

@Service
class UserLocationService(
    private val userLocationRepository: UserLocationRepository,
    private val friendsLookupService: FriendsLookupService,
    private val notificationService: NotificationService,
    private val mongoTemplate: MongoTemplate,
) {

    private val locationExpiryMs = 24/* h */ * 60 * 60 * 1000

    /** The last committed snail-trail point per user, used to decide when the trail advances (~1/min). */
    private data class TrailMark(val minuteBucket: Long, val location: LatLong)
    private val lastTrailMarks = ConcurrentHashMap<ObjectId, TrailMark>()


    /**
     * Handle a location frame a client pushed over the socket. Saves [userId]'s current location and,
     * for every friend who BOTH may see it AND is currently connected:
     *  - pushes the live position every time (so friends update ~every 5s), and
     *  - pushes a single new snail-trail point only when the trail actually advances (at most once
     *    per minute, and only after moving >10m), to that friend if they have the trail enabled.
     *
     * Gating is from the SENDER's side here: a friend F receives anything only if [userId]'s
     * per-friend `shareLocation` towards F is on (which requires an ACCEPTED friendship). speed/heading
     * are gated by `shareSpeedHeading` and the trail by `shareSnailTrail` (false = no trail);
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
        // Did this update advance the user's snail trail (new minute + moved >10m)? At most once/min.
        val newTrailPoint = if (commitTrailPoint(userId, saved)) saved else null

        friendsLookupService.getAllInteractions(userId)
            .filter { it.status == FriendshipStatus.ACCEPTED && it.shareLocation }
            .forEach { interaction ->
                if (!notificationService.isUserConnected(interaction.userId)) return@forEach // skip offline friends

                notificationService.notifyFriendLocationChange(
                    recipientId = interaction.userId,
                    friend = livePayload(userId, interaction.shareSpeedHeading, saved),
                )

                if (newTrailPoint != null && interaction.shareSnailTrail) {
                    notificationService.notifySnailTrailPointAdded(
                        recipientId = interaction.userId,
                        ownerUserId = userId.toHexString(),
                        point = snailTrailPoint(interaction.shareSpeedHeading, newTrailPoint),
                    )
                }
            }
    }

    /**
     * Records [saved] as the user's newest snail-trail point if it advances the trail: i.e. it's in
     * a later minute than the last committed point and more than [SNAIL_TRAIL_MIN_DISTANCE_METERS]
     * away (the very first point always counts). Returns true if a new point was committed. This is
     * the incremental twin of the batch rule in [buildSnailTrail], so the client's appended points
     * line up with a fresh snapshot.
     */
    private fun commitTrailPoint(userId: ObjectId, saved: UserLocation): Boolean {
        val minute = saved.locationTime.toEpochMilliseconds() / 60_000
        val previous = lastTrailMarks[userId]
        val commit = previous == null ||
                (minute > previous.minuteBucket &&
                        GeoUtils.haversineMeters(previous.location, saved.location) > SNAIL_TRAIL_MIN_DISTANCE_METERS)
        if (commit) lastTrailMarks[userId] = TrailMark(minute, saved.location)
        return commit
    }

    /**
     * Push [userId] their own current position and full snail trail, plus the current positions and
     * full snail trails of all friends they may see (initial load on connect). Gating for friends is
     * from the RECEIVER's side here: a friend G is included only if G's per-friend setting shares
     * location towards [userId] (which requires an ACCEPTED friendship); the extra fields are gated by
     * G's own per-friend settings towards [userId]. [userId]'s own entry is never gated - a user always
     * sees their full own position and trail.
     */
    fun sendInitialLocationSnapshot(userId: ObjectId) {
        val selfSnapshot = userLocationRepository.findTopByUserIdOrderByLocationTimeDesc(userId)?.let { latest ->
            FriendLocationSnapshot(
                position = livePayload(userId, shareSpeedHeading = true, latest),
                snailTrail = buildSnailTrail(userId, shareSpeedHeading = true, shareSnailTrail = true),
            )
        }

        val friendSnapshots = friendsLookupService.getFriendsForUserUpdate(userId)
            .filter { it.shareLocation }
            .mapNotNull { settings ->
                val latest = userLocationRepository.findTopByUserIdOrderByLocationTimeDesc(settings.friendId)
                    ?: return@mapNotNull null
                FriendLocationSnapshot(
                    position = livePayload(settings.friendId, settings.shareSpeedHeading, latest),
                    snailTrail = buildSnailTrail(settings.friendId, settings.shareSpeedHeading, settings.shareSnailTrail),
                )
            }

        notificationService.notifyLocationSnapshot(userId, listOfNotNull(selfSnapshot) + friendSnapshots)
    }

    /** Current position + telemetry (no trail). Altitude/battery/distance are always shared once visible. */
    private fun livePayload(ownerId: ObjectId, shareSpeedHeading: Boolean, latest: UserLocation): FriendLocationPayload =
        FriendLocationPayload(
            userId = ownerId.toHexString(),
            coordinates = latest.location,
            locationTime = latest.locationTime.toEpochMilliseconds(),
            speed = if (shareSpeedHeading) latest.speed else null,
            heading = if (shareSpeedHeading) latest.heading else null,
            altitude = latest.altitude,
            batteryLevel = latest.batteryLevel,
            distanceTraveled24h = latest.distanceTraveled24hMeters,
        )

    private fun snailTrailPoint(shareSpeedHeading: Boolean, point: UserLocation): SnailTrailPointPayload =
        SnailTrailPointPayload(
            coordinates = point.location,
            locationTime = point.locationTime.toEpochMilliseconds(),
            speed = if (shareSpeedHeading) point.speed else null,
            heading = if (shareSpeedHeading) point.heading else null,
        )

    /**
     * Builds a friend's full snail trail (oldest to newest) from their own location history: at most
     * one point per minute, kept only when they moved more than [SNAIL_TRAIL_MIN_DISTANCE_METERS]
     * from the previous kept point - so a stationary user yields almost no trail. [shareSnailTrail]
     * gates the whole trail: false = not shared (empty), true = the full retained history (the 24h
     * TTL window). Uses the same rule as [commitTrailPoint] so incremental points line up with this.
     * No extra storage - reads the same history kept for the distance calculation.
     */
    private fun buildSnailTrail(friendId: ObjectId, shareSpeedHeading: Boolean, shareSnailTrail: Boolean): List<SnailTrailPointPayload> {
        if (!shareSnailTrail) return emptyList()

        // The trail is the full retained history, bounded by the 24h location TTL.
        val since = Clock.System.now().minus(MAX_SNAIL_TRAIL_HOURS.hours)
        val history = userLocationRepository.findByUserIdAndLocationTimeGreaterThanEqualOrderByLocationTimeAsc(friendId, since)

        // Keep the first point of each new minute that is also >10m from the last kept point.
        val kept = mutableListOf<UserLocation>()
        var lastMinute: Long? = null
        for (point in history) {
            val minute = point.locationTime.toEpochMilliseconds() / 60_000
            val last = kept.lastOrNull()
            if (last == null ||
                (minute != lastMinute && GeoUtils.haversineMeters(last.location, point.location) > SNAIL_TRAIL_MIN_DISTANCE_METERS)
            ) {
                kept += point
                lastMinute = minute
            }
        }

        return kept.map { snailTrailPoint(shareSpeedHeading, it) }
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
