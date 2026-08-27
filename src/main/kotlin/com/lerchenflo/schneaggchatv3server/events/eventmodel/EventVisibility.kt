package com.lerchenflo.schneaggchatv3server.events.eventmodel

enum class EventVisibility {
    PUBLIC,               // Everyone (all of the creator's friends, even if not invited) can see and join
    FRIENDS_ONLY,         // Only the creator's friends can see and join, invited or not
    INVITED_FRIENDS_ONLY, // Only explicitly invited users can see and join
}
