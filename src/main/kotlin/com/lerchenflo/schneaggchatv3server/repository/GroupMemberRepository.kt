package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.group.model.GroupMember
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface GroupMemberRepository : MongoRepository<GroupMember, ObjectId> {

    fun findByuserid(userid: ObjectId): List<GroupMember>

    fun findAllByGroupId(groupId: ObjectId): MutableList<GroupMember>
}