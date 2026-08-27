package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.group.model.Group
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import kotlin.time.Instant

interface GroupRepository : MongoRepository<Group, ObjectId> {
    fun findByDeletedFalseAndExpiresAtBefore(yesterday: Instant): List<Group>
    fun findByIdAndDeletedFalse(id: ObjectId): Group?
    fun findByIdInAndDeletedFalse(ids: List<ObjectId>): List<Group>
}