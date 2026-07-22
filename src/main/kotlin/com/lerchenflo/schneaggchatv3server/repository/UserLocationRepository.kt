@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.UserLocation
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

interface UserLocationRepository : MongoRepository<UserLocation, ObjectId> {

    fun findTopByUserIdOrderByLocationTimeDesc(userId: ObjectId): UserLocation?

    /** Backed by the existing {userId, locationTime} compound index - used to build snail trails. */
    fun findByUserIdAndLocationTimeGreaterThanEqualOrderByLocationTimeAsc(userId: ObjectId, from: Instant): List<UserLocation>


    /**
     * Delete old locations
     */
    fun deleteByExpiresAtBefore(time: Instant): Int

}
