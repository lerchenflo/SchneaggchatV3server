@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.events.eventmodel

import org.bson.types.ObjectId
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class EventParticipationStatus {
    SEEN,
    ACCEPTED,
    DISMISSED,
}

/**
 * One user's response to an event, embedded in [Event.participations] - at most one entry per user.
 * [updatedAt] is always stamped by the server, never taken from the request.
 */
data class EventParticipation(
    val userId: ObjectId,
    val status: EventParticipationStatus,
    val updatedAt: Instant,
)

data class EventParticipationResponse(
    val userId: String,
    /** Current username, for users the viewer has no stored friend for. */
    val userName: String,
    val status: EventParticipationStatus,
    val updatedAt: Long,
)
