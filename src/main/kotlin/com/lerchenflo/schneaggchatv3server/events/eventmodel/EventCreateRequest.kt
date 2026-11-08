package com.lerchenflo.schneaggchatv3server.events.eventmodel

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong

data class EventRequest(
    val eventId: String?,
    val type: EventType,
    val title: String,
    val description: String,
    val location: LatLong?,
    val startDate: Long,
    val closeDate: Long?,
    val invitedUsers: List<String>,
    val visibility: EventVisibility,
    val maxUsers: Int?,
    val groupDeleteDelay: GroupDeleteDelay,
    val createGroup: Boolean = true,
    /** Reminder lead times in minutes before the start. Null (field not sent) keeps the stored ones. */
    val reminderMinutes: List<Int>? = null,
)



