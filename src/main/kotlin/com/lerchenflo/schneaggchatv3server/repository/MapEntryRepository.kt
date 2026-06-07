package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntry
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface MapEntryRepository : MongoRepository<MapEntry, ObjectId> {
    fun findByDeletedFalse(): List<MapEntry>
}
