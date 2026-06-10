package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.notifications.websocket.connectiontimelogger.model.ConnectionLogEntry
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface ConnectionTimeRepository:  MongoRepository<ConnectionLogEntry, ObjectId> {

    fun findAllByUserId(userId: ObjectId): List<ConnectionLogEntry>

}
