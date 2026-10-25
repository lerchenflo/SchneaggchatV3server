package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.feedback.model.FeedbackEntry
import com.lerchenflo.schneaggchatv3server.feedback.model.FeedbackType
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface FeedbackEntryRepository : MongoRepository<FeedbackEntry, ObjectId> {
    fun findByTypeAndDeletedFalse(type: FeedbackType): List<FeedbackEntry>

    /** Includes tombstoned seeds - used by the seeder so deleted seeds are not re-inserted. */
    fun findBySeedKeyNotNull(): List<FeedbackEntry>
}
