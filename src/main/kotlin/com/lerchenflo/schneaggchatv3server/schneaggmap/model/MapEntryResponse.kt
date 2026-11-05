package com.lerchenflo.schneaggchatv3server.schneaggmap.model

import com.lerchenflo.schneaggchatv3server.schneaggmap.LocationData

data class MapEntryResponse(
    val id: String,

    val coordinates: LatLong,
    val name: String,
    val description: String,

    val locationData: List<LocationData>,

    val createdBy: String,
    val createdAt: Long,

    val updatedBy: String,
    val updatedAt: Long,

    /** Username of [updatedBy], resolved server side so the client does not need the user in its DB. */
    val updatedByName: String,
)
