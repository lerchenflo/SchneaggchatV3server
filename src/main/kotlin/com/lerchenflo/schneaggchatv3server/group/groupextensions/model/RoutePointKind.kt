package com.lerchenflo.schneaggchatv3server.group.groupextensions.model

enum class RoutePointKind {
    STOP,
    WAYPOINT,
    ACCOMMODATION,
    POI,
    TRANSFER, // switching vehicle ("Umstieg"); name required, may carry continueTransportMode
}
