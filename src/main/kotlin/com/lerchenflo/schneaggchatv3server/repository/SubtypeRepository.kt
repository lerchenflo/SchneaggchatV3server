package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.Subtype
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface SubtypeRepository : MongoRepository<Subtype, ObjectId> {
    fun findByMainTypeKey(mainTypeKey: String): List<Subtype>
    fun findByMainTypeKeyAndNameIgnoreCase(mainTypeKey: String, name: String): Subtype?
}
