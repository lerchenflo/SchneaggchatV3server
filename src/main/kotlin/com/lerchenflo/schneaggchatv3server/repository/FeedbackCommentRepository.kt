package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.feedback.model.FeedbackComment
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface FeedbackCommentRepository : MongoRepository<FeedbackComment, ObjectId> {
    fun findByEntryIdOrderByCreatedAtAsc(entryId: ObjectId): List<FeedbackComment>

    fun findByEntryIdInAndDevCommentTrue(entryIds: Collection<ObjectId>): List<FeedbackComment>

    fun deleteByEntryId(entryId: ObjectId): Long
}
