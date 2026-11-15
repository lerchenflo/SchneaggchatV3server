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

    /** User id of the last editor. The client resolves the name (its user rows, else `/users/lookup`). */
    val updatedBy: String,
    val updatedAt: Long,
)
