package com.lerchenflo.schneaggchatv3server.events.eventmodel

data class EventParticipationRequest(
    val eventId: String,
    val status: EventParticipationStatus,
)
