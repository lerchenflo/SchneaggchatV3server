@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.monitoring.model.AppLog
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

interface AppLogRepository : MongoRepository<AppLog, ObjectId> {
    fun deleteByTimestampBefore(timestamp: Instant): Long

    fun findByRequestIdOrderByTimestampAsc(requestId: String): List<AppLog>
}
