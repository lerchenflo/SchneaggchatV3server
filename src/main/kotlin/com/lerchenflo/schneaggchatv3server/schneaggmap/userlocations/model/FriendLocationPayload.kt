@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.FriendLocationView
import kotlin.time.ExperimentalTime

/**
 * Wire shape of a friend's live location, pushed over the WebSocket. Mirrors what the old HTTP
 * `UserLocationResponse` returned - ObjectId as hex string, timestamps as epoch millis. Every
 * optional field is already privacy-gated by the time it reaches this payload (see
 * `UserLocationService.buildFriendView`).
 */
data class FriendLocationPayload(
    val userId: String,
    val coordinates: LatLong,
    val locationTime: Long,
    val speed: Double?,
    val heading: Double?,
    val altitude: Double?,
    val batteryLevel: Int?,
    val distanceTraveled24h: Double?,
    val snailTrail: List<SnailTrailPointPayload>,
)

data class SnailTrailPointPayload(
    val coordinates: LatLong,
    val locationTime: Long,
    val speed: Double?,
    val heading: Double?,
)

fun FriendLocationView.toPayload(): FriendLocationPayload = FriendLocationPayload(
    userId = userId.toHexString(),
    coordinates = coordinates,
    locationTime = locationTime.toEpochMilliseconds(),
    speed = speed,
    heading = heading,
    altitude = altitude,
    batteryLevel = batteryLevel,
    distanceTraveled24h = distanceTraveled24hMeters,
    snailTrail = snailTrail.map {
        SnailTrailPointPayload(
            coordinates = it.coordinates,
            locationTime = it.locationTime.toEpochMilliseconds(),
            speed = it.speed,
            heading = it.heading,
        )
    },
)
