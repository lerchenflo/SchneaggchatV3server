package com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.Instant

@Document("userlocations")
@CompoundIndex(name = "userId_locationTime_idx", def = "{'userId': 1, 'locationTime': -1}")
class UserLocation(

    @Id val id: ObjectId = ObjectId.get(),
    val userId: ObjectId,

    val location: LatLong,
    val locationTime: Instant,

    @Indexed(expireAfter = "0s")
    val expiresAt: Instant,
)