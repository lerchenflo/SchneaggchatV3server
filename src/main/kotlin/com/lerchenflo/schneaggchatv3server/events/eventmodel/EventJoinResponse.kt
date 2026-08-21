package com.lerchenflo.schneaggchatv3server.events.eventmodel

import com.lerchenflo.schneaggchatv3server.group.model.GroupResponse

data class EventJoinResponse(
    val groupResponse: GroupResponse, //Return the group belonging to the event

)