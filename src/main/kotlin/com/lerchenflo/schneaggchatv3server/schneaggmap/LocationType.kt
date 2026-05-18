package com.lerchenflo.schneaggchatv3server.schneaggmap

import org.bson.types.ObjectId
import kotlin.time.Instant

data class LatLong(val lat: Double, val lng: Double)


sealed interface LocationType {

    val id: ObjectId

    val description: String
    val coordinates: LatLong

    val createdAt: Instant
    val createdBy: ObjectId

    val lastChangedAt: Instant
    val lastChangedBy: ObjectId

    val deleted: Boolean

    data class Radar(
        override val id: ObjectId,
        override val createdAt: Instant,
        override val createdBy: ObjectId,
        override val lastChangedAt: Instant,
        override val lastChangedBy: ObjectId,
        override val description: String,
        override val coordinates: LatLong,
        override val deleted: Boolean,

        val speedLimit: Int,

    ): LocationType

    //Example for subclasses a user might create
    enum class RadarType { REDLIGHT, SPEED, MOBILE, LARGECONTROL }

}
