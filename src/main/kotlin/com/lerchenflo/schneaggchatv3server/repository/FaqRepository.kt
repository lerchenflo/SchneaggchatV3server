package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.website.faq.model.FaqEntry
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface FaqRepository : MongoRepository<FaqEntry, ObjectId> {
    fun findByDeletedFalse(): List<FaqEntry>
}
