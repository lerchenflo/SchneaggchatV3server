package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntryVersion
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface MapEntryVersionRepository : MongoRepository<MapEntryVersion, ObjectId> {
    fun findByEntryIdOrderByEditedAtDesc(entryId: ObjectId): List<MapEntryVersion>
}
