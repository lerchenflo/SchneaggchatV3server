@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap.model

import com.lerchenflo.schneaggchatv3server.schneaggmap.LocationData
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Document("map_entries")
data class MapEntry(
    @Id val id: ObjectId = ObjectId(),

    val coordinates: LatLong,
    val name: String,
    val description: String,

    val locationData: LocationData,

    val createdBy: ObjectId,
    val createdAt: Instant,

    val updatedBy: ObjectId,
    val updatedAt: Instant,

    val deleted: Boolean = false,
)


enum class MapCategory { STREET, ACTIVITY, FOOD, CAMPING }

fun MapEntry.category(): MapCategory = when (locationData) {
    is LocationData.Radar,
    is LocationData.Street        -> MapCategory.STREET
    is LocationData.SightSeeing,
    is LocationData.SwimmingLocation,
    is LocationData.PartyLocation -> MapCategory.ACTIVITY
    is LocationData.Food          -> MapCategory.FOOD
    is LocationData.Camping       -> MapCategory.CAMPING
}