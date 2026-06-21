package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.UserLocation
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface UserLocationRepository : MongoRepository<UserLocation, ObjectId> {

    fun findTopByUserIdOrderByLocationTimeDesc(userId: ObjectId): UserLocation?

}
