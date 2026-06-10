package com.lerchenflo.schneaggchatv3server.notifications.websocket.connectiontimelogger

import com.lerchenflo.schneaggchatv3server.notifications.websocket.connectiontimelogger.model.ConnectionLogEntry
import com.lerchenflo.schneaggchatv3server.repository.ConnectionTimeRepository
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import kotlin.time.Duration
import kotlin.time.Instant


@Service
class ConnectionTimeLogger(
    private val connectionTimeRepository: ConnectionTimeRepository
) {


    fun upsertEntry(
        userId: ObjectId,
        startTime: Instant,
        endTime: Instant
    ){
        val duration = endTime - startTime

        connectionTimeRepository.save(
            ConnectionLogEntry(
                userId = userId,
                startTime = startTime,
                endTime = endTime,
                duration = duration
            )
        )
    }

    fun getMaxUserConnectionTime(userId: ObjectId): Duration {

        return connectionTimeRepository.findAllByUserId(userId)
            .map { it.duration }
            .fold(Duration.ZERO) { acc, duration -> acc + duration }
    }

}