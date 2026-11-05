package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.group.groupextensions.model.GroupExtension
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface GroupExtensionRepository : MongoRepository<GroupExtension, ObjectId> {
    fun findByIdAndDeletedFalse(id: ObjectId): GroupExtension?
    fun findByOwnerIdInAndDeletedFalse(ownerIds: List<ObjectId>): List<GroupExtension>
    fun deleteByOwnerId(ownerId: ObjectId)
}
