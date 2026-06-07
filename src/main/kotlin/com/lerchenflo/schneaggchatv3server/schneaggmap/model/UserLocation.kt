@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Document("locations")
data class UserLocation(
    @Id val id: ObjectId = ObjectId(),
    val userid: ObjectId,
    val timestamp: Instant,
    val latitude: Double,
    val longitude: Double,
)
