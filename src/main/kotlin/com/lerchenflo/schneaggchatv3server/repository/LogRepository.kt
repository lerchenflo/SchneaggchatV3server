package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.util.Log
import com.lerchenflo.schneaggchatv3server.util.LogType
import org.bson.types.ObjectId
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.MongoRepository

interface LogRepository : MongoRepository<Log, ObjectId> {
    fun countByLogType(logType: LogType): Long

    fun findFirstByLogTypeAndUserIdOrderByTimestampDesc(logType: LogType, userId: ObjectId?): Log?

    fun findByLogTypeAndUserId(logType: LogType, userId: ObjectId?): List<Log>

    /**
     * Ordering comes from the [Pageable]'s `Sort` rather than a fixed `OrderBy` in the method name,
     * so the admin log viewer can sort by timestamp, user or type. The unfiltered listing uses the
     * inherited `findAll(Pageable)`.
     */
    fun findByLogType(logType: LogType, pageable: Pageable): Page<Log>
}