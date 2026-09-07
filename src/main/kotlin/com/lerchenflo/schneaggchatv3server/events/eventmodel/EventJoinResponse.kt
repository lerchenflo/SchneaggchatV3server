package com.lerchenflo.schneaggchatv3server.events.eventmodel

import com.lerchenflo.schneaggchatv3server.group.model.GroupResponse

data class EventJoinResponse(
    val groupResponse: GroupResponse, //Return the group belonging to the event
    val event: EventResponse, //The joiner is not always in the event's push audience, so hand them the fresh event directly
)