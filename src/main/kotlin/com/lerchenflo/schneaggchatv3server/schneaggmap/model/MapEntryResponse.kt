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

    /** Username of [updatedBy], resolved server side so the client does not need the user in its DB. */
    val updatedByName: String,
)

fun MapEntry.toMapEntryResponse(updatedByName: String): MapEntryResponse = MapEntryResponse(
    id = id.toHexString(),
    coordinates = coordinates,
    description = description,
    createdBy = createdBy.toHexString(),
    createdAt = createdAt.toEpochMilliseconds(),
    updatedBy = updatedBy.toHexString(),
    updatedAt = updatedAt.toEpochMilliseconds(),
    name = name,
    locationData = locationData,
    updatedByName = updatedByName,
)
