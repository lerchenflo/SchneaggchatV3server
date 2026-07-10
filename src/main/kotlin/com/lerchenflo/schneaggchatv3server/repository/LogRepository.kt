package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.util.Log
import com.lerchenflo.schneaggchatv3server.util.LogType
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository

interface LogRepository : MongoRepository<Log, ObjectId> {
    fun countByLogType(logType: LogType): Long

    fun findFirstByLogTypeAndUserIdOrderByTimestampDesc(logType: LogType, userId: ObjectId?): Log?

    fun findByLogTypeAndUserId(logType: LogType, userId: ObjectId?): List<Log>
}