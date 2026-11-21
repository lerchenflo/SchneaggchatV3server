package com.lerchenflo.schneaggchatv3server.events.eventmodel

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong

data class EventResponse(
    val id: String,
    val creatorId: String,
    val type: EventType,
    val title: String,
    val description: String,
    val groupId: String?,
    /** Chat a createGroup on this event reconnects instead of creating a new one, see Event.previousGroupId. Null = none. */
    val previousGroupId: String? = null,
    val location: LatLong?,
    val startDate: Long,
    val closeDate: Long?,
    /** User ids. Names are resolved on the client (its user rows, else `/users/lookup`), as for every user id here. */
    val invitedUsers: List<String>,
    val participations: List<EventParticipationResponse> = emptyList(),
    val visibility: EventVisibility,
    val maxUsers: Int? = null,
    val groupDeleteDelay: GroupDeleteDelay,
    /** Reminder lead times in minutes before [startDate], longest first. Empty = none. */
    val reminderMinutes: List<Int>,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val updatedBy: String,
)

data class EventSyncResponse(
    val updatedEvents: List<EventResponse>,
    val deletedEvents: List<String>,
    val moreEntries: Boolean,
)
