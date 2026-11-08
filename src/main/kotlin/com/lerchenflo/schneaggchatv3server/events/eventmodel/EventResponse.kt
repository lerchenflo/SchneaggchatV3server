package com.lerchenflo.schneaggchatv3server.events.eventmodel

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong

data class EventResponse(
    val id: String,
    val creatorId: String,
    val type: EventType,
    val title: String,
    val description: String,
    val groupId: String?,
    val location: LatLong?,
    val startDate: Long,
    val closeDate: Long?,
    val invitedUsers: List<String>,
    /** Same users as [invitedUsers], with their current username. */
    val invitedUserNames: List<EventUserNameResponse>,
    val participations: List<EventParticipationResponse> = emptyList(),
    val visibility: EventVisibility,
    val maxUsers: Int? = null,
    val groupDeleteDelay: GroupDeleteDelay,
    /** Reminder lead times in minutes before [startDate], longest first. Empty = none. */
    val reminderMinutes: List<Int>,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val updatedBy: String,

    val creatorName: String,
)

data class EventUserNameResponse(
    val userId: String,
    val userName: String,
)

data class EventSyncResponse(
    val updatedEvents: List<EventResponse>,
    val deletedEvents: List<String>,
    val moreEntries: Boolean,
)
