package com.lerchenflo.schneaggchatv3server.events.eventmodel

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong

data class EventRequest(
    val eventId: String?,
    val type: EventType,
    val title: String,
    val description: String,
    val groupId: String,
    val location: LatLong?,
    val startDate: Long,
    val closeDate: Long?,
    val invitedUsers: List<String>,
    val visibility: EventVisibility,
    val groupDeleteDelay: GroupDeleteDelay,
)



