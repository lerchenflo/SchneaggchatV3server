package com.lerchenflo.schneaggchatv3server.schneaggmap.model

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
