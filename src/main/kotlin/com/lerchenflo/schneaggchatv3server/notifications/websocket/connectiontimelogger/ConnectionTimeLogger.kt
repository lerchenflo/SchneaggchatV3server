package com.lerchenflo.schneaggchatv3server.notifications.websocket.connectiontimelogger

import com.lerchenflo.schneaggchatv3server.notifications.websocket.connectiontimelogger.model.ConnectionLogEntry
import com.lerchenflo.schneaggchatv3server.repository.ConnectionTimeRepository
import org.bson.types.ObjectId
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service
import kotlin.time.Duration
import kotlin.time.Instant


@Service
class ConnectionTimeLogger(
    private val connectionTimeRepository: ConnectionTimeRepository,
    private val mongoTemplate: MongoTemplate,
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

    /** Every logged WebSocket session of [userId], one entry per session (devices are not merged). */
    fun getUserSessions(userId: ObjectId): List<ConnectionLogEntry> =
        connectionTimeRepository.findAllByUserId(userId)

    /**
     * Start of the earliest session in the whole collection, i.e. since when connection time is
     * logged at all. kotlin.time.Instant is stored as a nested {epochSeconds, nanosecondsOfSecond}
     * doc, so sort on its seconds field.
     */
    fun getTrackingStart(): Instant? =
        mongoTemplate.findOne(
            Query().with(Sort.by(Sort.Direction.ASC, "startTime.epochSeconds")).limit(1),
            ConnectionLogEntry::class.java,
        )?.startTime

}