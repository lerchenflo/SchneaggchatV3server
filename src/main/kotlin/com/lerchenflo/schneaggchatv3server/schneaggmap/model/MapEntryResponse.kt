@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap.model

import com.lerchenflo.schneaggchatv3server.schneaggmap.LocationData
import kotlin.time.ExperimentalTime

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

)

fun MapEntry.toMapEntryResponse(): MapEntryResponse = MapEntryResponse(
    id = id.toHexString(),
    coordinates = coordinates,
    description = description,
    createdBy = createdBy.toHexString(),
    createdAt = createdAt.toEpochMilliseconds(),
    updatedBy = updatedBy.toHexString(),
    updatedAt = updatedAt.toEpochMilliseconds(),
    name = name,
    locationData = locationData
)
