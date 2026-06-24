package com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.Instant

@TypeAlias("userlocation")
@Document("userlocations")
@CompoundIndex(name = "userId_locationTime_idx", def = "{'userId': 1, 'locationTime': -1}")
class UserLocation(

    @Id val id: ObjectId = ObjectId.get(),
    val userId: ObjectId,

    val location: LatLong,
    val locationTime: Instant,

    // Optional driving telemetry - only lat/long are mandatory, everything below is nullable.
    val speed: Double? = null,           // meters/second
    val heading: Double? = null,         // degrees, 0-360, 0 = north
    val altitude: Double? = null,        // meters above sea level
    val batteryLevel: Int? = null,       // percent, 0-100

    // Distance bookkeeping, computed at insert time (see UserLocationService.saveUserLocation).
    val distanceFromPreviousMeters: Double = 0.0,
    val distanceTraveled24hMeters: Double = 0.0,

    @Indexed(expireAfter = "0s")
    val expiresAt: Instant,
)