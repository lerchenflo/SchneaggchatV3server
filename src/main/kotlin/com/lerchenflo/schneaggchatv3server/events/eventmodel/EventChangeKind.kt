package com.lerchenflo.schneaggchatv3server.events.eventmodel

/** What an event change alert is about. One alert lists every kind its save changed. */
enum class EventChangeKind {
    START,     // the start date moved
    LOCATION,  // the location was set, moved or removed
    CANCELLED, // the event was deleted before it ended
}
