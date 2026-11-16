@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap.model

import com.lerchenflo.schneaggchatv3server.schneaggmap.LocationData
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Document("map_entries")
@TypeAlias("mapentry")
data class MapEntry(
    @Id val id: ObjectId = ObjectId(),

    val coordinates: LatLong,
    val name: String,
    val description: String,

    val locationData: List<LocationData>,

    val createdBy: ObjectId,
    val createdAt: Instant,

    val updatedBy: ObjectId,
    val updatedAt: Instant,

    val deleted: Boolean = false,

    /**
     * Set on entries inserted by [com.lerchenflo.schneaggchatv3server.schneaggmap.MapSeedService]
     * (e.g. `osm-node-<id>`), null for user-created entries. Server-only, never sent to clients.
     */
    val seedKey: String? = null,
)
