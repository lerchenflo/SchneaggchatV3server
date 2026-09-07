package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.events.eventmodel.Event
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface EventRepository : MongoRepository<Event, ObjectId> {

    fun findByGroupId(groupId: ObjectId): Event?

}