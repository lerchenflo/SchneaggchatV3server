package com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong

/**
 * Wire shape of a friend's live position, pushed over the WebSocket on every update (~5s).
 * Carries only the current position + telemetry, NOT the snail trail - the trail grows at most once
 * per minute and is delivered separately (full list in the initial snapshot, then one point at a
 * time via `SnailTrailPointAdded`). ObjectId is a hex string, timestamps are epoch millis, and every
 * optional field is already privacy-gated by the time it reaches this payload.
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
)

/** One point of a friend's snail trail. */
data class SnailTrailPointPayload(
    val coordinates: LatLong,
    val locationTime: Long,
    val speed: Double?,
    val heading: Double?,
)

/** Initial-load entry for one friend: their current position plus their full historical snail trail. */
data class FriendLocationSnapshot(
    val position: FriendLocationPayload,
    val snailTrail: List<SnailTrailPointPayload>,
)
